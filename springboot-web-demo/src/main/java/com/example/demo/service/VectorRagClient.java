package com.example.demo.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.RetrievalResult;
import com.example.demo.service.retrieval.RetrievalSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Calls the local-only Python vector service and always fails back to local Java retrieval. */
@Service
public class VectorRagClient {

    private static final Logger LOGGER = Logger.getLogger(VectorRagClient.class.getName());

    @Autowired
    private AudioMapper audioMapper;

    @Value("${vector-rag.base-url:http://127.0.0.1:8090}")
    private String baseUrl;

    private final ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vector-rag-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    public List<Audio> search(String question, int limit) {
        List<RetrievalResult> retrievalResults = searchResults(question, limit);
        Map<Integer, Audio> ordered = new LinkedHashMap<>();
        for (RetrievalResult result : retrievalResults) {
            Audio audio = audioMapper.selectById(result.getAudioId());
            if (audio != null) ordered.put(result.getAudioId(), audio);
        }
        return new ArrayList<>(ordered.values());
    }

    public List<RetrievalResult> searchResults(String question, int limit) {
        if (question == null || question.trim().isEmpty() || limit < 1) return Collections.emptyList();
        try {
            JSONObject request = new JSONObject();
            request.put("query", question.trim());
            request.put("top_k", Math.min(Math.max(limit, 1), 20));
            JSONObject response = post("/search", request);
            JSONArray items = response.getJSONArray("items");
            if (items == null || items.isEmpty()) return Collections.emptyList();

            Map<Integer, RetrievalResult> ordered = new LinkedHashMap<>();
            for (int index = 0; index < items.size() && ordered.size() < limit; index++) {
                JSONObject item = items.getJSONObject(index);
                Integer audioId = item.getInteger("audioId");
                if (audioId == null || ordered.containsKey(audioId)) continue;
                ordered.put(audioId, new RetrievalResult(audioId, RetrievalSource.VECTOR,
                        item.getDoubleValue("score"), item.getString("text")));
            }
            return new ArrayList<>(ordered.values());
        } catch (Exception exception) {
            LOGGER.log(Level.FINE, "Local vector search unavailable; using keyword fallback.", exception);
            return Collections.emptyList();
        }
    }

    public void rebuildAsync(String reason) {
        rebuildExecutor.submit(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("reason", reason == null ? "audio-updated" : reason);
                post("/rebuild", request);
            } catch (Exception exception) {
                LOGGER.log(Level.FINE, "Local vector index rebuild skipped because the service is unavailable.", exception);
            }
        });
    }

    private JSONObject post(String path, JSONObject requestBody) throws Exception {
        String endpoint = baseUrl.replaceAll("/+$", "") + path;
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(JSON.toJSONString(requestBody).getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String response = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (status < 200 || status >= 300) throw new IllegalStateException("Vector RAG HTTP " + status + ": " + response);
        return JSON.parseObject(response);
    }

    private String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
        }
        return response.toString();
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdownNow();
    }
}
