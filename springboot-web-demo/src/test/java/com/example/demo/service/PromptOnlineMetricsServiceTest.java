package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptOnlineMetricsServiceTest {

    @Test
    void recordsOnlyVersionedProductionPromptWithoutContent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PromptOnlineMetricsService service = new PromptOnlineMetricsService(jdbc);
        service.record("none", 0, true, 0, 0, 3);
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());

        service.record("music_answer:v3", 1, true, 120, 40, 800);
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.eq("music_answer:v3"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(120), org.mockito.ArgumentMatchers.eq(40),
                org.mockito.ArgumentMatchers.eq(800L));
    }

    @Test
    void aggregatesTokensLatencyErrorsAndSampleReadiness() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PromptOnlineMetricsService service = new PromptOnlineMetricsService(jdbc);
        ReflectionTestUtils.setField(service, "inputPricePerMillion", 3d);
        ReflectionTestUtils.setField(service, "outputPricePerMillion", 9d);
        ReflectionTestUtils.setField(service, "minimumSampleSize", 2);
        when(jdbc.query(anyString(), any(RowMapper.class), any(java.sql.Timestamp.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return Arrays.asList(mapper.mapRow(row("music_answer:v1", 2, true, 100, 20, 100), 0),
                            mapper.mapRow(row("music_answer:v1", 1, false, 50, 10, 300), 1));
                });

        Map<String, Object> summary = service.summary(7);
        Map<?, ?> version = (Map<?, ?>) ((java.util.List<?>) summary.get("versions")).get(0);
        assertEquals(2, version.get("requestCount"));
        assertEquals(3, version.get("modelCalls"));
        assertEquals(150L, version.get("inputTokens"));
        assertEquals(30L, version.get("outputTokens"));
        assertEquals(0.5d, (Double) version.get("errorRate"), 0.00001d);
        assertEquals(200d, (Double) version.get("averageLatencyMs"), 0.00001d);
        assertEquals(300, version.get("p95LatencyMs"));
        assertTrue((Boolean) version.get("sampleSufficient"));
        assertFalse(String.valueOf(summary.get("privacy")).contains("问题正文"));
    }

    private ResultSet row(String version, int calls, boolean success,
                          int input, int output, int latency) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("prompt_version")).thenReturn(version);
        when(row.getInt("model_calls")).thenReturn(calls);
        when(row.getBoolean("success")).thenReturn(success);
        when(row.getInt("input_tokens")).thenReturn(input);
        when(row.getInt("output_tokens")).thenReturn(output);
        when(row.getInt("latency_ms")).thenReturn(latency);
        return row;
    }
}
