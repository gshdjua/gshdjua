package com.example.demo.service;

import com.alibaba.fastjson.JSONArray;
import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.EntityType;
import com.example.demo.service.retrieval.EntityQueryParser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class DeepSeekMusicAgentTest {

    private final DeepSeekMusicAgent agent = new DeepSeekMusicAgent();

    @Test
    void productionAnswerUsesPublishedPromptAfterFixedSafetyRules() {
        PromptVersionService prompts = mock(PromptVersionService.class);
        when(prompts.currentAnswerPrompt(null)).thenReturn(
                new PromptVersionService.SelectedPrompt("新版回答风格：简洁说明推荐理由。", "music_answer:v2"));
        ReflectionTestUtils.setField(agent, "promptVersionService", prompts);
        JSONArray messages = new JSONArray();

        agent.appendAnswerSystemMessages(messages);

        assertEquals(3, messages.size());
        assertTrue(messages.getJSONObject(0).getString("content").contains("禁止编造"));
        assertEquals("新版回答风格：简洁说明推荐理由。", messages.getJSONObject(1).getString("content"));
    }

    @Test
    void explicitEvaluationVersionOverridesUserRolloutOnlyForThatRequest() {
        PromptVersionService prompts = mock(PromptVersionService.class);
        when(prompts.forEvaluation(2)).thenReturn(
                new PromptVersionService.SelectedPrompt("候选版本回答规则", "music_answer:v2"));
        ReflectionTestUtils.setField(agent, "promptVersionService", prompts);
        @SuppressWarnings("unchecked")
        ThreadLocal<Integer> override = (ThreadLocal<Integer>) ReflectionTestUtils.getField(agent, "evaluationPromptVersion");
        override.set(2);
        try {
            JSONArray messages = new JSONArray();
            agent.appendAnswerSystemMessages(messages);
            assertEquals("候选版本回答规则", messages.getJSONObject(1).getString("content"));
            verify(prompts).forEvaluation(2);
            verify(prompts, never()).currentAnswerPrompt(any());
        } finally {
            override.remove();
        }
    }

    @Test
    void recommendationAnswerRejectsSongOutsideVerifiedCandidates() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        Audio verified = new Audio();
        verified.setId(1);
        verified.setSongName("轻快动画歌曲");
        Audio flowerDance = new Audio();
        flowerDance.setId(2);
        flowerDance.setSongName("Flower Dance");
        when(audioMapper.selectAll()).thenReturn(Arrays.asList(verified, flowerDance));
        ReflectionTestUtils.setField(agent, "audioMapper", audioMapper);

        assertTrue(agent.mentionsUnselectedSong("推荐轻快动画歌曲和 Flower Dance", Collections.singletonList(verified)));
        assertFalse(agent.mentionsUnselectedSong("推荐轻快动画歌曲", Collections.singletonList(verified)));
    }

    @Test
    void modelJudgedRecommendationCardsOnlyContainNamedSongs() {
        Audio first = new Audio();
        first.setId(1);
        first.setSongName("轻快动画歌曲");
        Audio second = new Audio();
        second.setId(2);
        second.setSongName("激昂动画歌曲");
        MusicLibraryAgent.RecommendationOutcome candidates = new MusicLibraryAgent.RecommendationOutcome(
                3, 2, 0, "INSUFFICIENT_MATCHES", Arrays.asList(first, second));

        MusicLibraryAgent.RecommendationOutcome shown = agent.recommendationsMentionedInAnswer(
                candidates, "目前只能确认《轻快动画歌曲》更适合轻松听感。 ");

        assertEquals(1, shown.getSongs().size());
        assertEquals(Integer.valueOf(1), shown.getSongs().get(0).getId());
        assertTrue(shown.hasShortfall());
    }

    @Test
    void animeMoodCostPreviewCallsModelToJudgeCandidatesEvenWhenFewerThanRequested() {
        AssistantQueryUnderstandingService understanding = new AssistantQueryUnderstandingService();
        EntityQueryParser parser = new EntityQueryParser();
        ReflectionTestUtils.setField(parser, "queryUnderstandingService", understanding);
        MusicLibraryAgent libraryAgent = mock(MusicLibraryAgent.class);
        DeepSeekMusicAgent evaluatedAgent = new DeepSeekMusicAgent();
        ReflectionTestUtils.setField(evaluatedAgent, "entityQueryParser", parser);
        ReflectionTestUtils.setField(evaluatedAgent, "queryUnderstandingService", understanding);
        ReflectionTestUtils.setField(evaluatedAgent, "musicLibraryAgent", libraryAgent);
        PromptVersionService prompts = mock(PromptVersionService.class);
        when(prompts.currentAnswerPrompt(10)).thenReturn(new PromptVersionService.SelectedPrompt(
                "只根据候选简介判断歌曲听感，不要编造出处或凑数。", "music_answer:v3"));
        ReflectionTestUtils.setField(evaluatedAgent, "promptVersionService", prompts);
        String question = "推荐三首轻松的动漫歌曲";
        Audio verified = new Audio();
        verified.setId(4);
        verified.setSongName("轻快动画歌曲");
        verified.setSinger("歌手");
        when(libraryAgent.getRecommendationOutcome(eq(understanding.normalize(question)), eq(10), eq(3),
                org.mockito.ArgumentMatchers.<Set<Integer>>any())).thenReturn(
                new MusicLibraryAgent.RecommendationOutcome(3, 1, 0, "INSUFFICIENT_MATCHES",
                        Collections.singletonList(verified)));

        Map<String, Object> preview = evaluatedAgent.evaluateLlmCost(question, 10,
                Collections.emptyList(), false, 300, 3, 9);

        assertTrue((Boolean) preview.get("modelRequired"));
        assertEquals("estimated_agent", preview.get("executionPath"));
        assertEquals(1, preview.get("modelCalls"));
    }

    @Test
    void hidesEvidenceReferencesWhenLocalLibraryHasNoReliableMatch() {
        assertFalse(agent.shouldShowEvidenceReferences(
                "本地歌库未检索到足够可靠的证据来确认该对象。"));
        assertFalse(agent.shouldShowEvidenceReferences(
                "公开背景信息可以说明该角色，但这一信息不属于本地歌库证据。"));
    }

    @Test
    void showsEvidenceReferencesWhenAnswerUsesMatchedSongs() {
        assertTrue(agent.shouldShowEvidenceReferences(
                "本地歌库收录了《Good knows》，歌手是平野绫 [S1]。"));
    }

    @Test
    void hidesInternalRetrievalDetailsFromUserFacingAnswer() {
        String answer = "本地歌库收录了《Good knows》，歌手是平野绫 [S1]。\n\n"
                + "本地证据来源：\n"
                + "[S1] 本地歌库：《Good knows》；检索方式：[VECTOR, KEYWORD]\n"
                + "[S2] 无关候选；检索方式：[VECTOR]";

        assertEquals("本地歌库收录了《Good knows》，歌手是平野绫。",
                agent.sanitizeUserFacingAnswer(answer));
    }

    @Test
    void removesMarkdownBoldAndDashListMarkers() {
        String answer = "关于《Good knows》：\n\n- **歌手与出处**：平野绫\n- **听感特点**：节奏明快";

        assertEquals("关于《Good knows》：\n\n歌手与出处：平野绫\n听感特点：节奏明快",
                agent.sanitizeUserFacingAnswer(answer));
    }

    @Test
    void selectsEvidenceLimitByIntentWithoutChangingEvaluationTopK() {
        assertEquals(1, agent.evidenceLimitForIntent(AssistantIntent.SONG_METADATA, "介绍这首歌"));
        assertEquals(3, agent.evidenceLimitForIntent(AssistantIntent.GENERAL, "它有什么特点"));
        assertEquals(5, agent.evidenceLimitForIntent(AssistantIntent.SOURCE_QUERY, "这部动画有哪些歌"));
        assertEquals(5, agent.evidenceLimitForIntent(AssistantIntent.RECOMMENDATION, "推荐歌曲"));
        assertEquals(8, agent.evidenceLimitForIntent(AssistantIntent.RECOMMENDATION, "推荐8首歌曲"));
        assertEquals(3, agent.evidenceLimitForIntent(AssistantIntent.RECOMMENDATION, "推荐三首歌曲"));
        assertEquals(12, agent.evidenceLimitForIntent(AssistantIntent.RECOMMENDATION, "推荐20首歌曲"));
    }

    @Test
    void selectsStrictEntityEvidenceLimitByEntityType() {
        assertEquals(1, agent.strictEntityEvidenceLimit(EntityType.SONG));
        assertEquals(5, agent.strictEntityEvidenceLimit(EntityType.SINGER));
        assertEquals(5, agent.strictEntityEvidenceLimit(EntityType.SOURCE));
    }

    @Test
    void routesGeneralMoodQuestionThroughMoodRetrieval() {
        AssistantQueryUnderstandingService understanding = new AssistantQueryUnderstandingService();
        EntityQueryParser parser = new EntityQueryParser();
        ReflectionTestUtils.setField(parser, "queryUnderstandingService", understanding);
        MusicLibraryAgent libraryAgent = mock(MusicLibraryAgent.class);
        DeepSeekMusicAgent routedAgent = new DeepSeekMusicAgent();
        ReflectionTestUtils.setField(routedAgent, "entityQueryParser", parser);
        ReflectionTestUtils.setField(routedAgent, "queryUnderstandingService", understanding);
        ReflectionTestUtils.setField(routedAgent, "musicLibraryAgent", libraryAgent);
        String question = "咱们歌库里有轻松的音乐吗？";
        when(libraryAgent.findExactSourceSongs(question, 5)).thenReturn(Collections.emptyList());
        MusicLibraryAgent.RecommendationOutcome outcome = new MusicLibraryAgent.RecommendationOutcome(
                5, 0, 0, "INSUFFICIENT_MATCHES", Collections.emptyList());
        when(libraryAgent.getRecommendationOutcome(question, 7, 5, Collections.emptySet())).thenReturn(outcome);
        when(libraryAgent.formatRecommendationReply(question, outcome)).thenReturn("没有匹配歌曲");

        String answer = routedAgent.reply(question, 7, Collections.emptyList());

        assertEquals("没有匹配歌曲", answer);
        verify(libraryAgent).getRecommendationOutcome(question, 7, 5, Collections.emptySet());
    }

    @Test
    void costEvaluationV2ReportsLocalBypassWithoutPersistingAnswerPreview() {
        AssistantQueryUnderstandingService understanding = new AssistantQueryUnderstandingService();
        EntityQueryParser parser = new EntityQueryParser();
        ReflectionTestUtils.setField(parser, "queryUnderstandingService", understanding);
        MusicLibraryAgent libraryAgent = mock(MusicLibraryAgent.class);
        DeepSeekMusicAgent evaluatedAgent = new DeepSeekMusicAgent();
        ReflectionTestUtils.setField(evaluatedAgent, "entityQueryParser", parser);
        ReflectionTestUtils.setField(evaluatedAgent, "queryUnderstandingService", understanding);
        ReflectionTestUtils.setField(evaluatedAgent, "musicLibraryAgent", libraryAgent);
        when(libraryAgent.findExactSourceSongs("歌库现在有多少首歌曲？", 5)).thenReturn(Collections.emptyList());

        Map<String, Object> result = evaluatedAgent.evaluateLlmCost(
                "歌库现在有多少首歌曲？", null, Collections.emptyList(), false, 100, 3, 9);

        assertEquals("2.0", result.get("evaluationVersion"));
        assertEquals("local", result.get("executionPath"));
        assertEquals("local", result.get("selectedStrategy"));
        assertEquals(0, result.get("modelCalls"));
        assertFalse(result.containsKey("answerPreview"));
    }

    @Test
    void agentNativeSimulationUsesAgentStrategyPreviewWithoutExecutingTools() {
        AgentServiceClient client = mock(AgentServiceClient.class);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("selectedStrategy", "direct");
        preview.put("strategyReason", "single_step_request");
        preview.put("costBudget", "standard");
        preview.put("plannedTool", "favorite_search");
        when(client.previewStrategy("我收藏了哪些动漫歌曲？", "auto", "standard")).thenReturn(preview);
        DeepSeekMusicAgent evaluatedAgent = new DeepSeekMusicAgent();
        ReflectionTestUtils.setField(evaluatedAgent, "agentServiceClient", client);

        Map<String, Object> result = evaluatedAgent.evaluateLlmCost(
                "我收藏了哪些动漫歌曲？", 7, Collections.emptyList(), false, 180, 3, 9,
                "agent_native", "auto", "standard");

        assertEquals("agent_native", result.get("executionTarget"));
        assertEquals("estimated_agent_native", result.get("executionPath"));
        assertEquals("direct", result.get("selectedStrategy"));
        assertEquals("favorite_search", result.get("plannedTool"));
        assertEquals(1, result.get("modelCalls"));
        assertEquals(0, result.get("toolCalls"));
        verify(client, never()).chatNativeEvaluation(anyString(), any(), anyString(), anyString());
    }

    @Test
    void unavailableAgentNativeRealCallDoesNotReportEstimatedUsageAsActual() {
        AgentServiceClient client = mock(AgentServiceClient.class);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("selectedStrategy", "direct");
        preview.put("costBudget", "standard");
        preview.put("plannedTool", "song_detail");
        when(client.previewStrategy(anyString(), anyString(), anyString())).thenReturn(preview);
        DeepSeekMusicAgent evaluatedAgent = new DeepSeekMusicAgent();
        ReflectionTestUtils.setField(evaluatedAgent, "agentServiceClient", client);

        Map<String, Object> result = evaluatedAgent.evaluateLlmCost(
                "介绍 Good knows", 7, Collections.emptyList(), true, 200, 3, 9,
                "agent_native", "auto", "standard");

        assertEquals("agent_unavailable", result.get("executionPath"));
        assertEquals(0, result.get("modelCalls"));
        assertEquals(0, result.get("totalTokens"));
        assertFalse((Boolean) result.get("actualUsage"));
    }
}
