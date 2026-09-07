package com.example.demo.controller;

import com.example.demo.service.DeepSeekMusicAgent;
import com.example.demo.service.LlmCostEvaluationDatasetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/llm-cost-evaluation")
public class LlmCostEvaluationController {

    @Autowired
    private LlmCostEvaluationDatasetService datasetService;

    @Autowired
    private DeepSeekMusicAgent musicAgent;

    @GetMapping("/cases")
    public Map<String, Object> cases() {
        try { return response(200, "success", datasetService.loadCases()); }
        catch (IllegalStateException exception) { return response(500, exception.getMessage(), null); }
    }

    @PostMapping("/cases")
    public Map<String, Object> addCase(@RequestBody Map<String, Object> payload) {
        try { return response(200, "成本测试题已新增", datasetService.addCase(payload)); }
        catch (IllegalArgumentException exception) { return response(400, exception.getMessage(), null); }
        catch (IllegalStateException exception) { return response(500, exception.getMessage(), null); }
    }

    @PutMapping("/cases/{id}")
    public Map<String, Object> updateCase(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        try { return response(200, "成本测试题已更新", datasetService.updateCase(id, payload)); }
        catch (IllegalArgumentException exception) { return response(400, exception.getMessage(), null); }
        catch (IllegalStateException exception) { return response(500, exception.getMessage(), null); }
    }

    @DeleteMapping("/cases/{id}")
    public Map<String, Object> deleteCase(@PathVariable String id) {
        try {
            datasetService.deleteCase(id);
            return response(200, "成本测试题已删除", null);
        } catch (IllegalArgumentException exception) { return response(400, exception.getMessage(), null); }
        catch (IllegalStateException exception) { return response(500, exception.getMessage(), null); }
    }

    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestBody Map<String, Object> payload) {
        String question = text(payload.get("question"));
        if (question.isEmpty()) return response(400, "问题不能为空", null);
        boolean realCall = booleanValue(payload.get("realCall"));
        int expectedOutputTokens = integer(payload.get("expectedOutputTokens"), 300);
        double inputPrice = decimal(payload.get("inputPricePerMillion"), 0d);
        double outputPrice = decimal(payload.get("outputPricePerMillion"), 0d);
        Integer userId = nullableInteger(payload.get("userId"));
        try {
            return response(200, "success", musicAgent.evaluateLlmCost(question, userId,
                    history(payload.get("history")), realCall, expectedOutputTokens, inputPrice, outputPrice));
        } catch (Exception exception) {
            return response(500, "成本评测失败：" + exception.getMessage(), null);
        }
    }

    private List<Map<String, String>> history(Object value) {
        List<Map<String, String>> result = new ArrayList<>();
        if (!(value instanceof List)) return result;
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> raw = (Map<?, ?>) item;
            String role = text(raw.get("role"));
            String content = text(raw.get("content"));
            if (!("user".equals(role) || "assistant".equals(role)) || content.isEmpty()) continue;
            Map<String, String> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", content);
            result.add(message);
        }
        return result;
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("msg", message);
        result.put("data", data);
        return result;
    }

    private boolean booleanValue(Object value) { return value instanceof Boolean ? (Boolean) value : "true".equalsIgnoreCase(text(value)); }
    private int integer(Object value, int fallback) { try { return Integer.parseInt(text(value)); } catch (Exception ignored) { return fallback; } }
    private Integer nullableInteger(Object value) { try { return Integer.valueOf(text(value)); } catch (Exception ignored) { return null; } }
    private double decimal(Object value, double fallback) { try { return Double.parseDouble(text(value)); } catch (Exception ignored) { return fallback; } }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
