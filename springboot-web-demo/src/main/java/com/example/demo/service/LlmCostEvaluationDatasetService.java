package com.example.demo.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmCostEvaluationDatasetService {

    private static final Pattern CASE_ID_PATTERN = Pattern.compile("C(\\d+)", Pattern.CASE_INSENSITIVE);

    @Value("${llm-cost.evaluation.dataset-path:../evaluation/llm-cost-eval.jsonl}")
    private String configuredPath;

    public List<Map<String, Object>> loadCases() {
        File dataset = resolveDataset();
        if (dataset == null) throw new IllegalStateException("LLM cost evaluation dataset not found");
        List<Map<String, Object>> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(dataset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) cases.add(JSON.parseObject(line, new TypeReference<Map<String, Object>>() {}));
            }
            return cases;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load LLM cost evaluation dataset", exception);
        }
    }

    public synchronized Map<String, Object> addCase(Map<String, Object> payload) {
        List<Map<String, Object>> cases = loadCases();
        Map<String, Object> normalized = normalize(payload, nextId(cases));
        ensureUnique(cases, normalized, null);
        cases.add(normalized);
        writeCases(cases);
        return normalized;
    }

    public synchronized Map<String, Object> updateCase(String id, Map<String, Object> payload) {
        List<Map<String, Object>> cases = loadCases();
        int index = findIndex(cases, id);
        if (index < 0) throw new IllegalArgumentException("成本测试题不存在：" + id);
        Map<String, Object> normalized = normalize(payload, String.valueOf(cases.get(index).get("id")));
        ensureUnique(cases, normalized, id);
        cases.set(index, normalized);
        writeCases(cases);
        return normalized;
    }

    public synchronized void deleteCase(String id) {
        List<Map<String, Object>> cases = loadCases();
        int index = findIndex(cases, id);
        if (index < 0) throw new IllegalArgumentException("成本测试题不存在：" + id);
        cases.remove(index);
        writeCases(cases);
    }

    private Map<String, Object> normalize(Map<String, Object> payload, String id) {
        if (payload == null) throw new IllegalArgumentException("测试题内容不能为空");
        String question = text(payload.get("question"));
        if (question.isEmpty()) throw new IllegalArgumentException("问题不能为空");
        if (question.length() > 500) throw new IllegalArgumentException("问题不能超过500个字符");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "2.0");
        result.put("id", id.toUpperCase());
        result.put("category", defaultText(payload.get("category"), "未分类"));
        result.put("question", question);
        result.put("expected_intent", defaultText(payload.get("expected_intent"), "AUTO"));
        result.put("expected_evidence_count", clamp(payload.get("expected_evidence_count"), 3, 0, 12));
        result.put("expected_output_tokens", clamp(payload.get("expected_output_tokens"), 300, 1, 4000));
        result.put("expected_model_call", booleanValue(payload.get("expected_model_call")));
        result.put("expected_strategy", strategy(payload.get("expected_strategy")));
        result.put("execution_target", executionTarget(payload.get("execution_target")));
        result.put("cost_budget", costBudget(payload.get("cost_budget")));
        result.put("expected_tools", expectedTools(payload.get("expected_tools")));
        return result;
    }

    private void ensureUnique(List<Map<String, Object>> cases, Map<String, Object> candidate, String excludedId) {
        for (Map<String, Object> item : cases) {
            if (excludedId != null && excludedId.equalsIgnoreCase(text(item.get("id")))) continue;
            if (text(item.get("question")).equalsIgnoreCase(text(candidate.get("question")))) {
                throw new IllegalArgumentException("已存在相同问题的成本测试题");
            }
        }
    }

    private String nextId(List<Map<String, Object>> cases) {
        int maximum = 0;
        for (Map<String, Object> item : cases) {
            Matcher matcher = CASE_ID_PATTERN.matcher(text(item.get("id")));
            if (matcher.matches()) maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
        }
        return String.format("C%03d", maximum + 1);
    }

    private int findIndex(List<Map<String, Object>> cases, String id) {
        for (int index = 0; index < cases.size(); index++) {
            if (text(cases.get(index).get("id")).equalsIgnoreCase(text(id))) return index;
        }
        return -1;
    }

    private void writeCases(List<Map<String, Object>> cases) {
        File dataset = resolveDataset();
        if (dataset == null) throw new IllegalStateException("LLM cost evaluation dataset not found");
        File temporary = new File(dataset.getParentFile(), dataset.getName() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8))) {
            for (Map<String, Object> item : cases) {
                writer.write(JSON.toJSONString(item));
                writer.newLine();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write LLM cost evaluation dataset", exception);
        }
        try {
            Files.move(temporary.toPath(), dataset.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            temporary.delete();
            throw new IllegalStateException("Failed to replace LLM cost evaluation dataset");
        }
    }

    private File resolveDataset() {
        for (String path : Arrays.asList(configuredPath, "evaluation/llm-cost-eval.jsonl", "../evaluation/llm-cost-eval.jsonl")) {
            File file = new File(path);
            if (file.isFile()) return file;
        }
        return null;
    }

    private int clamp(Object value, int fallback, int minimum, int maximum) {
        try { return Math.max(minimum, Math.min(maximum, Integer.parseInt(text(value)))); }
        catch (NumberFormatException exception) { return fallback; }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean ? (Boolean) value : "true".equalsIgnoreCase(text(value));
    }

    private String defaultText(Object value, String fallback) {
        String result = text(value);
        return result.isEmpty() ? fallback : result;
    }

    private String strategy(Object value) {
        String result = defaultText(value, "AUTO").toLowerCase();
        if (!("auto".equals(result) || "local".equals(result) || "direct".equals(result) || "react".equals(result))) {
            throw new IllegalArgumentException("期望策略必须是 AUTO、local、direct 或 react");
        }
        return "auto".equals(result) ? "AUTO" : result;
    }

    private String executionTarget(Object value) {
        String result = defaultText(value, "production").toLowerCase();
        if (!("production".equals(result) || "agent_native".equals(result))) {
            throw new IllegalArgumentException("执行目标必须是 production 或 agent_native");
        }
        return result;
    }

    private String costBudget(Object value) {
        String result = defaultText(value, "standard").toLowerCase();
        if (!("low".equals(result) || "standard".equals(result) || "high".equals(result))) {
            throw new IllegalArgumentException("成本预算必须是 low、standard 或 high");
        }
        return result;
    }

    private List<String> expectedTools(Object value) {
        List<String> result = new ArrayList<>();
        if (!(value instanceof List)) return result;
        List<String> allowed = Arrays.asList(
                "song_search", "favorite_search", "recommend_songs", "vector_search", "song_detail");
        for (Object item : (List<?>) value) {
            String tool = text(item);
            if (tool.isEmpty() || result.contains(tool)) continue;
            if (!allowed.contains(tool)) throw new IllegalArgumentException("未知期望工具：" + tool);
            result.add(tool);
        }
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
