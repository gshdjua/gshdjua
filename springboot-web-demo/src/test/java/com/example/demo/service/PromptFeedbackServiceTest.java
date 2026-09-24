package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptFeedbackServiceTest {

    @Test
    void savesOnlyOwnedVersionedPromptAnswerAndClearsReasonForHelpfulRating() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PromptFeedbackService service = new PromptFeedbackService(jdbc);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L), eq(7)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("prompt_version")).thenReturn("music_answer:v3");
                    return Collections.singletonList(mapper.mapRow(row, 0));
                });

        Map<String, Object> result = service.save(42L, 7, "helpful", "inaccurate");

        assertEquals("music_answer:v3", result.get("promptVersion"));
        assertEquals("helpful", result.get("rating"));
        assertEquals(null, result.get("reason"));
        verify(jdbc).update(anyString(), eq(42L), eq("music_answer:v3"), eq("helpful"), eq(null));
    }

    @Test
    void rejectsInvalidRatingBeforeReadingAnyMessage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PromptFeedbackService service = new PromptFeedbackService(jdbc);

        assertThrows(IllegalArgumentException.class, () -> service.save(42L, 7, "maybe", null));

        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(), any());
    }

    @Test
    void aggregatesVoluntaryFeedbackByPromptVersion() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PromptFeedbackService service = new PromptFeedbackService(jdbc);
        ReflectionTestUtils.setField(service, "minimumSampleSize", 3);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return Arrays.asList(
                            mapper.mapRow(feedbackRow("music_answer:v1", "helpful", null), 0),
                            mapper.mapRow(feedbackRow("music_answer:v1", "helpful", null), 1),
                            mapper.mapRow(feedbackRow("music_answer:v1", "not_helpful", "inaccurate"), 2));
                });

        Map<String, Object> summary = service.summary(7);
        List<?> versions = (List<?>) summary.get("versions");
        Map<?, ?> version = (Map<?, ?>) versions.get(0);

        assertEquals(3, version.get("feedbackCount"));
        assertEquals(2, version.get("helpfulCount"));
        assertEquals(1, version.get("notHelpfulCount"));
        assertEquals(2d / 3d, (Double) version.get("helpfulRate"), 0.00001d);
        assertEquals(1, ((Map<?, ?>) version.get("reasonCounts")).get("inaccurate"));
        assertTrue((Boolean) version.get("sampleSufficient"));
    }

    private ResultSet feedbackRow(String version, String rating, String reason) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("prompt_version")).thenReturn(version);
        when(row.getString("rating")).thenReturn(rating);
        when(row.getString("reason")).thenReturn(reason);
        return row;
    }
}
