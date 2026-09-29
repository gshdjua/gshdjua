package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** User-scoped request throttling and daily external-model budget protection. */
@Service
public class UserLlmQuotaService {
    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<Integer, Deque<Long>> requestWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CachedQuota> quotaCache = new ConcurrentHashMap<>();

    @Value("${llm.quota.default.requests-per-minute:20}") private int defaultRequestsPerMinute;
    @Value("${llm.quota.default.concurrent-streams:2}") private int defaultConcurrentStreams;
    @Value("${llm.quota.default.daily-token-limit:100000}") private int defaultDailyTokenLimit;
    @Value("${llm.quota.default.daily-cost-limit:10.0}") private double defaultDailyCostLimit;

    public UserLlmQuotaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_llm_quota ("
                + "user_id INT PRIMARY KEY,requests_per_minute INT NOT NULL,concurrent_streams INT NOT NULL,"
                + "daily_token_limit INT NOT NULL,daily_cost_limit DECIMAL(16,4) NOT NULL,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS llm_quota_event ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id INT NOT NULL,event_type VARCHAR(40) NOT NULL,"
                + "limit_value DECIMAL(18,4) NOT NULL,current_value DECIMAL(18,4) NOT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,KEY idx_quota_event_user_time(user_id,created_at),"
                + "FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addInvocationUserColumnIfMissing();
    }

