package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PromptFeedbackService {
    private static final String HELPFUL = "helpful";
    private static final String NOT_HELPFUL = "not_helpful";
    private static final Set<String> RATINGS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(HELPFUL, NOT_HELPFUL)));
    private static final Map<String, String> REASON_LABELS = reasonLabels();

    private final JdbcTemplate jdbc;

    @Value("${prompt.feedback.minimum-sample-size:5}")
    private int minimumSampleSize;

    public PromptFeedbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> save(long messageId, int userId, String rating, String reason) {
        String safeRating = rating == null ? "" : rating.trim().toLowerCase();
        if (!RATINGS.contains(safeRating)) {
            throw new IllegalArgumentException("评价只能是 helpful 或 not_helpful");
        }
        String safeReason = normalizeReason(safeRating, reason);
        String promptVersion = eligiblePromptVersion(messageId, userId);
        jdbc.update("INSERT INTO prompt_answer_feedback(assistant_message_id,prompt_version,rating,reason) "
                        + "VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE prompt_version=VALUES(prompt_version),"
                        + "rating=VALUES(rating),reason=VALUES(reason),updated_at=CURRENT_TIMESTAMP",
                messageId, promptVersion, safeRating, safeReason);
        return feedback(messageId, promptVersion, safeRating, safeReason);
    }

    @Transactional
    public void delete(long messageId, int userId) {
        eligiblePromptVersion(messageId, userId);
        jdbc.update("DELETE FROM prompt_answer_feedback WHERE assistant_message_id=?", messageId);
    }

    public Map<String, Object> summary(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusDays(safeDays));
        List<FeedbackRow> rows = jdbc.query(
                "SELECT prompt_version,rating,reason FROM prompt_answer_feedback "
                        + "WHERE updated_at>=? ORDER BY updated_at",
                (rs, rowNum) -> new FeedbackRow(rs.getString("prompt_version"),
                        rs.getString("rating"), rs.getString("reason")), since);
        Map<String, FeedbackAggregate> grouped = new LinkedHashMap<>();
        for (FeedbackRow row : rows) {
            grouped.computeIfAbsent(row.promptVersion, FeedbackAggregate::new).add(row);
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        for (FeedbackAggregate aggregate : grouped.values()) versions.add(aggregate.toMap());
        versions.sort(Comparator.comparing(item -> String.valueOf(item.get("promptVersion"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", safeDays);
        result.put("minimumSampleSize", Math.max(1, minimumSampleSize));
        result.put("privacy", "只统计消息对应的 Prompt 版本、评价和预设原因；不复制问题或回答正文");
        result.put("reasonLabels", REASON_LABELS);
        result.put("versions", versions);
        return result;
    }

    private String eligiblePromptVersion(long messageId, int userId) {
        List<String> versions = jdbc.query(
                "SELECT apu.prompt_version FROM assistant_message m "
                        + "JOIN assistant_conversation c ON c.id=m.conversation_id "
                        + "JOIN assistant_prompt_usage apu ON apu.assistant_message_id=m.id "
                        + "WHERE m.id=? AND c.user_id=? AND m.role='assistant' "
                        + "AND apu.prompt_version LIKE 'music_answer:v%' LIMIT 1",
                (rs, rowNum) -> rs.getString("prompt_version"), messageId, userId);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("该回答不存在、无权访问或不属于 Prompt 模型回答");
        }
        return versions.get(0);
    }

    private String normalizeReason(String rating, String reason) {
        if (HELPFUL.equals(rating) || reason == null || reason.trim().isEmpty()) return null;
        String safeReason = reason.trim().toLowerCase();
        if (!REASON_LABELS.containsKey(safeReason)) {
            throw new IllegalArgumentException("不支持的反馈原因");
        }
        return safeReason;
    }

    private Map<String, Object> feedback(long messageId, String version, String rating, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assistantMessageId", messageId);
        result.put("promptVersion", version);
        result.put("rating", rating);
        result.put("reason", reason);
        return result;
    }

    private static Map<String, String> reasonLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("inaccurate", "内容不准确");
        labels.put("misunderstood", "没有理解问题");
        labels.put("bad_recommendation", "推荐不合适");
        labels.put("too_long", "内容太长");
        labels.put("other", "其他原因");
        return Collections.unmodifiableMap(labels);
    }

    private final class FeedbackAggregate {
        private final String promptVersion;
        private int helpful;
        private int notHelpful;
        private final Map<String, Integer> reasons = new LinkedHashMap<>();

        private FeedbackAggregate(String promptVersion) {
            this.promptVersion = promptVersion;
            for (String reason : REASON_LABELS.keySet()) reasons.put(reason, 0);
        }

        private void add(FeedbackRow row) {
            if (HELPFUL.equals(row.rating)) helpful++;
            if (NOT_HELPFUL.equals(row.rating)) {
                notHelpful++;
                if (row.reason != null && reasons.containsKey(row.reason)) {
                    reasons.put(row.reason, reasons.get(row.reason) + 1);
                }
            }
        }

        private Map<String, Object> toMap() {
            int count = helpful + notHelpful;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("promptVersion", promptVersion);
            result.put("feedbackCount", count);
            result.put("helpfulCount", helpful);
            result.put("notHelpfulCount", notHelpful);
            result.put("helpfulRate", count == 0 ? 0d : helpful / (double) count);
            result.put("reasonCounts", reasons);
            result.put("sampleSufficient", count >= Math.max(1, minimumSampleSize));
            return result;
        }
    }

    private static final class FeedbackRow {
        private final String promptVersion;
        private final String rating;
        private final String reason;

        private FeedbackRow(String promptVersion, String rating, String reason) {
            this.promptVersion = promptVersion;
            this.rating = rating;
            this.reason = reason;
        }
    }
}
