package com.example.demo.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentServiceClient {

    @Value("${agent-service.base-url:http://127.0.0.1:8100}")
    private String baseUrl;

    public AgentResult chat(JSONArray messages, String model, double temperature, String userMessage,
                            Long conversationId, Integer userId) {
        return chat(messages, model, temperature, userMessage, conversationId, userId, null);
    }

    public AgentResult chat(JSONArray messages, String model, double temperature, String userMessage,
                            Long conversationId, Integer userId, String requestId) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return null;
        try {
            JSONObject options = new JSONObject();
            options.put("provider", "deepseek");
            options.put("model", model);
            options.put("temperature", temperature);
            options.put("strategy", "auto");
            options.put("costBudget", "standard");

            JSONObject request = new JSONObject();
            request.put("protocolVersion", "1.0");
            request.put("requestId", requestId == null ? UUID.randomUUID().toString() : requestId);
            if (conversationId != null) request.put("conversationId", String.valueOf(conversationId));
            if (userId != null) request.put("userId", String.valueOf(userId));
            request.put("messages", messages);
            request.put("options", options);
            JSONObject metadata = new JSONObject();
            metadata.put("userMessage", userMessage);
            request.put("metadata", metadata);
            return sendChatRequest(request);
        } catch (Exception ignored) {
            return null;
        }
    }

    public AgentResult chatNativeEvaluation(String question, Integer userId, String strategy, String costBudget) {
        if (baseUrl == null || baseUrl.trim().isEmpty() || question == null || question.trim().isEmpty()) return null;
        try {
            return sendChatRequest(buildNativeEvaluationRequest(question, userId, strategy, costBudget));
        } catch (Exception ignored) {
            return null;
        }
    }

    static JSONObject buildNativeEvaluationRequest(String question, Integer userId,
                                                     String strategy, String costBudget) {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "你是 MusicHub 音乐助手。请使用可用的只读工具核对本地歌库，并仅根据工具结果回答；没有证据时明确说明未找到，不得编造。");
        messages.add(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", question.trim());
        messages.add(user);

        JSONObject options = new JSONObject();
        options.put("provider", "deepseek");
        options.put("temperature", 0.2d);
        options.put("strategy", normalizeStrategy(strategy));
        options.put("costBudget", normalizeBudget(costBudget));

        String requestId = "eval-native-" + UUID.randomUUID();
        JSONObject request = new JSONObject();
        request.put("protocolVersion", "1.0");
        request.put("requestId", requestId);
        if (userId != null) request.put("userId", String.valueOf(userId));
        request.put("messages", messages);
        request.put("options", options);
        JSONObject metadata = new JSONObject();
        metadata.put("userMessage", question.trim());
        metadata.put("traceId", requestId);
        metadata.put("evaluationMode", "agent_native");
        request.put("metadata", metadata);
        return request;
    }

    public Map<String, Object> previewStrategy(String question, String strategy, String costBudget) {
        if (baseUrl == null || baseUrl.trim().isEmpty() || question == null || question.trim().isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            JSONObject request = new JSONObject();
            request.put("message", question.trim());
            request.put("strategy", normalizeStrategy(strategy));
            request.put("costBudget", normalizeBudget(costBudget));
            URL endpoint = new URL(baseUrl.replaceAll("/+$", "") + "/v1/strategy/preview");
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(4000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return null;
            JSONObject object = JSON.parseObject(readAll(connection.getInputStream()));
            return object == null ? null : new LinkedHashMap<>(object);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private AgentResult sendChatRequest(JSONObject request) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(baseUrl.replaceAll("/+$", "") + "/v1/chat");
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(32000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return null;
            return parseAgentResult(readAll(connection.getInputStream()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalizeStrategy(String value) {
        String normalized = value == null ? "auto" : value.trim().toLowerCase();
        return "direct".equals(normalized) || "react".equals(normalized) ? normalized : "auto";
    }

    private static String normalizeBudget(String value) {
        String normalized = value == null ? "standard" : value.trim().toLowerCase();
        return "low".equals(normalized) || "high".equals(normalized) ? normalized : "standard";
    }

    public Map<String, Object> getExecutionAudit(String traceId) {
        if (baseUrl == null || baseUrl.trim().isEmpty() || traceId == null || traceId.trim().isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(traceId.trim(), StandardCharsets.UTF_8.name());
            URL endpoint = new URL(baseUrl.replaceAll("/+$", "") + "/v1/audit/traces/" + encoded);
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(4000);
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return null;
            JSONObject object = JSON.parseObject(readAll(connection.getInputStream()));
            return object == null ? null : new LinkedHashMap<>(object);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static AgentResult parseAgentResult(String content) {
        JSONObject response = JSON.parseObject(content);
        if (response == null) return null;
        String answer = response.getString("answer");
        if (answer == null || answer.trim().isEmpty()) return null;
        JSONObject usage = response.getJSONObject("usage");
        JSONObject budget = response.getJSONObject("budget");
        List<Map<String, Object>> toolExecutions = new ArrayList<>();
        JSONArray tools = response.getJSONArray("toolExecutions");
        if (tools != null) {
            for (Object item : tools) {
                if (!(item instanceof JSONObject)) continue;
                JSONObject tool = (JSONObject) item;
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("tool", tool.getString("tool"));
                safe.put("success", tool.getBooleanValue("success"));
                safe.put("attempts", tool.getIntValue("attempts"));
                safe.put("durationMs", tool.getIntValue("durationMs"));
                safe.put("errorCode", tool.getString("errorCode"));
                toolExecutions.add(safe);
            }
        }
        return new AgentResult(
                answer,
                usage == null ? 0 : usage.getIntValue("inputTokens"),
                usage == null ? 0 : usage.getIntValue("outputTokens"),
                usage == null ? 0 : usage.getIntValue("totalTokens"),
                response.getString("traceId"), response.getString("strategy"),
                response.getString("strategyReason"), response.getString("finishReason"),
                response.getIntValue("latencyMs"),
                budget == null ? "" : budget.getString("level"),
                budget == null ? 0 : budget.getIntValue("modelCalls"),
                budget == null ? 0 : budget.getIntValue("toolCalls"),
                budget == null ? 0 : budget.getIntValue("toolRounds"),
                budget != null && budget.getBooleanValue("exceeded"),
                budget == null ? "" : budget.getString("stopReason"),
                toolExecutions);
    }

    private String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    public static class AgentResult {
        private final String answer;
        private final int inputTokens;
        private final int outputTokens;
        private final int totalTokens;
        private final String traceId;
        private final String strategy;
        private final String strategyReason;
        private final String finishReason;
        private final int latencyMs;
        private final String costBudget;
        private final int modelCalls;
        private final int toolCalls;
        private final int toolRounds;
        private final boolean budgetExceeded;
        private final String stopReason;
        private final List<Map<String, Object>> toolExecutions;

        public AgentResult(String answer, int inputTokens, int outputTokens, int totalTokens) {
            this(answer, inputTokens, outputTokens, totalTokens, "", "", "", "", 0,
                    "", 0, 0, 0, false, "", Collections.emptyList());
        }

        public AgentResult(String answer, int inputTokens, int outputTokens, int totalTokens,
                           String traceId, String strategy, String strategyReason, String finishReason,
                           int latencyMs, String costBudget, int modelCalls, int toolCalls, int toolRounds,
                           boolean budgetExceeded, String stopReason, List<Map<String, Object>> toolExecutions) {
            this.answer = answer;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
            this.traceId = text(traceId);
            this.strategy = text(strategy);
            this.strategyReason = text(strategyReason);
            this.finishReason = text(finishReason);
            this.latencyMs = latencyMs;
            this.costBudget = text(costBudget);
            this.modelCalls = modelCalls;
            this.toolCalls = toolCalls;
            this.toolRounds = toolRounds;
            this.budgetExceeded = budgetExceeded;
            this.stopReason = text(stopReason);
            this.toolExecutions = Collections.unmodifiableList(new ArrayList<>(toolExecutions));
        }

        private static String text(String value) { return value == null ? "" : value; }

        public String getAnswer() { return answer; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public int getTotalTokens() { return totalTokens; }
        public String getTraceId() { return traceId; }
        public String getStrategy() { return strategy; }
        public String getStrategyReason() { return strategyReason; }
        public String getFinishReason() { return finishReason; }
        public int getLatencyMs() { return latencyMs; }
        public String getCostBudget() { return costBudget; }
        public int getModelCalls() { return modelCalls; }
        public int getToolCalls() { return toolCalls; }
        public int getToolRounds() { return toolRounds; }
        public boolean isBudgetExceeded() { return budgetExceeded; }
        public String getStopReason() { return stopReason; }
        public List<Map<String, Object>> getToolExecutions() { return toolExecutions; }
    }
}
