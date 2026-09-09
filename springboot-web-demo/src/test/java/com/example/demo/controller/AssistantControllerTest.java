package com.example.demo.controller;

import com.example.demo.entity.AssistantConversation;
import com.example.demo.entity.AssistantMessage;
import com.example.demo.entity.User;
import com.example.demo.mapper.AssistantConversationMapper;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.UserMapper;
import com.example.demo.service.AgentMemoryClient;
import com.example.demo.service.DeepSeekMusicAgent;
import com.example.demo.service.MusicLibraryAgent;
import com.example.demo.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantControllerTest {

    @Test
    void localAnswerStillUsesUnifiedMemoryCaptureEntry() {
        DeepSeekMusicAgent agent = mock(DeepSeekMusicAgent.class);
        AgentMemoryClient memoryClient = mock(AgentMemoryClient.class);
        AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AssistantController controller = new AssistantController();
        ReflectionTestUtils.setField(controller, "deepSeekMusicAgent", agent);
        ReflectionTestUtils.setField(controller, "agentMemoryClient", memoryClient);
        ReflectionTestUtils.setField(controller, "musicLibraryAgent", mock(MusicLibraryAgent.class));
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
        ReflectionTestUtils.setField(controller, "assistantConversationMapper", conversationMapper);
        ReflectionTestUtils.setField(controller, "audioMapper", mock(AudioMapper.class));

        User user = new User();
        user.setId(7);
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(11L);
        conversation.setTitle("新对话");
        when(request.getHeader("Authorization")).thenReturn(JwtUtil.generateToken("tester"));
        when(userMapper.selectByUsername("tester")).thenReturn(user);
        when(conversationMapper.selectByIdAndUserId(11L, 7)).thenReturn(conversation);
        when(conversationMapper.selectMessagesByConversationId(11L)).thenReturn(Collections.emptyList());
        AtomicLong messageIds = new AtomicLong(40);
        doAnswer(invocation -> {
            AssistantMessage item = invocation.getArgument(0);
            item.setId(messageIds.incrementAndGet());
            return null;
        }).when(conversationMapper).insertMessage(any(AssistantMessage.class));
        when(agent.reply("我喜欢动漫歌曲", 7, Collections.emptyList(), 11L, "assistant-message-41"))
                .thenReturn("本地回答");

        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", 11L);
        payload.put("message", "我喜欢动漫歌曲");
        Map<String, Object> response = controller.chat(payload, request);

        assertEquals(200, response.get("code"));
        verify(memoryClient).capture(7, 11L, "assistant-message-41", "我喜欢动漫歌曲");
    }
}
