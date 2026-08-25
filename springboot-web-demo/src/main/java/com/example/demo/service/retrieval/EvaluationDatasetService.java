package com.example.demo.service.retrieval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvaluationDatasetService {

    private static final Pattern CASE_ID_PATTERN = Pattern.compile("T(\\d+)", Pattern.CASE_INSENSITIVE);

    @Value("${evaluation.dataset-path:../evaluation/rag-eval-50.jsonl}")
    private String configuredPath;

    public List<Map<String, Object>> loadCases() {
        File dataset = resolveDataset();
        if (dataset == null) throw new IllegalStateException("Evaluation dataset not found");
        List<Map<String, Object>> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(dataset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    cases.add(JSON.parseObject(line, new TypeReference<Map<String, Object>>() {}));
                }
            }
            return cases;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load evaluation dataset", exception);
        }
    }

    public synchronized Map<String, Object> addCase(Map<String, Object> payload) {
        List<Map<String, Object>> cases = loadCases();
        Map<String, Object> normalized = normalizeCase(payload, nextCaseId(cases));
        ensureQuestionUnique(cases, normalized, null);
        cases.add(normalized);
        writeCases(cases);
        return normalized;
    }

    public synchronized Map<String, Object> updateCase(String id, Map<String, Object> payload) {
        List<Map<String, Object>> cases = loadCases();
        int index = findCaseIndex(cases, id);
        if (index < 0) throw new IllegalArgumentException("测试题不存在：" + id);
        Map<String, Object> normalized = normalizeCase(payload, String.valueOf(cases.get(index).get("id")));
        ensureQuestionUnique(cases, normalized, id);
        cases.set(index, normalized);
        writeCases(cases);
        return normalized;
    }

    public synchronized void deleteCase(String id) {
        List<Map<String, Object>> cases = loadCases();
        int index = findCaseIndex(cases, id);
        if (index < 0) throw new IllegalArgumentException("测试题不存在：" + id);
        cases.remove(index);
        writeCases(cases);
    }

    private Map<String, Object> normalizeCase(Map<String, Object> payload, String id) {
        if (payload == null) throw new IllegalArgumentException("测试题内容不能为空");
        String question = stringValue(payload.get("question"));
        if (question.isEmpty()) throw new IllegalArgumentException("问题不能为空");
        if (question.length() > 500) throw new IllegalArgumentException("问题不能超过500个字符");

        String category = stringValue(payload.get("category"));
        if (category.isEmpty()) category = "未分类";
        if (category.length() > 50) throw new IllegalArgumentException("类别不能超过50个字符");

        boolean negative = booleanValue(payload.get("negative")) || "否定事实".equals(category);
        String evaluationMode = stringValue(payload.get("evaluation_mode"));
        if (!("collection_ranking".equals(evaluationMode))) evaluationMode = "standard";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id.toUpperCase());
        result.put("category", negative ? "否定事实" : category);
        result.put("question", question);
        result.put("expected_answer", stringValue(payload.get("expected_answer")));
        result.put("must_include", stringList(payload.get("must_include")));
        result.put("must_not_include", stringList(payload.get("must_not_include")));
        result.put("expected_audio_ids", negative ? Collections.emptyList() : integerList(payload.get("expected_audio_ids")));
        if ("collection_ranking".equals(evaluationMode)) {
            result.put("evaluation_mode", evaluationMode);
            result.put("expected_result_count", clampInteger(payload.get("expected_result_count"), 3, 1, 20));
        }
        List<Map<String, String>> history = historyList(payload.get("history"));
        if (!history.isEmpty()) result.put("history", history);
        return result;
    }

    private void ensureQuestionUnique(List<Map<String, Object>> cases, Map<String, Object> candidate, String excludedId) {
        String question = stringValue(candidate.get("question"));
        for (Map<String, Object> item : cases) {
            if (excludedId != null && excludedId.equalsIgnoreCase(stringValue(item.get("id")))) continue;
            if (question.equalsIgnoreCase(stringValue(item.get("question")))) {
                throw new IllegalArgumentException("已存在相同问题的测试题");
            }
        }
    }

    private String nextCaseId(List<Map<String, Object>> cases) {
        int maximum = 0;
        for (Map<String, Object> item : cases) {
            Matcher matcher = CASE_ID_PATTERN.matcher(stringValue(item.get("id")));
            if (matcher.matches()) maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
        }
        return String.format("T%03d", maximum + 1);
    }

    private int findCaseIndex(List<Map<String, Object>> cases, String id) {
        for (int index = 0; index < cases.size(); index++) {
            if (stringValue(cases.get(index).get("id")).equalsIgnoreCase(stringValue(id))) return index;
        }
        return -1;
    }

    private void writeCases(List<Map<String, Object>> cases) {
        File dataset = resolveDataset();
        if (dataset == null) throw new IllegalStateException("Evaluation dataset not found");
        File temporary = new File(dataset.getParentFile(), dataset.getName() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8))) {
            for (Map<String, Object> item : cases) {
                writer.write(JSON.toJSONString(item));
                writer.newLine();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write evaluation dataset", exception);
        }
        try {
            Files.move(temporary.toPath(), dataset.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            temporary.delete();
            throw new IllegalStateException("Failed to replace evaluation dataset");
        }
    }

    private List<String> stringList(Object value) {
        Set<String> values = new LinkedHashSet<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) addString(values, item);
        } else {
            for (String item : stringValue(value).split("[,，\\n]")) addString(values, item);
        }
        return new ArrayList<>(values);
    }

    private void addString(Set<String> values, Object value) {
        String text = stringValue(value);
        if (!text.isEmpty()) values.add(text);
    }

    private List<Integer> integerList(Object value) {
        Set<Integer> values = new LinkedHashSet<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) addInteger(values, item);
        } else {
            for (String item : stringValue(value).split("[,，\\s]+")) addInteger(values, item);
        }
        return new ArrayList<>(values);
    }

    private void addInteger(Set<Integer> values, Object value) {
        try {
            int parsed = Integer.parseInt(stringValue(value));
            if (parsed > 0) values.add(parsed);
        } catch (NumberFormatException ignored) {
        }
    }

    private List<Map<String, String>> historyList(Object value) {
        List<Map<String, String>> history = new ArrayList<>();
        if (!(value instanceof List)) return history;
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> raw = (Map<?, ?>) item;
            String role = stringValue(raw.get("role"));
            String content = stringValue(raw.get("content"));
            if (!("user".equals(role) || "assistant".equals(role)) || content.isEmpty()) continue;
            Map<String, String> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", content);
            history.add(message);
        }
        return history;
    }

    private int clampInteger(Object value, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(stringValue(value))));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean ? (Boolean) value : "true".equalsIgnoreCase(stringValue(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private File resolveDataset() {
        for (String path : Arrays.asList(configuredPath, "evaluation/rag-eval-50.jsonl",
                "../evaluation/rag-eval-50.jsonl")) {
            File file = new File(path);
            if (file.isFile()) return file;
        }
        return null;
    }
}
