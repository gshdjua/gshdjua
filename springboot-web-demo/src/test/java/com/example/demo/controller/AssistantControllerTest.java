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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantControllerTest {

    @Test
    void keepsUserSelectionButUsesOneHealthyFallbackAfterTechnicalFailure() {
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

        User user = new User(); user.setId(7);
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(11L); conversation.setTitle("会话"); conversation.setSelectedModelId("qwen-plus");
        LlmModelCatalogService.ModelConfig requested = new LlmModelCatalogService.ModelConfig(
                "qwen-plus", "qwen", "qwen-plus", "通义千问 Plus", false, 1, 2);
        LlmModelCatalogService.ModelConfig fallback = new LlmModelCatalogService.ModelConfig(
                "deepseek-chat", "deepseek", "deepseek-chat", "DeepSeek Chat", true, 3, 9);
        when(catalogService.resolve("qwen-plus")).thenReturn(requested);
        when(catalogService.resolveHealthyFallback("qwen-plus")).thenReturn(Optional.of(fallback));
        when(request.getHeader("Authorization")).thenReturn(JwtUtil.generateToken("tester"));
        when(userMapper.selectByUsername("tester")).thenReturn(user);
        when(conversationMapper.selectByIdAndUserId(11L, 7)).thenReturn(conversation);
        when(conversationMapper.selectMessagesByConversationId(11L)).thenReturn(Collections.emptyList());
        AtomicLong messageIds = new AtomicLong(40);
        doAnswer(invocation -> { ((AssistantMessage) invocation.getArgument(0)).setId(messageIds.incrementAndGet()); return null; })
                .when(conversationMapper).insertMessage(any(AssistantMessage.class));
        DeepSeekMusicAgent.ReplyResult failed = new DeepSeekMusicAgent.ReplyResult(
                "本地回答", null, "music_answer:v1", 1, false, 0, 0, 120,
                "local", "local", "qwen", "qwen-plus", "local_fallback", "RATE_LIMITED");
        DeepSeekMusicAgent.ReplyResult succeeded = new DeepSeekMusicAgent.ReplyResult(
                "备用回答", null, "music_answer:v1", 1, true, 100, 20, 80,
                "deepseek", "deepseek-chat", "deepseek", "deepseek-chat", "model", "");
        when(agent.replyWithResult("测试降级", 7, Collections.emptyList(), 11L, "assistant-message-41", "qwen", "qwen-plus"))
                .thenReturn(failed);
        when(agent.replyWithResult("测试降级", 7, Collections.emptyList(), 11L, "assistant-message-41-fallback", "deepseek", "deepseek-chat"))
                .thenReturn(succeeded);
        when(memoryClient.capture(7, 11L, "assistant-message-41-fallback", "测试降级"))
                .thenReturn(Collections.singletonMap("capturedCount", 1));

        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", 11L); payload.put("message", "测试降级"); payload.put("modelId", "qwen-plus");
        Map<String, Object> response = controller.chat(payload, request);
        Map<?, ?> data = (Map<?, ?>) response.get("data");

        assertEquals(200, response.get("code"));
        assertEquals("qwen-plus", data.get("selectedModelId"));
        assertEquals("qwen", data.get("requestedProvider"));
        assertEquals("deepseek", data.get("actualProvider"));
        assertEquals("model_fallback", data.get("executionPath"));
        assertEquals("RATE_LIMITED", data.get("fallbackReason"));
        assertEquals(1, data.get("fallbackCount"));
        assertEquals("deepseek-chat", data.get("fallbackModelId"));
        verify(catalogService).recordInvocationHealth("qwen-plus", false, 120L, "RATE_LIMITED");
        verify(catalogService).recordInvocationHealth("deepseek-chat", true, 80L, "");
        verify(memoryClient).capture(7, 11L, "assistant-message-41-fallback", "测试降级");
    }

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
        verify(metricsService).record("none", "deepseek", "deepseek-chat", 3d, 9d, 0, true, 0, 0, 0L);
        assertEquals("none", ((Map<?, ?>) response.get("data")).get("promptVersion"));
    }
}
