package com.example.demo.service;

import com.example.demo.service.retrieval.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekMusicAgentTest {

    private final DeepSeekMusicAgent agent = new DeepSeekMusicAgent();

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
}
