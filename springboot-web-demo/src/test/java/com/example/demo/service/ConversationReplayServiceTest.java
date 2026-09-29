package com.example.demo.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationReplayServiceTest {
    private final ConversationReplayService service = new ConversationReplayService(
            null, null, null, null, null, null, null);

    @Test
    void replaysTheLastQuestionWithOnlyItsPriorHistory() {
        JSONArray messages = new JSONArray();
        messages.add(message("user", "第一问"));
        messages.add(message("assistant", "第一答"));
        messages.add(message("user", "需要重新执行的问题"));
        messages.add(message("assistant", "原始回答"));

        ConversationReplayService.ReplayInput input = service.replayInput(messages);

        assertEquals("需要重新执行的问题", input.getQuestion());
        assertEquals("原始回答", input.getOriginalReply());
        assertEquals(2, input.getHistory().size());
        assertEquals("第一问", input.getHistory().get(0).get("content"));
    }

    @Test
    void similarityIsExactForFormattingOnlyChangesAndLowerForDifferentAnswers() {
        assertEquals(1d, service.similarity("Hello  世界", "hello世界"));
        assertTrue(service.similarity("这是一首轻音乐", "这是一首摇滚乐") < 1d);
    }

    private JSONObject message(String role, String content) {
        JSONObject item = new JSONObject();
        item.put("role", role);
        item.put("content", content);
        return item;
    }
}
