package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PromptOnlineMetricsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromptOnlineMetricsService.class);
    private final JdbcTemplate jdbc;

    @Value("${prompt.metrics.input-price-per-million:3}")
    private double inputPricePerMillion;

    @Value("${prompt.metrics.output-price-per-million:9}")
    private double outputPricePerMillion;

    @Value("${prompt.metrics.minimum-sample-size:30}")
    private int minimumSampleSize;

    public PromptOnlineMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String promptVersion, int modelCalls, boolean success,
                       int inputTokens, int outputTokens, long latencyMs) {
        record(promptVersion, "none", "none", inputPricePerMillion, outputPricePerMillion,
                modelCalls, success, inputTokens, outputTokens, latencyMs);
    }

    public void record(String promptVersion, String provider, String model,
                       double inputUnitPrice, double outputUnitPrice, int modelCalls, boolean success,
                       int inputTokens, int outputTokens, long latencyMs) {
        if (promptVersion == null || !promptVersion.startsWith("music_answer:v")) return;
        try {
            jdbc.update("INSERT INTO prompt_online_metric(prompt_version,provider,model,model_calls,success,input_tokens,output_tokens,latency_ms,input_unit_price,output_unit_price) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)", promptVersion, safe(provider), safe(model), Math.max(0, modelCalls), success,
                    Math.max(0, inputTokens), Math.max(0, outputTokens), Math.max(0, Math.min(Integer.MAX_VALUE, latencyMs)),
                    Math.max(0d, inputUnitPrice), Math.max(0d, outputUnitPrice));
        } catch (DataAccessException exception) {
            LOGGER.warn("Could not record anonymous prompt metric for {}: {}",
                    promptVersion, exception.getClass().getSimpleName());
        }
    }

    public Map<String, Object> summary(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusDays(safeDays));
        List<MetricRow> rows = jdbc.query(
                "SELECT prompt_version,provider,model,model_calls,success,input_tokens,output_tokens,latency_ms,input_unit_price,output_unit_price "
                        + "FROM prompt_online_metric WHERE created_at>=? ORDER BY created_at",
                (rs, rowNum) -> new MetricRow(rs.getString("prompt_version"), rs.getString("provider"), rs.getString("model"),
                        rs.getInt("model_calls"), rs.getBoolean("success"),
                        rs.getInt("input_tokens"), rs.getInt("output_tokens"), rs.getInt("latency_ms"),
                        rs.getDouble("input_unit_price"), rs.getDouble("output_unit_price")), since);
        Map<String, Aggregate> grouped = new LinkedHashMap<>();
        for (MetricRow row : rows) grouped.computeIfAbsent(row.promptVersion + "\n" + row.provider + "\n" + row.model,
                key -> new Aggregate(row.promptVersion, row.provider, row.model)).add(row);
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Aggregate aggregate : grouped.values()) versions.add(aggregate.toMap());
        versions.sort(Comparator.comparing(item -> String.valueOf(item.get("promptVersion"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", safeDays);
        result.put("minimumSampleSize", Math.max(1, minimumSampleSize));
        result.put("inputPricePerMillion", inputPricePerMillion);
        result.put("outputPricePerMillion", outputPricePerMillion);
        result.put("privacy", "仅统计版本号、调用状态、Token 与耗时；不保存用户、问题或回答");
        result.put("versions", versions);
        return result;
    }

    public Map<String, Object> deleteRecent(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusDays(safeDays));
        int deletedCount = jdbc.update("DELETE FROM prompt_online_metric WHERE created_at>=?", since);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", safeDays);
        result.put("deletedCount", deletedCount);
        return result;
    }

    private final class Aggregate {
        private final String promptVersion, provider, model;
        private int requests;
        private int modelCalls;
        private int modelRequests;
        private int errors;
        private long inputTokens;
        private long outputTokens;
        private long latencyTotal;
        private final List<Integer> latencies = new ArrayList<>();
        private double estimatedCost;

        private Aggregate(String promptVersion, String provider, String model) {
            this.promptVersion = promptVersion; this.provider = provider; this.model = model;
        }

        private void add(MetricRow row) {
            requests++;
            if (row.modelCalls > 0) {
                modelRequests++;
                modelCalls += row.modelCalls;
                if (!row.success) errors++;
            }
            inputTokens += row.inputTokens;
            outputTokens += row.outputTokens;
            estimatedCost += row.inputTokens / 1_000_000d * row.inputUnitPrice
                    + row.outputTokens / 1_000_000d * row.outputUnitPrice;
            latencyTotal += row.latencyMs;
            latencies.add(row.latencyMs);
        }

        private Map<String, Object> toMap() {
            latencies.sort(Integer::compareTo);
            int p95Index = latencies.isEmpty() ? 0 : Math.min(latencies.size() - 1,
                    Math.max(0, (int) Math.ceil(latencies.size() * 0.95d) - 1));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("promptVersion", promptVersion);
            result.put("provider", provider);
            result.put("model", model);
            result.put("requestCount", requests);
            result.put("modelCalls", modelCalls);
            result.put("inputTokens", inputTokens);
            result.put("outputTokens", outputTokens);
            result.put("estimatedCost", estimatedCost);
            result.put("averageLatencyMs", requests == 0 ? 0d : latencyTotal / (double) requests);
            result.put("p95LatencyMs", latencies.isEmpty() ? 0 : latencies.get(p95Index));
            result.put("errorCount", errors);
            result.put("errorRate", modelRequests == 0 ? 0d : errors / (double) modelRequests);
            result.put("sampleSufficient", requests >= Math.max(1, minimumSampleSize));
            return result;
        }
    }

    private static final class MetricRow {
        private final String promptVersion, provider, model;
        private final int modelCalls;
        private final boolean success;
        private final int inputTokens;
        private final int outputTokens;
        private final int latencyMs;
        private final double inputUnitPrice, outputUnitPrice;

        private MetricRow(String promptVersion, String provider, String model, int modelCalls, boolean success,
                          int inputTokens, int outputTokens, int latencyMs, double inputUnitPrice, double outputUnitPrice) {
            this.promptVersion = promptVersion;
            this.provider = provider; this.model = model;
            this.modelCalls = modelCalls;
            this.success = success;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.latencyMs = latencyMs;
            this.inputUnitPrice = inputUnitPrice; this.outputUnitPrice = outputUnitPrice;
        }
    }

    private String safe(String value) { return value == null || value.trim().isEmpty() ? "none" : value.trim(); }
}
