package com.example.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserLlmQuotaServiceTest {
    private JdbcTemplate jdbc;
    private UserLlmQuotaService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new UserLlmQuotaService(jdbc);
        ReflectionTestUtils.setField(service, "defaultRequestsPerMinute", 2);
        ReflectionTestUtils.setField(service, "defaultConcurrentStreams", 1);
        ReflectionTestUtils.setField(service, "defaultDailyTokenLimit", 100);
        ReflectionTestUtils.setField(service, "defaultDailyCostLimit", 1.0d);
        when(jdbc.queryForList(anyString(), anyInt())).thenReturn(Collections.emptyList());
    }

    @Test
    void limitsRequestsIndependentlyPerUser() {
        service.acquireRequest(7, false).close();
        service.acquireRequest(7, false).close();

        UserLlmQuotaService.QuotaExceededException exception = assertThrows(
                UserLlmQuotaService.QuotaExceededException.class,
                () -> service.acquireRequest(7, false));
        assertEquals("REQUEST_RATE_LIMIT", exception.getReason());
        assertDoesNotThrow(() -> service.acquireRequest(8, false).close());
    }

    @Test
    void releasesConcurrentStreamSlotWhenPermitCloses() {
        UserLlmQuotaService.Permit first = service.acquireRequest(11, true);
        UserLlmQuotaService.QuotaExceededException exception = assertThrows(
                UserLlmQuotaService.QuotaExceededException.class,
                () -> service.acquireRequest(11, true));
        assertEquals("CONCURRENT_STREAM_LIMIT", exception.getReason());
        first.close();
        assertDoesNotThrow(() -> service.acquireRequest(11, true).close());
    }

    @Test
    void blocksExternalModelWhenDailyTokenBudgetIsExhausted() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("tokens", 100L);
        usage.put("cost", 0.2d);
        when(jdbc.queryForMap(anyString(), anyInt())).thenReturn(usage);

        UserLlmQuotaService.QuotaExceededException exception = assertThrows(
                UserLlmQuotaService.QuotaExceededException.class,
                () -> service.assertModelBudget(5));
        assertEquals("DAILY_TOKEN_LIMIT", exception.getReason());
    }
}
