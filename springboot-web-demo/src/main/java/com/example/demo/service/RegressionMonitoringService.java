package com.example.demo.service;

import com.alibaba.fastjson.JSON;
import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.EvaluationDatasetService;
import com.example.demo.service.retrieval.HybridMusicRetriever;
import com.example.demo.service.retrieval.RetrievalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RegressionMonitoringService {

    private final JdbcTemplate jdbc;
    private final EvaluationDatasetService datasetService;
    private final HybridMusicRetriever retriever;
    private final AudioMapper audioMapper;
    private final VectorRagClient vectorRagClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "retrieval-regression");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${regression.schedule.enabled:true}")
    private boolean scheduledEnabled;

    @Value("${regression.evaluation.top-k:5}")
    private int defaultTopK;

    @Value("${regression.alert.accuracy-drop:0.05}")
    private double accuracyDropThreshold;

    @Value("${regression.alert.recall-drop:0.05}")
    private double recallDropThreshold;

    @Value("${regression.alert.latency-increase-ratio:0.50}")
    private double latencyIncreaseRatio;

    public RegressionMonitoringService(JdbcTemplate jdbc, EvaluationDatasetService datasetService,
                                       HybridMusicRetriever retriever, AudioMapper audioMapper,
                                       VectorRagClient vectorRagClient) {
        this.jdbc = jdbc;
        this.datasetService = datasetService;
        this.retriever = retriever;
        this.audioMapper = audioMapper;
        this.vectorRagClient = vectorRagClient;
    }

    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS retrieval_regression_run ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,trigger_type VARCHAR(24) NOT NULL,status VARCHAR(20) NOT NULL,"
                + "top_k INT NOT NULL,total_cases INT NOT NULL DEFAULT 0,passed_cases INT NOT NULL DEFAULT 0,"
                + "failure_count INT NOT NULL DEFAULT 0,overall_accuracy DECIMAL(10,6) NOT NULL DEFAULT 0,"
                + "recall_at_k DECIMAL(10,6) NOT NULL DEFAULT 0,mrr DECIMAL(10,6) NOT NULL DEFAULT 0,"
                + "average_latency_ms DECIMAL(14,3) NOT NULL DEFAULT 0,baseline_run_id BIGINT NULL,"
                + "accuracy_delta DECIMAL(10,6) NOT NULL DEFAULT 0,recall_delta DECIMAL(10,6) NOT NULL DEFAULT 0,"
                + "index_generation INT NULL,details_json LONGTEXT NULL,error_message VARCHAR(500) NOT NULL DEFAULT '',"
                + "started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMP NULL,"
                + "KEY idx_regression_run_status_time(status,started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS retrieval_regression_alert ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,run_id BIGINT NULL,alert_type VARCHAR(40) NOT NULL,"
                + "severity VARCHAR(16) NOT NULL,message VARCHAR(500) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'open',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,acknowledged_at TIMESTAMP NULL,"
                + "KEY idx_regression_alert_status_time(status,created_at),"
                + "FOREIGN KEY (run_id) REFERENCES retrieval_regression_run(id) ON DELETE SET NULL) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public Map<String, Object> trigger(String triggerType, int topK) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!running.compareAndSet(false, true)) {
            result.put("accepted", false);
            result.put("running", true);
            result.put("message", "已有回归评测正在运行");
            return result;
        }
        final int safeTopK = Math.max(1, Math.min(20, topK));
        final String safeTrigger = "scheduled".equals(triggerType) ? "scheduled" : "manual";
        executor.submit(() -> executeRun(safeTrigger, safeTopK));
        result.put("accepted", true);
        result.put("running", true);
        result.put("topK", safeTopK);
        return result;
    }

    @Scheduled(cron = "${regression.schedule.cron:0 0 3 * * *}")
    public void scheduledRun() {
        if (scheduledEnabled) trigger("scheduled", defaultTopK);
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running.get());
        result.put("scheduleEnabled", scheduledEnabled);
        result.put("defaultTopK", defaultTopK);
        result.put("index", vectorRagClient.indexStatus());
        result.put("runs", jdbc.queryForList("SELECT id,trigger_type AS triggerType,status,top_k AS topK,"
                + "total_cases AS totalCases,passed_cases AS passedCases,failure_count AS failureCount,"
                + "overall_accuracy AS overallAccuracy,recall_at_k AS recallAtK,mrr,"
                + "average_latency_ms AS averageLatencyMs,baseline_run_id AS baselineRunId,"
                + "accuracy_delta AS accuracyDelta,recall_delta AS recallDelta,index_generation AS indexGeneration,"
                + "error_message AS errorMessage,started_at AS startedAt,completed_at AS completedAt "
                + "FROM retrieval_regression_run ORDER BY id DESC LIMIT 10"));
        result.put("alerts", jdbc.queryForList("SELECT id,run_id AS runId,alert_type AS alertType,severity,message,status,"
                + "created_at AS createdAt,acknowledged_at AS acknowledgedAt FROM retrieval_regression_alert "
                + "ORDER BY (status='open') DESC,id DESC LIMIT 30"));
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("accuracyDrop", accuracyDropThreshold);
        thresholds.put("recallDrop", recallDropThreshold);
        thresholds.put("latencyIncreaseRatio", latencyIncreaseRatio);
        result.put("thresholds", thresholds);
        return result;
    }

    public boolean acknowledge(long alertId) {
        return jdbc.update("UPDATE retrieval_regression_alert SET status='acknowledged',"
                + "acknowledged_at=CURRENT_TIMESTAMP WHERE id=? AND status='open'", alertId) > 0;
    }

    public Map<String, Object> rebuildIndex(String reason) {
        vectorRagClient.rebuildAsync(reason == null || reason.trim().isEmpty() ? "manual-admin" : reason.trim());
        return vectorRagClient.indexStatus();
    }

    private void executeRun(String triggerType, int topK) {
        Long runId = insertRun(triggerType, topK);
        Map<String, Object> baseline = latestCompletedRun(runId);
        try {
            EvaluationSummary summary = evaluate(datasetService.loadCases(), topK);
            Map<String, Object> indexStatus = vectorRagClient.indexStatus();
            Integer generation = integer(indexStatus.get("indexGeneration"));
            double baselineAccuracy = number(baseline.get("overallAccuracy"));
            double baselineRecall = number(baseline.get("recallAtK"));
            double accuracyDelta = baseline.isEmpty() ? 0d : summary.overallAccuracy - baselineAccuracy;
            double recallDelta = baseline.isEmpty() ? 0d : summary.recallAtK - baselineRecall;
            jdbc.update("UPDATE retrieval_regression_run SET status='completed',total_cases=?,passed_cases=?,"
                            + "failure_count=?,overall_accuracy=?,recall_at_k=?,mrr=?,average_latency_ms=?,"
                            + "baseline_run_id=?,accuracy_delta=?,recall_delta=?,index_generation=?,details_json=?,"
                            + "completed_at=CURRENT_TIMESTAMP WHERE id=?",
                    summary.totalCases, summary.passedCases, summary.failureCount, summary.overallAccuracy,
                    summary.recallAtK, summary.mrr, summary.averageLatencyMs, longValue(baseline.get("id")),
                    accuracyDelta, recallDelta, generation, JSON.toJSONString(summary.details), runId);
            createRegressionAlerts(runId, summary, baseline, indexStatus);
        } catch (Exception exception) {
            String error = safeMessage(exception);
            jdbc.update("UPDATE retrieval_regression_run SET status='failed',error_message=?,"
                    + "completed_at=CURRENT_TIMESTAMP WHERE id=?", error, runId);
            createAlert(runId, "RUN_FAILED", "critical", "自动回归评测失败：" + error);
        } finally {
            running.set(false);
        }
    }

    EvaluationSummary evaluate(List<Map<String, Object>> cases, int topK) {
        List<Map<String, Object>> details = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        List<Double> reciprocalRanks = new ArrayList<>();
        long totalLatency = 0;
        int scorable = 0;
        int passed = 0;
        for (Map<String, Object> testCase : cases) {
            long started = System.nanoTime();
            String question = text(testCase.get("question"));
            List<Integer> retrievedIds;
            if ("collection_ranking".equals(text(testCase.get("evaluation_mode")))) {
                int count = Math.max(1, Math.min(topK, integerOrDefault(testCase.get("expected_result_count"), 3)));
                List<Audio> ranked = audioMapper.selectRecommended();
                retrievedIds = new ArrayList<>();
                for (int index = 0; index < Math.min(count, ranked.size()); index++) retrievedIds.add(ranked.get(index).getId());
            } else {
                List<RetrievalResult> results = retriever.retrieve(question, history(testCase.get("history")), topK);
                retrievedIds = new ArrayList<>();
                for (RetrievalResult item : results) retrievedIds.add(item.getAudioId());
            }
            long elapsedMs = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            totalLatency += elapsedMs;
            CaseScore score = score(testCase, retrievedIds);
            if (score.scorable) {
                scorable++;
                if (score.passed) passed++;
                if (score.recall != null) recalls.add(score.recall);
                if (score.reciprocalRank != null) reciprocalRanks.add(score.reciprocalRank);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", testCase.get("id"));
            detail.put("category", testCase.get("category"));
            detail.put("passed", score.passed);
            detail.put("scorable", score.scorable);
            detail.put("retrievedAudioIds", retrievedIds);
            detail.put("latencyMs", elapsedMs);
            details.add(detail);
        }
        return new EvaluationSummary(cases.size(), passed, Math.max(0, scorable - passed),
                scorable == 0 ? 0d : (double) passed / scorable, average(recalls), average(reciprocalRanks),
                cases.isEmpty() ? 0d : (double) totalLatency / cases.size(), details);
    }

    private CaseScore score(Map<String, Object> testCase, List<Integer> retrievedIds) {
        if ("collection_ranking".equals(text(testCase.get("evaluation_mode")))) {
            int expectedCount = integerOrDefault(testCase.get("expected_result_count"), 3);
            return new CaseScore(true, retrievedIds.size() == Math.min(expectedCount, audioMapper.selectRecommended().size()), null, null);
        }
        Set<Integer> expected = integerSet(testCase.get("expected_audio_ids"));
        boolean negative = "否定事实".equals(text(testCase.get("category"))) && expected.isEmpty();
        if (expected.isEmpty() && !negative) return new CaseScore(false, false, null, null);
        if (negative) return new CaseScore(true, retrievedIds.isEmpty(), null, null);
        int hits = 0;
        int firstRank = 0;
        Set<Integer> uniqueHits = new LinkedHashSet<>();
        for (int index = 0; index < retrievedIds.size(); index++) {
            Integer id = retrievedIds.get(index);
            if (expected.contains(id)) {
                uniqueHits.add(id);
                hits++;
                if (firstRank == 0) firstRank = index + 1;
            }
        }
        return new CaseScore(true, hits > 0, (double) uniqueHits.size() / expected.size(),
                firstRank == 0 ? 0d : 1d / firstRank);
    }

    private void createRegressionAlerts(Long runId, EvaluationSummary summary, Map<String, Object> baseline,
                                        Map<String, Object> indexStatus) {
        if (!Boolean.TRUE.equals(indexStatus.get("available"))) {
            createAlert(runId, "INDEX_UNAVAILABLE", "critical", "向量索引服务不可用，当前检索可能仅使用本地降级路径。");
        }
        if (baseline.isEmpty()) return;
        double accuracyDrop = number(baseline.get("overallAccuracy")) - summary.overallAccuracy;
        double recallDrop = number(baseline.get("recallAtK")) - summary.recallAtK;
        double baselineLatency = number(baseline.get("averageLatencyMs"));
        if (accuracyDrop >= accuracyDropThreshold) {
            createAlert(runId, "ACCURACY_REGRESSION", "critical",
                    "总体准确率较上次下降 " + percent(accuracyDrop) + "。");
        }
        if (recallDrop >= recallDropThreshold) {
            createAlert(runId, "RECALL_REGRESSION", "warning",
                    "Recall@K 较上次下降 " + percent(recallDrop) + "。");
        }
        if (baselineLatency > 0 && summary.averageLatencyMs > baselineLatency * (1d + latencyIncreaseRatio)) {
            createAlert(runId, "LATENCY_REGRESSION", "warning",
                    "平均检索延迟由 " + Math.round(baselineLatency) + "ms 上升至 "
                            + Math.round(summary.averageLatencyMs) + "ms。");
        }
    }

    private Long insertRun(String triggerType, int topK) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO retrieval_regression_run(trigger_type,status,top_k) VALUES(?,'running',?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, triggerType);
            statement.setInt(2, topK);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private Map<String, Object> latestCompletedRun(Long excludingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,overall_accuracy AS overallAccuracy,"
                + "recall_at_k AS recallAtK,average_latency_ms AS averageLatencyMs FROM retrieval_regression_run "
                + "WHERE status='completed' AND id<>? ORDER BY id DESC LIMIT 1", excludingId);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    private void createAlert(Long runId, String type, String severity, String message) {
        jdbc.update("INSERT INTO retrieval_regression_alert(run_id,alert_type,severity,message) VALUES(?,?,?,?)",
                runId, type, severity, message);
    }

    private List<Map<String, String>> history(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<Map<String, String>> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> raw = (Map<?, ?>) item;
            Map<String, String> message = new LinkedHashMap<>();
            message.put("role", text(raw.get("role")));
            message.put("content", text(raw.get("content")));
            result.add(message);
        }
        return result;
    }

    private Set<Integer> integerSet(Object value) {
        Set<Integer> result = new LinkedHashSet<>();
        if (value instanceof List) for (Object item : (List<?>) value) {
            Integer parsed = integer(item);
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) return 0d;
        double total = 0d;
        for (Double value : values) total += value;
        return total / values.size();
    }

    private String percent(double value) { return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100d); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private double number(Object value) { try { return value == null ? 0d : Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0d; } }
    private Integer integer(Object value) { try { return value == null ? null : Integer.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; } }
    private int integerOrDefault(Object value, int fallback) { Integer parsed = integer(value); return parsed == null ? fallback : parsed; }
    private Long longValue(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; } }
    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) message = exception.getClass().getSimpleName();
        return message.substring(0, Math.min(500, message.length()));
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    static final class CaseScore {
        final boolean scorable;
        final boolean passed;
        final Double recall;
        final Double reciprocalRank;
        CaseScore(boolean scorable, boolean passed, Double recall, Double reciprocalRank) {
            this.scorable = scorable; this.passed = passed; this.recall = recall; this.reciprocalRank = reciprocalRank;
        }
    }

    static final class EvaluationSummary {
        final int totalCases;
        final int passedCases;
        final int failureCount;
        final double overallAccuracy;
        final double recallAtK;
        final double mrr;
        final double averageLatencyMs;
        final List<Map<String, Object>> details;
        EvaluationSummary(int totalCases, int passedCases, int failureCount, double overallAccuracy,
                          double recallAtK, double mrr, double averageLatencyMs,
                          List<Map<String, Object>> details) {
            this.totalCases = totalCases; this.passedCases = passedCases; this.failureCount = failureCount;
            this.overallAccuracy = overallAccuracy; this.recallAtK = recallAtK; this.mrr = mrr;
            this.averageLatencyMs = averageLatencyMs; this.details = details;
        }
    }
}
