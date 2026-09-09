package com.example.demo.service;

import com.alibaba.fastjson.JSON;
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

@Service
public class AgentMemoryClient {

    @Value("${agent-service.base-url:http://127.0.0.1:8100}")
    private String baseUrl;

    public Object getSettings(Integer userId) {
        return request("GET", userPath(userId) + "/settings", null);
    }

    public Object setEnabled(Integer userId, boolean enabled) {
        JSONObject body = new JSONObject();
        body.put("enabled", enabled);
        return request("PUT", userPath(userId) + "/settings", body);
    }

    public Object list(Integer userId) {
        return request("GET", userPath(userId) + "/memories", null);
    }

    public Object capture(Integer userId, Long conversationId, String requestId, String message) {
        JSONObject body = new JSONObject();
        body.put("requestId", requestId);
        if (conversationId != null) body.put("conversationId", String.valueOf(conversationId));
        body.put("message", message);
        return request("POST", userPath(userId) + "/capture", body);
    }

    public Object update(Integer userId, Long memoryId, String content) {
        JSONObject body = new JSONObject();
        body.put("content", content);
        return request("PUT", userPath(userId) + "/memories/" + memoryId, body);
    }

    public Object delete(Integer userId, Long memoryId) {
        return request("DELETE", userPath(userId) + "/memories/" + memoryId, null);
    }

    public Object clear(Integer userId) {
        return request("DELETE", userPath(userId) + "/memories", null);
    }

    public void deleteConversationState(Integer userId, Long conversationId) {
        request("DELETE", userPath(userId) + "/conversation-state/" + conversationId, null);
    }

    private String userPath(Integer userId) {
        return "/v1/memory/users/" + userId;
    }

    private Object request(String method, String path, JSONObject body) {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(baseUrl.replaceAll("/+$", "") + path);
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;
            String response = readAll(connection.getInputStream());
            return response.trim().isEmpty() ? new JSONObject() : JSON.parse(response);
        } catch (Exception exception) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAll(InputStream inputStream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
