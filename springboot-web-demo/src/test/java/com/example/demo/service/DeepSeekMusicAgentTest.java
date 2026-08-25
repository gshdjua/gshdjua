package com.example.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