    public Permit acquireRequest(Integer userId, boolean stream) {
        if (userId == null) return Permit.none();
        Quota quota = quotaFor(userId);
        long now = System.currentTimeMillis();
        Deque<Long> window = requestWindows.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= now - 60000L) window.removeFirst();
            if (window.size() >= quota.requestsPerMinute) {
                recordEvent(userId, "REQUEST_RATE_LIMIT", quota.requestsPerMinute, window.size());
                throw new QuotaExceededException("REQUEST_RATE_LIMIT",
                        "请求过于频繁，请稍后再试", 60, quota.requestsPerMinute, window.size());
            }
            window.addLast(now);
        }
        if (!stream) return new Permit(null);

        AtomicInteger active = activeStreams.computeIfAbsent(userId, ignored -> new AtomicInteger());
        int current = active.incrementAndGet();
        if (current > quota.concurrentStreams) {
            active.decrementAndGet();
            synchronized (window) { window.removeLastOccurrence(now); }
            recordEvent(userId, "CONCURRENT_STREAM_LIMIT", quota.concurrentStreams, current - 1);
            throw new QuotaExceededException("CONCURRENT_STREAM_LIMIT",
                    "当前正在生成的回答过多，请等待已有回答完成", 2, quota.concurrentStreams, current - 1);
        }
        return new Permit(active);
    }

    /** Called immediately before an external model request; local-only answers never reach this method. */
    public void assertModelBudget(Integer userId) {
        if (userId == null) return;
        Quota quota = quotaFor(userId);
        Usage usage = todayUsage(userId);
        if (quota.dailyTokenLimit > 0 && usage.tokens >= quota.dailyTokenLimit) {
            recordEvent(userId, "DAILY_TOKEN_LIMIT", quota.dailyTokenLimit, usage.tokens);
            throw new QuotaExceededException("DAILY_TOKEN_LIMIT",
                    "今日模型 Token 额度已用完，本次将仅使用本地歌库回答", secondsUntilTomorrow(),
                    quota.dailyTokenLimit, usage.tokens);
        }
        if (quota.dailyCostLimit > 0 && usage.cost >= quota.dailyCostLimit) {
            recordEvent(userId, "DAILY_COST_LIMIT", quota.dailyCostLimit, usage.cost);
            throw new QuotaExceededException("DAILY_COST_LIMIT",
                    "今日模型费用额度已用完，本次将仅使用本地歌库回答", secondsUntilTomorrow(),
                    quota.dailyCostLimit, usage.cost);
        }
    }

    public List<Map<String, Object>> listUserUsage() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id userId,u.username,u.role,q.requests_per_minute requestsPerMinute,"
                        + "q.concurrent_streams concurrentStreams,q.daily_token_limit dailyTokenLimit,"
                        + "q.daily_cost_limit dailyCostLimit,COALESCE(x.tokens,0) usedTokens,"
                        + "COALESCE(x.cost,0) usedCost,COALESCE(x.modelCalls,0) modelCalls,"
                        + "COALESCE(e.blockedCount,0) blockedCount FROM user u "
                        + "LEFT JOIN user_llm_quota q ON q.user_id=u.id "
                        + "LEFT JOIN (SELECT user_id,SUM(input_tokens+output_tokens) tokens,SUM(estimated_cost) cost,"
                        + "SUM(model_calls) modelCalls FROM llm_invocation_log WHERE created_at>=CURRENT_DATE "
                        + "AND user_id IS NOT NULL GROUP BY user_id) x ON x.user_id=u.id "
                        + "LEFT JOIN (SELECT user_id,COUNT(*) blockedCount FROM llm_quota_event "
                        + "WHERE created_at>=CURRENT_DATE GROUP BY user_id) e ON e.user_id=u.id ORDER BY u.id");
        for (Map<String, Object> row : rows) {
            int userId = ((Number) row.get("userId")).intValue();
            applyDefaults(row);
            row.put("activeStreams", activeStreams.getOrDefault(userId, new AtomicInteger()).get());
            row.put("requestsInLastMinute", requestCount(userId));
        }
        return rows;
    }

    public Map<String, Object> updateQuota(int userId, int requestsPerMinute, int concurrentStreams,
                                           int dailyTokenLimit, double dailyCostLimit) {
        if (requestsPerMinute < 1 || requestsPerMinute > 600) {
            throw new IllegalArgumentException("每分钟请求数须在 1 到 600 之间");
        }
        if (concurrentStreams < 1 || concurrentStreams > 20) {
            throw new IllegalArgumentException("并发流数量须在 1 到 20 之间");
        }
        if (dailyTokenLimit < 0 || dailyTokenLimit > 100000000) {
            throw new IllegalArgumentException("每日 Token 上限须在 0 到 100000000 之间，0 表示不限");
        }
        if (dailyCostLimit < 0 || dailyCostLimit > 100000) {
            throw new IllegalArgumentException("每日费用上限须在 0 到 100000 之间，0 表示不限");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE id=?", Integer.class, userId);
        if (exists == null || exists == 0) throw new IllegalArgumentException("用户不存在");
        jdbc.update("INSERT INTO user_llm_quota(user_id,requests_per_minute,concurrent_streams,daily_token_limit,daily_cost_limit) "
                        + "VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE requests_per_minute=VALUES(requests_per_minute),"
                        + "concurrent_streams=VALUES(concurrent_streams),daily_token_limit=VALUES(daily_token_limit),"
                        + "daily_cost_limit=VALUES(daily_cost_limit)",
                userId, requestsPerMinute, concurrentStreams, dailyTokenLimit, dailyCostLimit);
        quotaCache.remove(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("requestsPerMinute", requestsPerMinute);
        result.put("concurrentStreams", concurrentStreams);
        result.put("dailyTokenLimit", dailyTokenLimit);
        result.put("dailyCostLimit", dailyCostLimit);
        return result;
    }

    public Map<String, Object> defaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestsPerMinute", defaultRequestsPerMinute);
        result.put("concurrentStreams", defaultConcurrentStreams);
        result.put("dailyTokenLimit", defaultDailyTokenLimit);
        result.put("dailyCostLimit", defaultDailyCostLimit);
        return result;
    }

    private Quota quotaFor(Integer userId) {
        if (userId == null) return defaultQuota();
        CachedQuota cached = quotaCache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) return cached.quota;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT requests_per_minute,concurrent_streams,daily_token_limit,daily_cost_limit "
                        + "FROM user_llm_quota WHERE user_id=?", userId);
        Quota quota = rows.isEmpty() ? defaultQuota() : new Quota(
                number(rows.get(0).get("requests_per_minute"), defaultRequestsPerMinute).intValue(),
                number(rows.get(0).get("concurrent_streams"), defaultConcurrentStreams).intValue(),
                number(rows.get(0).get("daily_token_limit"), defaultDailyTokenLimit).intValue(),
                number(rows.get(0).get("daily_cost_limit"), defaultDailyCostLimit).doubleValue());
        quotaCache.put(userId, new CachedQuota(quota, now + 30000L));
        return quota;
    }

    private Usage todayUsage(int userId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COALESCE(SUM(input_tokens+output_tokens),0) tokens,"
                        + "COALESCE(SUM(estimated_cost),0) cost FROM llm_invocation_log "
                        + "WHERE user_id=? AND created_at>=CURRENT_DATE", userId);
        return new Usage(number(row.get("tokens"), 0).longValue(), number(row.get("cost"), 0).doubleValue());
    }

    private int requestCount(int userId) {
        Deque<Long> window = requestWindows.get(userId);
        if (window == null) return 0;
        long now = System.currentTimeMillis();
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= now - 60000L) window.removeFirst();
            return window.size();
        }
    }

    private void applyDefaults(Map<String, Object> row) {
        if (row.get("requestsPerMinute") == null) row.put("requestsPerMinute", defaultRequestsPerMinute);
        if (row.get("concurrentStreams") == null) row.put("concurrentStreams", defaultConcurrentStreams);
        if (row.get("dailyTokenLimit") == null) row.put("dailyTokenLimit", defaultDailyTokenLimit);
        if (row.get("dailyCostLimit") == null) row.put("dailyCostLimit", defaultDailyCostLimit);
    }

    private void recordEvent(int userId, String type, double limit, double current) {
        try {
            jdbc.update("INSERT INTO llm_quota_event(user_id,event_type,limit_value,current_value) VALUES(?,?,?,?)",
                    userId, type, BigDecimal.valueOf(limit), BigDecimal.valueOf(current));
        } catch (DataAccessException ignored) {
            // Protection must remain active even if observability persistence is temporarily unavailable.
        }
    }

    private void addInvocationUserColumnIfMissing() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='llm_invocation_log' AND COLUMN_NAME='user_id'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE llm_invocation_log ADD COLUMN user_id INT NULL AFTER id");
        }
        Integer indexCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='llm_invocation_log' "
                        + "AND INDEX_NAME='idx_llm_invocation_user_created'", Integer.class);
        if (indexCount == null || indexCount == 0) {
            jdbc.execute("ALTER TABLE llm_invocation_log ADD KEY idx_llm_invocation_user_created(user_id,created_at)");
        }
    }

    private Quota defaultQuota() {
        return new Quota(defaultRequestsPerMinute, defaultConcurrentStreams,
                defaultDailyTokenLimit, defaultDailyCostLimit);
    }

    private Number number(Object value, Number fallback) {
        return value instanceof Number ? (Number) value : fallback;
    }

    private int secondsUntilTomorrow() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        return (int) Math.max(1, java.time.Duration.between(now, now.toLocalDate().plusDays(1)
                .atStartOfDay(now.getZone())).getSeconds());
    }

    public static final class Permit implements AutoCloseable {
        private final AtomicInteger active;
        private boolean closed;
        private Permit(AtomicInteger active) { this.active = active; }
        public static Permit none() { return new Permit(null); }
        @Override public synchronized void close() {
            if (!closed && active != null) active.updateAndGet(value -> Math.max(0, value - 1));
            closed = true;
        }
    }

    public static final class QuotaExceededException extends RuntimeException {
        private final String reason;
        private final int retryAfterSeconds;
        private final double limit;
        private final double current;
        private QuotaExceededException(String reason, String message, int retryAfterSeconds,
                                       double limit, double current) {
            super(message); this.reason = reason; this.retryAfterSeconds = retryAfterSeconds;
            this.limit = limit; this.current = current;
        }
        public String getReason() { return reason; }
        public int getRetryAfterSeconds() { return retryAfterSeconds; }
        public double getLimit() { return limit; }
        public double getCurrent() { return current; }
    }

    private static final class Quota {
        private final int requestsPerMinute, concurrentStreams, dailyTokenLimit;
        private final double dailyCostLimit;
        private Quota(int rpm, int streams, int tokens, double cost) {
            this.requestsPerMinute = rpm; this.concurrentStreams = streams;
            this.dailyTokenLimit = tokens; this.dailyCostLimit = cost;
        }
    }
    private static final class CachedQuota {
        private final Quota quota; private final long expiresAt;
        private CachedQuota(Quota quota, long expiresAt) { this.quota = quota; this.expiresAt = expiresAt; }
    }
    private static final class Usage {
        private final long tokens; private final double cost;
        private Usage(long tokens, double cost) { this.tokens = tokens; this.cost = cost; }
    }
}
