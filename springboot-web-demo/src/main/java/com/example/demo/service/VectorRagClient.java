package com.example.demo.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.cache.TimedSingleFlightCache;
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

    private final TimedSingleFlightCache<SearchKey, List<RetrievalResult>> searchCache =
            new TimedSingleFlightCache<>(60000L, 256);

    @Value("${vector-rag.search-cache.ttl-ms:60000}")
    void configureSearchCacheTtl(long ttlMillis) {
        searchCache.configure(ttlMillis, 256);
    }

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
        return searchResults(question, limit, null);
    }

    public List<RetrievalResult> searchResults(String question, int limit, List<Integer> audioIds) {
        if (question == null || question.trim().isEmpty() || limit < 1) return Collections.emptyList();
        int normalizedLimit = Math.min(Math.max(limit, 1), 20);
        SearchKey key = new SearchKey(question.trim(), normalizedLimit, audioIds);
        try {
            return new ArrayList<>(searchCache.get(key, () -> loadSearchResults(key)));
        } catch (Exception exception) {
            LOGGER.log(Level.FINE, "Local vector search unavailable; using keyword fallback.", exception);
            return Collections.emptyList();
        }
    }

    private List<RetrievalResult> loadSearchResults(SearchKey key) {
        try {
            JSONObject request = new JSONObject();
            request.put("query", key.question);
            request.put("top_k", key.limit);
            if (!key.audioIds.isEmpty()) request.put("audio_ids", key.audioIds);
            JSONObject response = post("/search", request);
            JSONArray items = response.getJSONArray("items");
            if (items == null || items.isEmpty()) return Collections.emptyList();

            Map<Integer, RetrievalResult> ordered = new LinkedHashMap<>();
            for (int index = 0; index < items.size() && ordered.size() < key.limit; index++) {
                JSONObject item = items.getJSONObject(index);
                Integer audioId = item.getInteger("audioId");
                if (audioId == null || ordered.containsKey(audioId)) continue;
                ordered.put(audioId, new RetrievalResult(audioId, RetrievalSource.VECTOR,
                        item.getDoubleValue("score"), item.getString("text")));
            }
            return Collections.unmodifiableList(new ArrayList<>(ordered.values()));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public Map<String, Object> cacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("springSearch", searchCache.stats());
        stats.put("ragService", remoteCacheStats());
        return stats;
    }

    private Map<String, Object> remoteCacheStats() {
        Map<String, Object> unavailable = new LinkedHashMap<>();
        unavailable.put("available", false);
        try {
            String endpoint = baseUrl.replaceAll("/+$", "") + "/cache/stats";
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(3000);
            int status = connection.getResponseCode();
            String response = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                unavailable.put("error", "RAG HTTP " + status);
                return unavailable;
            }
            Map<String, Object> remote = new LinkedHashMap<>();
            remote.put("available", true);
            JSONObject body = JSON.parseObject(response);
            remote.put("embedding", body.getJSONObject("embedding"));
            remote.put("search", body.getJSONObject("search"));
            remote.put("indexGeneration", body.getInteger("indexGeneration"));
            return remote;
        } catch (Exception exception) {
            unavailable.put("error", "RAG 服务不可用");
            return unavailable;
        }
    }

    public void rebuildAsync(String reason) {
        searchCache.clear();
        rebuildExecutor.submit(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("reason", reason == null ? "audio-updated" : reason);
                post("/rebuild", request);
                searchCache.clear();
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

    private static final class SearchKey {
        private final String question;
        private final int limit;
        private final List<Integer> audioIds;

        private SearchKey(String question, int limit, List<Integer> audioIds) {
            this.question = question;
            this.limit = limit;
            if (audioIds == null || audioIds.isEmpty()) {
                this.audioIds = Collections.emptyList();
            } else {
                List<Integer> sorted = new ArrayList<>();
                for (Integer audioId : audioIds) if (audioId != null) sorted.add(audioId);
                Collections.sort(sorted);
                this.audioIds = Collections.unmodifiableList(sorted);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SearchKey)) return false;
            SearchKey key = (SearchKey) other;
            return limit == key.limit && question.equals(key.question) && audioIds.equals(key.audioIds);
        }

        @Override
        public int hashCode() {
            int result = question.hashCode();
            result = 31 * result + limit;
            result = 31 * result + audioIds.hashCode();
            return result;
        }
    }
}
