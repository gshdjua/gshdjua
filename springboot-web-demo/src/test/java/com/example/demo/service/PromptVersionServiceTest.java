package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptVersionServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PromptVersionService service = new PromptVersionService(jdbc);

    @Test
    void readsPublishedPromptForProductionAnswer() {
        PromptVersionService.SelectedPrompt version = new PromptVersionService.SelectedPrompt("已发布的回答规则", "music_answer:v2");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PromptVersionService.ANSWER_PROMPT)))
                .thenReturn(Collections.singletonList(version));

        assertEquals("已发布的回答规则", service.currentAnswerPrompt().getTemplate());
        assertEquals("music_answer:v2", service.currentAnswerPrompt().getVersion());
    }

    @Test
    void fallsBackWhenPromptStoreIsUnavailableOrDisabled() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PromptVersionService.ANSWER_PROMPT)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals("music_answer:fallback", service.currentAnswerPrompt().getVersion());

        when(jdbc.query(anyString(), any(RowMapper.class), eq(PromptVersionService.ANSWER_PROMPT)))
                .thenReturn(Collections.emptyList());
        assertEquals(PromptVersionService.FALLBACK_TEMPLATE, service.currentAnswerPrompt().getTemplate());
    }

    @Test
    void stableBucketDoesNotChangeForSameUserAndCanSelectGrayCandidate() {
        assertEquals(service.stableBucket(123), service.stableBucket(123));
        assertTrue(service.stableBucket(123) >= 0 && service.stableBucket(123) < 100);
        when(jdbc.query(argThat(sql -> sql != null && sql.contains("status='published'")), any(RowMapper.class),
                eq(PromptVersionService.ANSWER_PROMPT)))
                .thenReturn(Collections.singletonList(new PromptVersionService.SelectedPrompt("基线规则", "music_answer:v1")));
        when(jdbc.query(argThat(sql -> sql != null && sql.contains("prompt_rollout")), any(RowMapper.class),
                eq(PromptVersionService.ANSWER_PROMPT)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("template_text")).thenReturn("候选规则");
                    when(row.getInt("version")).thenReturn(2);
                    when(row.getInt("traffic_percent")).thenReturn(100);
                    return Collections.singletonList(mapper.mapRow(row, 0));
                });
        assertEquals("music_answer:v2", service.currentAnswerPrompt(123).getVersion());
        assertEquals("music_answer:v2", service.currentAnswerPrompt(123).getVersion());
    }

    @Test
    void missingRolloutTableKeepsPublishedBaseline() {
        when(jdbc.query(argThat(sql -> sql != null && sql.contains("status='published'")), any(RowMapper.class),
                eq(PromptVersionService.ANSWER_PROMPT)))
                .thenReturn(Collections.singletonList(new PromptVersionService.SelectedPrompt("基线规则", "music_answer:v1")));
        when(jdbc.query(argThat(sql -> sql != null && sql.contains("prompt_rollout")), any(RowMapper.class),
                eq(PromptVersionService.ANSWER_PROMPT)))
                .thenThrow(new DataAccessResourceFailureException("rollout unavailable"));
        assertEquals("music_answer:v1", service.currentAnswerPrompt(123).getVersion());
    }

    @Test
    void startRolloutRequiresBaselineAndValidPercent() {
        when(jdbc.queryForList(argThat(sql -> sql.contains("AND id=?")),
                eq(PromptVersionService.ANSWER_PROMPT), eq(7L)))
                .thenReturn(Collections.singletonList(version(7L, 2, "draft")));
        assertThrows(IllegalArgumentException.class, () -> service.startRollout(7L, 0));
        assertThrows(IllegalArgumentException.class, () -> service.startRollout(7L, 10));
        when(jdbc.queryForList("SELECT id FROM prompt_version WHERE name=? AND status='published'",
                PromptVersionService.ANSWER_PROMPT))
                .thenReturn(Collections.singletonList(Collections.singletonMap("id", 1L)));
        when(jdbc.queryForList(argThat(sql -> sql.contains("FROM prompt_rollout pr LEFT")),
                eq(PromptVersionService.ANSWER_PROMPT)))
                .thenReturn(Collections.singletonList(Collections.singletonMap("enabled", 1)));
        assertEquals(1, service.startRollout(7L, 10).get("enabled"));
        verify(jdbc).update("UPDATE prompt_version SET status='gray' WHERE id=? AND name=? AND status IN ('draft','inactive')",
                7L, PromptVersionService.ANSWER_PROMPT);
    }

    @Test
    void activeRolloutBlocksBaselinePublish() {
        when(jdbc.queryForList("SELECT enabled FROM prompt_rollout WHERE name=? AND enabled=1",
                PromptVersionService.ANSWER_PROMPT))
                .thenReturn(Collections.singletonList(Collections.singletonMap("enabled", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.publish(7L));
        assertThrows(IllegalArgumentException.class, () -> service.disable(7L));
    }

    @Test
    void evaluationCanSelectDraftWithoutPublishingIt() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PromptVersionService.ANSWER_PROMPT), eq(2)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("template_text")).thenReturn("候选草稿规则");
                    when(row.getInt("version")).thenReturn(2);
                    return Collections.singletonList(mapper.mapRow(row, 0));
                });
        assertEquals("music_answer:v2", service.forEvaluation(2).getVersion());
        assertThrows(IllegalArgumentException.class, () -> service.forEvaluation(3));
    }

    @Test
    void stopRolloutDisablesCandidateWithoutChangingBaseline() {
        when(jdbc.queryForList("SELECT candidate_id FROM prompt_rollout WHERE name=? AND enabled=1 FOR UPDATE",
                PromptVersionService.ANSWER_PROMPT))
                .thenReturn(Collections.singletonList(Collections.singletonMap("candidate_id", 7L)));
        assertEquals(false, service.stopRollout().get("enabled"));
        verify(jdbc).update("UPDATE prompt_rollout SET enabled=0,traffic_percent=0 WHERE name=?",
                PromptVersionService.ANSWER_PROMPT);
        verify(jdbc).update("UPDATE prompt_version SET status='inactive' WHERE id=? AND name=? AND status='gray'",
                7L, PromptVersionService.ANSWER_PROMPT);
    }


    @Test
    void createsDraftWithoutPublishingAndRejectsBlankTemplate() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(PromptVersionService.ANSWER_PROMPT))).thenReturn(2);
        when(jdbc.queryForList(anyString(), eq(PromptVersionService.ANSWER_PROMPT), eq(2)))
                .thenReturn(Collections.singletonList(version(7L, 2, "draft")));

        assertEquals("draft", service.createDraft("这是一条至少二十字符的新版回答规则，需要保留证据约束。 ").get("status"));
        verify(jdbc).update(anyString(), eq(PromptVersionService.ANSWER_PROMPT), eq(2), anyString());
        assertThrows(IllegalArgumentException.class, () -> service.createDraft("短"));
    }

    @Test
    void publishingSwitchesActiveVersionAndRollbackRestoresEarlierVersion() {
        when(jdbc.queryForList(anyString(), eq(PromptVersionService.ANSWER_PROMPT), eq(7L)))
                .thenReturn(Collections.singletonList(version(7L, 2, "draft")),
                        Collections.singletonList(version(7L, 2, "published")));
        assertEquals("published", service.publish(7L).get("status"));
        verify(jdbc).update("UPDATE prompt_version SET status='inactive' WHERE name=? AND status='published'",
                PromptVersionService.ANSWER_PROMPT);

        JdbcTemplate rollbackJdbc = mock(JdbcTemplate.class);
        PromptVersionService rollbackService = new PromptVersionService(rollbackJdbc);
        when(rollbackJdbc.queryForList(anyString(), eq(PromptVersionService.ANSWER_PROMPT), eq(1L)))
                .thenReturn(Collections.singletonList(version(1L, 1, "inactive")),
                        Collections.singletonList(version(1L, 1, "inactive")),
                        Collections.singletonList(version(1L, 1, "published")));
        when(rollbackJdbc.queryForList("SELECT version FROM prompt_version WHERE name=? AND status='published'",
                PromptVersionService.ANSWER_PROMPT)).thenReturn(Collections.singletonList(Collections.singletonMap("version", 2)));
        assertEquals("published", rollbackService.rollback(1L).get("status"));
    }

    @Test
    void rollbackRejectsNewerOrUnpublishedVersion() {
        when(jdbc.queryForList(anyString(), eq(PromptVersionService.ANSWER_PROMPT), eq(7L)))
                .thenReturn(Collections.singletonList(version(7L, 2, "draft")));
        assertThrows(IllegalArgumentException.class, () -> service.rollback(7L));
    }

    @Test
    void disablesPublishedVersionAndProtectsPublishedTemplateFromEdits() {
        when(jdbc.update("UPDATE prompt_version SET status='inactive' WHERE id=? AND name=? AND status='published'",
                7L, PromptVersionService.ANSWER_PROMPT)).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(PromptVersionService.ANSWER_PROMPT), eq(7L)))
                .thenReturn(Collections.singletonList(version(7L, 2, "inactive")));

        assertEquals("inactive", service.disable(7L).get("status"));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDraft(7L, "这是不应覆盖已经发布版本的一段新回答规则。"));
    }

    @Test
    void deletesOnlyDraftOrInactiveAndErasesTemplateWithoutReusingVersion() {
        String deleteSql = "UPDATE prompt_version SET status='deleted',template_text='' "
                + "WHERE id=? AND name=? AND status IN ('draft','inactive')";
        when(jdbc.update(deleteSql, 7L, PromptVersionService.ANSWER_PROMPT)).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(PromptVersionService.ANSWER_PROMPT), eq(7L)))
                .thenReturn(Collections.singletonList(version(7L, 2, "deleted")));

        assertEquals("deleted", service.delete(7L).get("status"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(1L));
        verify(jdbc).update(deleteSql, 7L, PromptVersionService.ANSWER_PROMPT);
    }

    private static Map<String, Object> version(long id, int number, String status) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("version", number);
        result.put("status", status);
        return result;
    }
}
