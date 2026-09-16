package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmCostEvaluationDatasetServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesVersionTwoStrategyExpectation() throws Exception {
        Path dataset = temporaryDirectory.resolve("cost.jsonl");
        Files.write(dataset, new byte[0]);
        LlmCostEvaluationDatasetService service = new LlmCostEvaluationDatasetService();
        ReflectionTestUtils.setField(service, "configuredPath", dataset.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", "结合收藏比较两首歌");
        payload.put("expected_strategy", "react");
        payload.put("expected_model_call", true);

        Map<String, Object> saved = service.addCase(payload);

        assertEquals("2.0", saved.get("schema_version"));
        assertEquals("react", saved.get("expected_strategy"));
        assertEquals("react", service.loadCases().get(0).get("expected_strategy"));
    }

    @Test
    void rejectsUnknownExpectedStrategy() throws Exception {
        Path dataset = temporaryDirectory.resolve("invalid.jsonl");
        Files.write(dataset, new byte[0]);
        LlmCostEvaluationDatasetService service = new LlmCostEvaluationDatasetService();
        ReflectionTestUtils.setField(service, "configuredPath", dataset.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", "测试问题");
        payload.put("expected_strategy", "tree");

        assertThrows(IllegalArgumentException.class, () -> service.addCase(payload));
    }

    @Test
    void storesAgentNativeTargetBudgetAndExpectedTools() throws Exception {
        Path dataset = temporaryDirectory.resolve("native.jsonl");
        Files.write(dataset, new byte[0]);
        LlmCostEvaluationDatasetService service = new LlmCostEvaluationDatasetService();
        ReflectionTestUtils.setField(service, "configuredPath", dataset.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", "先查收藏再推荐歌曲");
        payload.put("execution_target", "agent_native");
        payload.put("cost_budget", "high");
        payload.put("expected_tools", Arrays.asList("favorite_search", "recommend_songs"));

        Map<String, Object> saved = service.addCase(payload);

        assertEquals("agent_native", saved.get("execution_target"));
        assertEquals("high", saved.get("cost_budget"));
        assertEquals(Arrays.asList("favorite_search", "recommend_songs"), saved.get("expected_tools"));
    }

    @Test
    void rejectsUnknownAgentNativeTool() throws Exception {
        Path dataset = temporaryDirectory.resolve("invalid-tool.jsonl");
        Files.write(dataset, new byte[0]);
        LlmCostEvaluationDatasetService service = new LlmCostEvaluationDatasetService();
        ReflectionTestUtils.setField(service, "configuredPath", dataset.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", "测试问题");
        payload.put("expected_tools", Arrays.asList("delete_song"));

        assertThrows(IllegalArgumentException.class, () -> service.addCase(payload));
    }
}
