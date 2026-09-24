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
import com.example.demo.service.PromptVersionService;
import com.example.demo.service.PromptOnlineMetricsService;
import com.example.demo.service.LlmModelCatalogService;
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
    void reactivatesMemoryForAuthenticatedUser() {
        AgentMemoryClient memoryClient = mock(AgentMemoryClient.class);
        UserMapper userMapper = mock(UserMapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AssistantController controller = new AssistantController();
        ReflectionTestUtils.setField(controller, "agentMemoryClient", memoryClient);
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);

        User user = new User();
        user.setId(7);
        when(request.getHeader("Authorization")).thenReturn(JwtUtil.generateToken("tester"));
        when(userMapper.selectByUsername("tester")).thenReturn(user);
        when(memoryClient.reactivate(7, 19L)).thenReturn(Collections.singletonMap("status", "active"));

        Map<String, Object> response = controller.reactivateMemory(19L, request);

        assertEquals(200, response.get("code"));
        verify(memoryClient).reactivate(7, 19L);
    }

    @Test
    void localAnswerStillUsesUnifiedMemoryCaptureEntry() {
        DeepSeekMusicAgent agent = mock(DeepSeekMusicAgent.class);
        AgentMemoryClient memoryClient = mock(AgentMemoryClient.class);
        PromptVersionService promptVersionService = mock(PromptVersionService.class);
        PromptOnlineMetricsService metricsService = mock(PromptOnlineMetricsService.class);
        LlmModelCatalogService catalogService = mock(LlmModelCatalogService.class);
        AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AssistantController controller = new AssistantController();
        ReflectionTestUtils.setField(controller, "deepSeekMusicAgent", agent);
        ReflectionTestUtils.setField(controller, "agentMemoryClient", memoryClient);
        ReflectionTestUtils.setField(controller, "promptVersionService", promptVersionService);
        ReflectionTestUtils.setField(controller, "promptOnlineMetricsService", metricsService);
        ReflectionTestUtils.setField(controller, "musicLibraryAgent", mock(MusicLibraryAgent.class));
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
        ReflectionTestUtils.setField(controller, "assistantConversationMapper", conversationMapper);
        ReflectionTestUtils.setField(controller, "audioMapper", mock(AudioMapper.class));
        ReflectionTestUtils.setField(controller, "llmModelCatalogService", catalogService);

        User user = new User();
        user.setId(7);
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(11L);
        conversation.setTitle("新对话");
        conversation.setSelectedModelId("deepseek-chat");
        LlmModelCatalogService.ModelConfig model = new LlmModelCatalogService.ModelConfig(
                "deepseek-chat", "deepseek", "deepseek-chat", "DeepSeek Chat", true, 3, 9);
        when(catalogService.resolve("deepseek-chat")).thenReturn(model);
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
        when(agent.replyWithResult("我喜欢动漫歌曲", 7, Collections.emptyList(), 11L, "assistant-message-41",
                "deepseek", "deepseek-chat"))
                .thenReturn(new DeepSeekMusicAgent.ReplyResult("本地回答", null));
        when(memoryClient.capture(7, 11L, "assistant-message-41", "我喜欢动漫歌曲"))
                .thenReturn(Collections.singletonMap("capturedCount", 1));

        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", 11L);
        payload.put("message", "我喜欢动漫歌曲");
        Map<String, Object> response = controller.chat(payload, request);

        assertEquals(200, response.get("code"));
        verify(memoryClient).capture(7, 11L, "assistant-message-41", "我喜欢动漫歌曲");
        verify(promptVersionService).recordUsage(42L, "none");
        verify(metricsService).record("none", "none", "none", 3d, 9d, 0, true, 0, 0, 0L);
        assertEquals("none", ((Map<?, ?>) response.get("data")).get("promptVersion"));
    }
}
