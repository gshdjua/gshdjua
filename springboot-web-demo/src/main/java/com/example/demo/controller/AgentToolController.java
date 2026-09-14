package com.example.demo.controller;

import com.example.demo.service.AgentToolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/agent/tools")
public class AgentToolController {

    @Autowired
    private AgentToolService agentToolService;

    @Value("${agent-tool.api-key:}")
    private String apiKey;

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody Map<String, Object> request,
                                       @RequestHeader(value = "X-Agent-Tool-Key", required = false) String suppliedKey) {
        long started = System.nanoTime();
        String requestId = text(request.get("requestId"));
        String traceId = text(request.get("traceId"));
        String tool = text(request.get("tool"));
        if (apiKey != null && !apiKey.trim().isEmpty() && !apiKey.equals(suppliedKey)) {
            return failure(requestId, traceId, tool, "FORBIDDEN", "工具服务认证失败", false, started);
        }
        if (!"1.0".equals(text(request.get("protocolVersion")))) {
            return failure(requestId, traceId, tool, "UNSUPPORTED_PROTOCOL", "不支持的工具协议版本", false, started);
        }
        if (!isKnownTool(tool)) {
            return failure(requestId, traceId, tool, "TOOL_NOT_FOUND", "未注册的工具", false, started);
        }
        Map<String, Object> arguments = asMap(request.get("arguments"));
        try {
            Object data;
            if ("song_search".equals(tool)) {
                String query = requiredText(arguments.get("query"), 200);
                int limit = boundedLimit(arguments.get("limit"), 5);
                data = agentToolService.searchSongs(query, limit);
            } else if ("favorite_search".equals(tool)) {
                Integer userId = requiredUserId(request);
                String query = optionalText(arguments.get("query"), 200);
                int limit = boundedLimit(arguments.get("limit"), 10);
                data = agentToolService.searchFavorites(userId, query, limit);
            } else if ("recommend_songs".equals(tool)) {
                Integer userId = requiredUserId(request);
                String query = requiredText(arguments.get("query"), 500);
                int limit = boundedLimit(arguments.get("limit"), 5);
                List<Integer> excluded = integerList(arguments.get("exclude_audio_ids"), 50);
                data = agentToolService.recommendSongs(userId, query, limit, excluded);
            } else if ("vector_search".equals(tool)) {
                String query = requiredText(arguments.get("query"), 500);
                int limit = boundedLimit(arguments.get("limit"), 5);
                List<Integer> audioIds = integerList(arguments.get("audio_ids"), 100);
                data = agentToolService.vectorSearch(query, limit, audioIds);
            } else {
                Integer audioId = optionalPositiveInteger(arguments.get("audio_id"));
                String query = optionalText(arguments.get("query"), 200);
                if (audioId == null && query.isEmpty()) throw new IllegalArgumentException("缺少歌曲 ID 或查询词");
                data = agentToolService.songDetail(audioId, query);
            }
            return success(requestId, traceId, tool, data, started);
        } catch (IllegalArgumentException exception) {
            return failure(requestId, traceId, tool, "INVALID_ARGUMENTS", exception.getMessage(), false, started);
        } catch (Exception exception) {
            return failure(requestId, traceId, tool, "TOOL_FAILED", "工具执行失败", true, started);
        }
    }

    private boolean isKnownTool(String tool) {
        return "song_search".equals(tool) || "favorite_search".equals(tool)
                || "recommend_songs".equals(tool) || "vector_search".equals(tool)
                || "song_detail".equals(tool);
    }

    private Integer requiredUserId(Map<String, Object> request) {
        Integer userId = optionalPositiveInteger(request.get("userId"));
        if (userId == null) throw new IllegalArgumentException("需要登录用户上下文");
        return userId;
    }

    private String requiredText(Object value, int maxLength) {
        String result = optionalText(value, maxLength);
        if (result.isEmpty()) throw new IllegalArgumentException("缺少必填查询词");
        return result;
    }

    private String optionalText(Object value, int maxLength) {
        String result = text(value).trim();
        if (result.length() > maxLength) throw new IllegalArgumentException("查询词过长");
        return result;
    }

    private int boundedLimit(Object value, int defaultValue) {
        int result = integer(value, defaultValue);
        if (result < 1 || result > 20) throw new IllegalArgumentException("limit 必须在 1 到 20 之间");
        return result;
    }

    private Integer optionalPositiveInteger(Object value) {
        if (value == null) return null;
        int result = integer(value, -1);
        if (result < 1) throw new IllegalArgumentException("歌曲 ID 必须是正整数");
        return result;
    }

    private List<Integer> integerList(Object value, int maxSize) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new IllegalArgumentException("歌曲 ID 列表格式不正确");
        List<?> raw = (List<?>) value;
        if (raw.size() > maxSize) throw new IllegalArgumentException("歌曲 ID 列表过长");
        List<Integer> result = new ArrayList<>();
        for (Object item : raw) {
            Integer id = optionalPositiveInteger(item);
            if (id == null) throw new IllegalArgumentException("歌曲 ID 列表包含空值");
            result.add(id);
        }
        return result;
    }

    private Map<String, Object> success(String requestId, String traceId, String tool,
                                        Object data, long started) {
        Map<String, Object> result = base(requestId, traceId, tool, started);
        result.put("success", true);
        result.put("data", data);
        result.put("error", null);
        return result;
    }

    private Map<String, Object> failure(String requestId, String traceId, String tool,
                                        String code, String message, boolean retryable, long started) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);
        Map<String, Object> result = base(requestId, traceId, tool, started);
        result.put("success", false);
        result.put("data", null);
        result.put("error", error);
        return result;
    }

    private Map<String, Object> base(String requestId, String traceId, String tool, long started) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "1.0");
        result.put("requestId", requestId);
        result.put("traceId", traceId);
        result.put("tool", tool);
        result.put("readOnly", true);
        result.put("durationMs", Math.round((System.nanoTime() - started) / 1_000_000.0));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private int integer(Object value, int defaultValue) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
