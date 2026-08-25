package com.example.demo.service.retrieval;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationDatasetServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void supportsAddingUpdatingAndDeletingCases() throws Exception {
        Path dataset = temporaryDirectory.resolve("cases.jsonl");
        Files.write(dataset, Arrays.asList(
                "{\"id\":\"T001\",\"category\":\"基础\",\"question\":\"问题一\",\"expected_audio_ids\":[1]}"
        ), StandardCharsets.UTF_8);
        EvaluationDatasetService service = new EvaluationDatasetService();
        ReflectionTestUtils.setField(service, "configuredPath", dataset.toString());

        Map<String, Object> added = service.addCase(casePayload("新问题", Arrays.asList(2, 3)));
        assertEquals("T002", added.get("id"));
        assertEquals(2, service.loadCases().size());

        Map<String, Object> update = casePayload("修改后的问题", Arrays.asList(3));
        service.updateCase("T002", update);
        assertEquals("修改后的问题", service.loadCases().get(1).get("question"));

        service.deleteCase("T001");
        assertEquals(1, service.loadCases().size());
        assertEquals("T002", service.loadCases().get(0).get("id"));
    }

    @Test
    void rejectsDuplicateQuestions() throws Exception {
        Path dataset = temporaryDirectory.resolve("cases.jsonl");
        Files.write(dataset, Arrays.asList(
                JSON.toJSONString(casePayloadWithId("T001", "相同问题"))
        ), StandardCharsets.UTF_8);
        EvaluationDatasetService service = new EvaluationDatasetService();
        ReflectionTestUtils.setField(service, "configuredPath", dataset.toString());

        assertThrows(IllegalArgumentException.class,
                () -> service.addCase(casePayload("相同问题", Arrays.asList(1))));
    }

    private Map<String, Object> casePayload(String question, Object expectedIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", "测试分类");
        payload.put("question", question);
        payload.put("expected_answer", "标准回答");
        payload.put("must_include", Arrays.asList("关键词"));
        payload.put("must_not_include", Arrays.asList("错误词"));
        payload.put("expected_audio_ids", expectedIds);
        return payload;
    }

    private Map<String, Object> casePayloadWithId(String id, String question) {
        Map<String, Object> payload = casePayload(question, Arrays.asList(1));
        payload.put("id", id);
        return payload;
    }
}
