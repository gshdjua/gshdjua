package com.example.demo.controller;

import com.example.demo.entity.AssistantConversation;
import com.example.demo.entity.AssistantMessage;
import com.example.demo.entity.Audio;
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
import com.example.demo.service.ModelInvocationLogService;
import com.example.demo.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class AssistantControllerTest {

    @Test
    void restoresPersistedRecommendationsWithConversationMessages() {
        AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AssistantController controller = new AssistantController();
        ReflectionTestUtils.setField(controller, "assistantConversationMapper", conversationMapper);
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);

        User user = new User();
        user.setId(7);
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(11L);
        AssistantMessage userMessage = new AssistantMessage();
        userMessage.setId(40L);
        userMessage.setRole("user");
        AssistantMessage assistantMessage = new AssistantMessage();
        assistantMessage.setId(41L);
        assistantMessage.setRole("assistant");
        Audio recommendation = new Audio();
        recommendation.setId(5);
        recommendation.setSongName("前前前世");

        when(request.getHeader("Authorization")).thenReturn(JwtUtil.generateToken("tester"));
        when(userMapper.selectByUsername("tester")).thenReturn(user);
        when(conversationMapper.selectByIdAndUserId(11L, 7)).thenReturn(conversation);
        when(conversationMapper.selectMessagesByConversationId(11L))
                .thenReturn(Arrays.asList(userMessage, assistantMessage));
        when(conversationMapper.selectRecommendationsByMessageId(41L))
                .thenReturn(Collections.singletonList(recommendation));

        Map<String, Object> response = controller.conversation(11L, request);
        AssistantConversation restored = (AssistantConversation) response.get("data");

        assertEquals(200, response.get("code"));
        assertEquals(1, restored.getMessages().get(1).getRecommendations().size());
        assertEquals("前前前世", restored.getMessages().get(1).getRecommendations().get(0).getSongName());
        verify(conversationMapper).selectRecommendationsByMessageId(41L);
    }

    @Test
    void persistsRecommendationOrderWithoutDuplicateSongs() {
        AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
        AssistantController controller = new AssistantController();
        ReflectionTestUtils.setField(controller, "assistantConversationMapper", conversationMapper);
        AssistantMessage assistantMessage = new AssistantMessage();
        assistantMessage.setId(41L);
        Audio first = new Audio();
        first.setId(5);
        Audio second = new Audio();
        second.setId(9);

        ReflectionTestUtils.invokeMethod(controller, "persistRecommendations", assistantMessage,
                Arrays.asList(first, second, first));

        verify(conversationMapper).insertMessageRecommendation(41L, 5, 0);
        verify(conversationMapper).insertMessageRecommendation(41L, 9, 1);
    }

    @Test
    void persistsQuestionAndPartialAnswerWhenStreamingIsCancelled() {
        AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        AssistantController controller = new AssistantController();
        ReflectionTestUtils.setField(controller, "assistantConversationMapper", conversationMapper);
        ReflectionTestUtils.setField(controller, "transactionManager", transactionManager);

        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(11L);
        conversation.setTitle("会话");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(conversationMapper.selectByIdAndUserId(11L, 7)).thenReturn(conversation);
        List<AssistantMessage> savedMessages = new ArrayList<>();
        doAnswer(invocation -> {
            savedMessages.add(invocation.getArgument(0));
            return null;
        }).when(conversationMapper).insertMessage(any(AssistantMessage.class));

        ReflectionTestUtils.invokeMethod(controller, "saveCancelledExchange",
                11L, 7, "请介绍这首歌", "这是已经生成的部分回答");

        assertEquals(2, savedMessages.size());
        assertEquals("user", savedMessages.get(0).getRole());
        assertEquals("请介绍这首歌", savedMessages.get(0).getContent());
        assertEquals("assistant", savedMessages.get(1).getRole());
        assertEquals("这是已经生成的部分回答\n\n（已停止生成）", savedMessages.get(1).getContent());
        verify(conversationMapper).touchConversation(11L);
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void keepsUserSelectionButUsesOneHealthyFallbackAfterTechnicalFailure() {
        DeepSeekMusicAgent agent = mock(DeepSeekMusicAgent.class);
        AgentMemoryClient memoryClient = mock(AgentMemoryClient.class);
        PromptVersionService promptVersionService = mock(PromptVersionService.class);
        PromptOnlineMetricsService metricsService = mock(PromptOnlineMetricsService.class);
        LlmModelCatalogService catalogService = mock(LlmModelCatalogService.class);
        ModelInvocationLogService invocationLogService = mock(ModelInvocationLogService.class);
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
        ReflectionTestUtils.setField(controller, "modelInvocationLogService", invocationLogService);

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
        verify(invocationLogService).record(eq("assistant-message-41"), eq("assistant-message-41-fallback"),
                eq(requested), eq(failed), eq(fallback), eq(succeeded), any(DeepSeekMusicAgent.ReplyResult.class));
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
        ModelInvocationLogService invocationLogService = mock(ModelInvocationLogService.class);
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
        ReflectionTestUtils.setField(controller, "modelInvocationLogService", invocationLogService);

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
