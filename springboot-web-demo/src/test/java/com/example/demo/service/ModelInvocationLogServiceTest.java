package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelInvocationLogServiceTest {

    @Test
    void recordsPrivacySafeCombinedChainAndPricesEachAttemptSeparately() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelInvocationLogService service = new ModelInvocationLogService(jdbc);
        LlmModelCatalogService.ModelConfig requestedModel = new LlmModelCatalogService.ModelConfig(
                "qwen-plus", "qwen", "qwen-plus", "Qwen Plus", false, 3, 9);
        LlmModelCatalogService.ModelConfig fallbackModel = new LlmModelCatalogService.ModelConfig(
                "deepseek-chat", "deepseek", "deepseek-chat", "DeepSeek Chat", true, 1, 2);
        DeepSeekMusicAgent.ReplyResult requested = new DeepSeekMusicAgent.ReplyResult(
                "不会被日志保存", null, "music_answer:v1", 1, false, 1000, 100, 120,
                "local", "local", "qwen", "qwen-plus", "local_fallback", "RATE_LIMITED");
        DeepSeekMusicAgent.ReplyResult fallback = new DeepSeekMusicAgent.ReplyResult(
                "也不会被日志保存", null, "music_answer:v1", 1, true, 2000, 200, 80,
                "deepseek", "deepseek-chat", "deepseek", "deepseek-chat", "model", "");
        DeepSeekMusicAgent.ReplyResult result = DeepSeekMusicAgent.ReplyResult.afterFailover(requested, fallback);

        service.record("trace-1", "trace-1-fallback", requestedModel, requested, fallbackModel, fallback, result);

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(startsWith("INSERT INTO llm_invocation_log"),
                values.capture(), values.capture(), values.capture(), values.capture(),
                values.capture(), values.capture(), values.capture(), values.capture(),
                values.capture(), values.capture(), values.capture(), values.capture(),
                values.capture(), values.capture(), values.capture(), values.capture(),
                values.capture());
        List<Object> args = values.getAllValues();
        assertEquals(17, args.size());
        assertEquals("trace-1", args.get(0));
        assertEquals("qwen", args.get(3));
        assertEquals("deepseek", args.get(5));
        assertEquals("model_fallback", args.get(7));
        assertEquals("RATE_LIMITED", args.get(8));
        assertEquals(0.0063d, (Double) args.get(15), 0.0000001d);
        assertEquals("[]", args.get(16));
    }

    @Test
    void rejectsUnknownStatusFilterBeforeQuerying() {
        ModelInvocationLogService service = new ModelInvocationLogService(mock(JdbcTemplate.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(7, "", "", "unexpected", 100));
    }

    @Test
    void recordsCompletedStreamPerformanceAgainstTrace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE llm_invocation_log SET first_token_ms="),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        ModelInvocationLogService service = new ModelInvocationLogService(jdbc);

        service.finishStream("trace-stream", "qwen", "qwen-plus", 320, 1450, 12, 96,
                1, 800, 60, 0.00294d, "completed");

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(startsWith("UPDATE llm_invocation_log SET first_token_ms="),
                values.capture(), values.capture(), values.capture(), values.capture(),
                values.capture(), values.capture(), values.capture());
        assertEquals(320L, values.getAllValues().get(0));
        assertEquals(12, values.getAllValues().get(1));
        assertEquals(96, values.getAllValues().get(2));
        assertEquals("completed", values.getAllValues().get(3));
        assertEquals(0, values.getAllValues().get(4));
        assertEquals(1450L, values.getAllValues().get(5));
        assertEquals("trace-stream", values.getAllValues().get(6));
    }

    @Test
    void storesInterruptedUsageAsAnExplicitEstimate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelInvocationLogService service = new ModelInvocationLogService(jdbc);

        service.finishStream("trace-cancel", "deepseek", "deepseek-chat", 480, 2100, 8, 120,
                1, 720, 72, 0.002808d, "cancelled");

        String insertSql = mockingDetails(jdbc).getInvocations().stream()
                .map(invocation -> {
                    Object sql = invocation.getArgument(0);
                    return String.valueOf(sql);
                })
                .filter(sql -> sql.startsWith("INSERT INTO llm_invocation_log"))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(18, insertSql.chars().filter(character -> character == '?').count());
        verify(jdbc).update(startsWith("INSERT INTO llm_invocation_log"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
