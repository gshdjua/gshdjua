package com.example.demo.controller;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.HybridMusicRetriever;
import com.example.demo.service.retrieval.EvaluationDatasetService;
import com.example.demo.service.retrieval.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/evaluation")
public class RetrievalEvaluationController {

    private static final Pattern ARABIC_SONG_COUNT = Pattern.compile("(\\d+)\\s*首");
    private static final Pattern CHINESE_SONG_COUNT = Pattern.compile("([一二两三四五六七八九十]+)\\s*首");

    @Autowired
    private HybridMusicRetriever hybridMusicRetriever;

    @Autowired
    private EvaluationDatasetService evaluationDatasetService;

    @Autowired
    private AudioMapper audioMapper;

    @GetMapping("/cases")
    public Map<String, Object> cases() {
        try {
            return response(200, "success", evaluationDatasetService.loadCases());
        } catch (IllegalStateException exception) {
            return response(500, exception.getMessage(), null);
        }
    }

    @PostMapping("/cases")
    public Map<String, Object> addCase(@RequestBody Map<String, Object> payload) {
        try {
            return response(200, "测试题已新增", evaluationDatasetService.addCase(payload));
        } catch (IllegalArgumentException exception) {
            return response(400, exception.getMessage(), null);
        } catch (IllegalStateException exception) {
            return response(500, exception.getMessage(), null);
        }
    }

    @PutMapping("/cases/{id}")
    public Map<String, Object> updateCase(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        try {
            return response(200, "测试题已更新", evaluationDatasetService.updateCase(id, payload));
        } catch (IllegalArgumentException exception) {
            return response(400, exception.getMessage(), null);
        } catch (IllegalStateException exception) {
            return response(500, exception.getMessage(), null);
        }
    }

    @DeleteMapping("/cases/{id}")
    public Map<String, Object> deleteCase(@PathVariable String id) {
        try {
            evaluationDatasetService.deleteCase(id);
            return response(200, "测试题已删除", null);
        } catch (IllegalArgumentException exception) {
            return response(400, exception.getMessage(), null);
        } catch (IllegalStateException exception) {
            return response(500, exception.getMessage(), null);
        }
    }

    @PostMapping("/retrieve")
    public Map<String, Object> retrieve(@RequestBody Map<String, Object> payload) {
        String question = String.valueOf(payload.getOrDefault("question", "")).trim();
        if (question.isEmpty()) return response(500, "Question cannot be empty", null);
        int topK = Math.max(1, Math.min(20, toInteger(payload.get("topK"), 5)));
        List<Map<String, String>> history = toHistory(payload.get("history"));
        if (isCollectionRankingQuestion(question)) {
            return popularityRankingResponse(question, topK);
        }
        List<RetrievalResult> results = hybridMusicRetriever.retrieve(question, history, topK);

        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            RetrievalResult result = results.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            item.put("audioId", result.getAudioId());
            item.put("citationId", result.getCitationId());
            item.put("sources", result.getSources());
            item.put("sourceScores", result.getSourceScores());
            item.put("fusionContributions", result.getFusionContributions());
            item.put("fusionProfile", result.getFusionProfile());
            item.put("fusionScore", result.getFusionScore());
            item.put("metadataScore", result.getMetadataScore());
            item.put("corroborationScore", result.getCorroborationScore());
            item.put("rerankScore", result.getRerankScore());
            item.put("rerankReasons", result.getRerankReasons());
            item.put("confidenceScore", result.getConfidenceScore());
            item.put("confidenceThreshold", result.getConfidenceThreshold());
            item.put("evidence", result.getEvidence());
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("topK", topK);
        data.put("results", items);
        return response(200, "success", data);
    }

    private Map<String, Object> popularityRankingResponse(String question, int topK) {
        int requestedCount = extractRequestedCount(question, topK);
        List<Audio> rankedSongs = audioMapper.selectRecommended();
        int expectedResultCount = Math.min(requestedCount, rankedSongs.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < expectedResultCount; index++) {
            Audio song = rankedSongs.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            item.put("audioId", song.getId());
            item.put("citationId", "S" + (index + 1));
            item.put("sources", java.util.Collections.singletonList("SQL_EXACT"));
            item.put("fusionProfile", "BUSINESS_COLLECTION_RANKING");
            item.put("collectCount", song.getCollectCount() == null ? 0 : song.getCollectCount());
            item.put("uploadTime", song.getUploadTime());
            item.put("evidence", "歌名：" + song.getSongName() + "；歌手：" + song.getSinger()
                    + "；收藏数：" + (song.getCollectCount() == null ? 0 : song.getCollectCount()));
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("topK", topK);
        data.put("requestedCount", requestedCount);
        data.put("expectedResultCount", expectedResultCount);
        data.put("evaluationMode", "collection_ranking");
        data.put("results", items);
        return response(200, "success", data);
    }

    private boolean isCollectionRankingQuestion(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        return normalized.contains("收藏")
                && (normalized.contains("从高到低") || normalized.contains("排序")
                || normalized.contains("最高") || normalized.contains("最多"));
    }

    private int extractRequestedCount(String question, int fallback) {
        Matcher arabicMatcher = ARABIC_SONG_COUNT.matcher(question);
        if (arabicMatcher.find()) return clampCount(Integer.parseInt(arabicMatcher.group(1)));
        Matcher chineseMatcher = CHINESE_SONG_COUNT.matcher(question);
        if (chineseMatcher.find()) return clampCount(parseChineseNumber(chineseMatcher.group(1)));
        return clampCount(fallback);
    }

    private int parseChineseNumber(String value) {
        if ("十".equals(value)) return 10;
        int tenIndex = value.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : chineseDigit(value.charAt(0));
            int units = tenIndex == value.length() - 1 ? 0 : chineseDigit(value.charAt(tenIndex + 1));
            return tens * 10 + units;
        }
        return value.isEmpty() ? 0 : chineseDigit(value.charAt(0));
    }

    private int chineseDigit(char value) {
        switch (value) {
            case '一': return 1;
            case '二':
            case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return 0;
        }
    }

    private int clampCount(int count) {
        return Math.max(1, Math.min(20, count));
    }

    private List<Map<String, String>> toHistory(Object value) {
        List<Map<String, String>> history = new ArrayList<>();
        if (!(value instanceof List)) return history;
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> source = (Map<?, ?>) item;
            String role = String.valueOf(source.get("role"));
            String content = String.valueOf(source.get("content"));
            if (!("user".equals(role) || "assistant".equals(role)) || content.trim().isEmpty()) continue;
            Map<String, String> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", content);
            history.add(message);
        }
        return history;
    }

    private int toInteger(Object value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("msg", message);
        response.put("data", data);
        return response;
    }
}
