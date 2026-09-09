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
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
        HttpURLConnection connection = null;
        try {
            JSONObject options = new JSONObject();
            options.put("provider", "deepseek");
            options.put("model", model);
            options.put("temperature", temperature);
            options.put("strategy", "direct");

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
            JSONObject response = JSON.parseObject(readAll(connection.getInputStream()));
            String answer = response.getString("answer");
            if (answer == null || answer.trim().isEmpty()) return null;
            JSONObject usage = response.getJSONObject("usage");
            return new AgentResult(answer, usage == null ? 0 : usage.getIntValue("inputTokens"),
                    usage == null ? 0 : usage.getIntValue("outputTokens"),
                    usage == null ? 0 : usage.getIntValue("totalTokens"));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
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

        public AgentResult(String answer, int inputTokens, int outputTokens, int totalTokens) {
            this.answer = answer;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }

        public String getAnswer() { return answer; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public int getTotalTokens() { return totalTokens; }
    }
}
