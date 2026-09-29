package com.example.demo.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelInvocationLogService {
    private final JdbcTemplate jdbc;

    public ModelInvocationLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS llm_invocation_log ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id INT NULL,requested_trace_id VARCHAR(100) NOT NULL,"
                + "fallback_trace_id VARCHAR(120) NOT NULL DEFAULT '',prompt_version VARCHAR(120) NOT NULL DEFAULT 'none',"
                + "requested_provider VARCHAR(40) NOT NULL,requested_model VARCHAR(120) NOT NULL,"
                + "actual_provider VARCHAR(40) NOT NULL,actual_model VARCHAR(120) NOT NULL,"
                + "execution_path VARCHAR(30) NOT NULL,fallback_reason VARCHAR(64) NOT NULL DEFAULT '',"
                + "model_calls INT NOT NULL DEFAULT 0,retry_count INT NOT NULL DEFAULT 0,fallback_count INT NOT NULL DEFAULT 0,"
                + "input_tokens INT NOT NULL DEFAULT 0,output_tokens INT NOT NULL DEFAULT 0,"
                + "latency_ms INT NOT NULL DEFAULT 0,estimated_cost DECIMAL(16,8) NOT NULL DEFAULT 0,"
                + "first_token_ms INT NOT NULL DEFAULT 0,stream_event_count INT NOT NULL DEFAULT 0,"
                + "stream_char_count INT NOT NULL DEFAULT 0,stream_status VARCHAR(24) NOT NULL DEFAULT 'not_streamed',"
                + "interrupted_at_chars INT NOT NULL DEFAULT 0,usage_source VARCHAR(20) NOT NULL DEFAULT 'provider',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,KEY idx_llm_invocation_created(created_at),"
                + "KEY idx_llm_invocation_user_created(user_id,created_at),"
                + "KEY idx_llm_invocation_requested(requested_provider,requested_model,created_at),"
                + "KEY idx_llm_invocation_path(execution_path,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("first_token_ms", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("stream_event_count", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("stream_char_count", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("stream_status", "VARCHAR(24) NOT NULL DEFAULT 'not_streamed'");
        addColumnIfMissing("interrupted_at_chars", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("usage_source", "VARCHAR(20) NOT NULL DEFAULT 'provider'");
        addColumnIfMissing("user_id", "INT NULL AFTER id");
        addIndexIfMissing("idx_llm_invocation_user_created", "user_id,created_at");
        jdbc.update("UPDATE llm_invocation_log SET usage_source='unavailable' "
                + "WHERE stream_status='cancelled' AND input_tokens=0 AND output_tokens=0 "
                + "AND model_calls>0 AND usage_source='provider'");
    }

    public void finishStream(Integer userId, String traceId, String provider, String model, long firstTokenMs,
                             long totalMs, int eventCount, int charCount, int modelCalls,
                             int estimatedInputTokens, int estimatedOutputTokens,
                             double estimatedCost, String status) {
        finishStream(traceId, provider, model, firstTokenMs, totalMs, eventCount, charCount,
                modelCalls, estimatedInputTokens, estimatedOutputTokens, estimatedCost, status);
        associateUser(traceId, userId);
    }

    public void finishStream(String traceId, String provider, String model, long firstTokenMs,
                             long totalMs, int eventCount, int charCount, int modelCalls,
                             int estimatedInputTokens, int estimatedOutputTokens,
                             double estimatedCost, String status) {
        String safeStatus = safe(status).toLowerCase();
        if (!("completed".equals(safeStatus) || "cancelled".equals(safeStatus)
                || "error".equals(safeStatus))) safeStatus = "error";
        int interruptedAt = "completed".equals(safeStatus) ? 0 : Math.max(0, charCount);
        int updated = jdbc.update("UPDATE llm_invocation_log SET first_token_ms=?,stream_event_count=?,"
                        + "stream_char_count=?,stream_status=?,interrupted_at_chars=?,latency_ms=GREATEST(latency_ms,?) "
                        + "WHERE requested_trace_id=?",
                Math.max(0, firstTokenMs), Math.max(0, eventCount), Math.max(0, charCount), safeStatus,
                interruptedAt, Math.max(0, totalMs), safe(traceId));
        if (updated == 0 && !"completed".equals(safeStatus)) {
            jdbc.update("INSERT INTO llm_invocation_log(requested_trace_id,prompt_version,requested_provider,"
                            + "requested_model,actual_provider,actual_model,execution_path,fallback_reason,model_calls,"
                            + "input_tokens,output_tokens,latency_ms,estimated_cost,first_token_ms,stream_event_count,"
                            + "stream_char_count,stream_status,interrupted_at_chars,usage_source) "
                            + "VALUES(?,'none',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    safe(traceId), safe(provider), safe(model), modelCalls > 0 ? safe(provider) : "none",
                    modelCalls > 0 ? safe(model) : "none",
                    "cancelled".equals(safeStatus) ? "cancelled" : "stream_error",
                    "cancelled".equals(safeStatus) ? "CLIENT_CANCELLED" : "STREAM_ERROR",
                    Math.max(0, modelCalls), Math.max(0, estimatedInputTokens), Math.max(0, estimatedOutputTokens),
                    Math.max(0, totalMs), Math.max(0, estimatedCost), Math.max(0, firstTokenMs),
                    Math.max(0, eventCount), Math.max(0, charCount), safeStatus, interruptedAt,
                    modelCalls > 0 ? "estimated" : "not_called");
        }
    }

    public void record(Integer userId, String requestedTraceId, String fallbackTraceId,
                       LlmModelCatalogService.ModelConfig requestedModel,
                       DeepSeekMusicAgent.ReplyResult requestedAttempt,
                       LlmModelCatalogService.ModelConfig fallbackModel,
                       DeepSeekMusicAgent.ReplyResult fallbackAttempt,
                       DeepSeekMusicAgent.ReplyResult finalResult) {
        record(requestedTraceId, fallbackTraceId, requestedModel, requestedAttempt,
                fallbackModel, fallbackAttempt, finalResult);
        associateUser(requestedTraceId, userId);
    }

    public void record(String requestedTraceId, String fallbackTraceId,
                       LlmModelCatalogService.ModelConfig requestedModel,
                       DeepSeekMusicAgent.ReplyResult requestedAttempt,
                       LlmModelCatalogService.ModelConfig fallbackModel,
                       DeepSeekMusicAgent.ReplyResult fallbackAttempt,
                       DeepSeekMusicAgent.ReplyResult finalResult) {
        if (requestedAttempt == null || requestedAttempt.getModelCalls() <= 0) return;
        double cost = cost(requestedModel, requestedAttempt);
        if (fallbackModel != null && fallbackAttempt != null) cost += cost(fallbackModel, fallbackAttempt);
        jdbc.update("INSERT INTO llm_invocation_log(requested_trace_id,fallback_trace_id,prompt_version,"
                        + "requested_provider,requested_model,actual_provider,actual_model,execution_path,fallback_reason,"
                        + "model_calls,retry_count,fallback_count,input_tokens,output_tokens,latency_ms,estimated_cost) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                safe(requestedTraceId), safe(fallbackTraceId), safe(finalResult.getPromptVersion()),
                safe(requestedModel.getProvider()), safe(requestedModel.getModel()),
                safe(finalResult.getProvider()), safe(finalResult.getModel()), safe(finalResult.getExecutionPath()),
                safe(finalResult.getFallbackReason()), finalResult.getModelCalls(), finalResult.getRetryCount(),
                finalResult.getFallbackCount(), finalResult.getInputTokens(), finalResult.getOutputTokens(),
                finalResult.getLatencyMs(), cost);
    }

    public void associateUser(String traceId, Integer userId) {
        if (userId != null) jdbc.update("UPDATE llm_invocation_log SET user_id=? WHERE requested_trace_id=?",
                userId, safe(traceId));
    }

    public Map<String, Object> search(int days, String provider, String model, String status, int limit) {
        Filter filter = filter(days, provider, model, status);
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<Object> listArgs = new ArrayList<>(filter.args);
        listArgs.add(safeLimit);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT id,user_id,requested_trace_id,fallback_trace_id,prompt_version,requested_provider,requested_model,"
                        + "actual_provider,actual_model,execution_path,fallback_reason,model_calls,retry_count,fallback_count,"
                        + "input_tokens,output_tokens,latency_ms,estimated_cost,first_token_ms,stream_event_count,"
                        + "stream_char_count,stream_status,interrupted_at_chars,usage_source,created_at FROM llm_invocation_log"
                        + filter.where + " ORDER BY created_at DESC,id DESC LIMIT ?",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("userId", rs.getObject("user_id"));
                    item.put("requestedTraceId", rs.getString("requested_trace_id"));
                    item.put("fallbackTraceId", rs.getString("fallback_trace_id"));
                    item.put("promptVersion", rs.getString("prompt_version"));
                    item.put("requestedProvider", rs.getString("requested_provider"));
                    item.put("requestedModel", rs.getString("requested_model"));
                    item.put("actualProvider", rs.getString("actual_provider"));
                    item.put("actualModel", rs.getString("actual_model"));
                    item.put("executionPath", rs.getString("execution_path"));
                    item.put("fallbackReason", rs.getString("fallback_reason"));
                    item.put("modelCalls", rs.getInt("model_calls"));
                    item.put("retryCount", rs.getInt("retry_count"));
                    item.put("fallbackCount", rs.getInt("fallback_count"));
                    item.put("inputTokens", rs.getInt("input_tokens"));
                    item.put("outputTokens", rs.getInt("output_tokens"));
                    item.put("latencyMs", rs.getInt("latency_ms"));
                    item.put("estimatedCost", rs.getDouble("estimated_cost"));
                    item.put("firstTokenMs", rs.getInt("first_token_ms"));
                    item.put("streamEventCount", rs.getInt("stream_event_count"));
                    item.put("streamCharCount", rs.getInt("stream_char_count"));
                    item.put("streamStatus", rs.getString("stream_status"));
                    item.put("interruptedAtChars", rs.getInt("interrupted_at_chars"));
                    item.put("usageSource", rs.getString("usage_source"));
                    item.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
                    return item;
                }, listArgs.toArray());
        Map<String, Object> summary = jdbc.queryForMap(
                "SELECT COUNT(*) totalCount,COALESCE(SUM(execution_path='model_fallback'),0) fallbackCount,"
                        + "COALESCE(SUM(execution_path='local_fallback'),0) localFallbackCount,"
                        + "COALESCE(SUM(stream_status='cancelled'),0) cancelledCount,"
                        + "COALESCE(SUM(stream_status='error'),0) streamErrorCount,"
                        + "COALESCE(SUM(usage_source='estimated'),0) estimatedUsageCount,"
                        + "COALESCE(SUM(usage_source='unavailable'),0) unavailableUsageCount,"
                        + "COALESCE(SUM(estimated_cost),0) totalCost,COALESCE(AVG(latency_ms),0) averageLatencyMs,"
                        + "COALESCE(AVG(NULLIF(first_token_ms,0)),0) averageFirstTokenMs "
                        + "FROM llm_invocation_log" + filter.where, filter.args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("summary", summary);
        result.put("limit", safeLimit);
        return result;
    }

    public int delete(int days, String provider, String model, String status) {
        Filter filter = filter(days, provider, model, status);
        return jdbc.update("DELETE FROM llm_invocation_log" + filter.where, filter.args.toArray());
    }

    private Filter filter(int days, String provider, String model, String status) {
        int safeDays = Math.max(1, Math.min(90, days));
        StringBuilder where = new StringBuilder(" WHERE created_at>=DATE_SUB(NOW(),INTERVAL ? DAY)");
        List<Object> args = new ArrayList<>();
        args.add(safeDays);
        String safeProvider = safe(provider).toLowerCase();
        if (!safeProvider.isEmpty()) {
            where.append(" AND (requested_provider=? OR actual_provider=?)");
            args.add(safeProvider); args.add(safeProvider);
        }
        String safeModel = safe(model);
        if (!safeModel.isEmpty()) {
            where.append(" AND (requested_model=? OR actual_model=?)");
            args.add(safeModel); args.add(safeModel);
        }
        String safeStatus = safe(status).toLowerCase();
        if ("success".equals(safeStatus)) where.append(" AND execution_path='model'");
        else if ("fallback".equals(safeStatus)) where.append(" AND execution_path='model_fallback'");
        else if ("failed".equals(safeStatus)) where.append(" AND execution_path='local_fallback'");
        else if ("cancelled".equals(safeStatus)) where.append(" AND stream_status='cancelled'");
        else if ("stream_error".equals(safeStatus)) where.append(" AND stream_status='error'");
        else if (!safeStatus.isEmpty() && !"all".equals(safeStatus)) throw new IllegalArgumentException("调用状态筛选值不合法");
        return new Filter(where.toString(), args);
    }

    private double cost(LlmModelCatalogService.ModelConfig model, DeepSeekMusicAgent.ReplyResult attempt) {
        return attempt.getInputTokens() * model.getInputPrice() / 1_000_000d
                + attempt.getOutputTokens() * model.getOutputPrice() / 1_000_000d;
    }

    private void addColumnIfMissing(String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='llm_invocation_log' AND COLUMN_NAME=?",
                Integer.class, column);
        if (count == null || count == 0) jdbc.execute("ALTER TABLE llm_invocation_log ADD COLUMN " + column + " " + definition);
    }

    private void addIndexIfMissing(String index, String columns) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='llm_invocation_log' AND INDEX_NAME=?",
                Integer.class, index);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE llm_invocation_log ADD KEY " + index + "(" + columns + ")");
        }
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    private static final class Filter {
        private final String where;
        private final List<Object> args;
        private Filter(String where, List<Object> args) { this.where = where; this.args = args; }
    }
}
