package com.example.demo.controller;

import com.example.demo.entity.AssistantConversation;
import com.example.demo.entity.AssistantMessage;
import com.example.demo.entity.Audio;
import com.example.demo.entity.User;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.AssistantConversationMapper;
import com.example.demo.mapper.UserMapper;
import com.example.demo.service.DeepSeekMusicAgent;
import com.example.demo.service.PromptVersionService;
import com.example.demo.service.PromptOnlineMetricsService;
import com.example.demo.service.PromptFeedbackService;
import com.example.demo.service.AgentMemoryClient;
import com.example.demo.service.MusicLibraryAgent;
import com.example.demo.service.LlmModelCatalogService;
import com.example.demo.service.ModelInvocationLogService;
import com.example.demo.service.AgentServiceClient;
import com.example.demo.service.UserLlmQuotaService;
import com.example.demo.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private static final Logger LOGGER = Logger.getLogger(AssistantController.class.getName());

    @Autowired
    private DeepSeekMusicAgent deepSeekMusicAgent;

    @Autowired
    private PromptVersionService promptVersionService;

    @Autowired
    private PromptOnlineMetricsService promptOnlineMetricsService;

    @Autowired
    private PromptFeedbackService promptFeedbackService;

    @Autowired
    private AgentMemoryClient agentMemoryClient;

    @Autowired
    private MusicLibraryAgent musicLibraryAgent;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AssistantConversationMapper assistantConversationMapper;

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private LlmModelCatalogService llmModelCatalogService;

    @Autowired
    private ModelInvocationLogService modelInvocationLogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AgentServiceClient agentServiceClient;

    @Autowired(required = false)
    private UserLlmQuotaService userLlmQuotaService;

    private final ConcurrentMap<String, StreamSession> activeStreams = new ConcurrentHashMap<>();

    @PostMapping("/chat")
    @Transactional
    public Map<String, Object> chat(@RequestBody Map<String, Object> payload, HttpServletRequest request,
                                    HttpServletResponse response) {
        Integer userId = getUserId(request);
        try (UserLlmQuotaService.Permit ignored = acquireQuota(userId, false)) {
            return processChat(payload, userId, null, null);
        } catch (UserLlmQuotaService.QuotaExceededException exception) {
            applyQuotaResponse(response, exception);
            return quotaResult(exception);
        }
    }

    /** Compatibility entry point retained for focused controller tests. */
    public Map<String, Object> chat(Map<String, Object> payload, HttpServletRequest request) {
        return chat(payload, request, null);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> payload, HttpServletRequest request,
                                 HttpServletResponse response) {
        Integer userId = getUserId(request);
        final UserLlmQuotaService.Permit quotaPermit;
        try {
            quotaPermit = acquireQuota(userId, true);
        } catch (UserLlmQuotaService.QuotaExceededException exception) {
            applyQuotaResponse(response, exception);
            SseEmitter rejected = new SseEmitter(1000L);
            try { rejected.send(SseEmitter.event().name("error").data(quotaResult(exception))); }
            catch (IOException ignored) { }
            rejected.complete();
            return rejected;
        }
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(60000L);
        String suppliedStreamId = String.valueOf(payload.getOrDefault("streamId", "")).trim();
        String candidateStreamId = suppliedStreamId.matches("[A-Za-z0-9_-]{8,100}")
                ? suppliedStreamId : UUID.randomUUID().toString();
        Long conversationId = toLong(payload.get("conversationId"));
        String originalMessage = String.valueOf(payload.getOrDefault("message", "")).trim();
        StreamSession candidateSession = new StreamSession(candidateStreamId, userId, conversationId,
                originalMessage, quotaPermit);
        while (activeStreams.putIfAbsent(candidateStreamId, candidateSession) != null) {
            candidateStreamId = UUID.randomUUID().toString();
            candidateSession = new StreamSession(candidateStreamId, userId, conversationId,
                    originalMessage, quotaPermit);
        }
        final String streamId = candidateStreamId;
        final StreamSession session = candidateSession;
        emitter.onTimeout(() -> cancelStream(streamId));
        emitter.onError(error -> cancelStream(streamId));
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            AtomicBoolean emittedModelDelta = new AtomicBoolean(false);
            AtomicBoolean streamOpen = new AtomicBoolean(true);
            try {
                Map<String, Object> start = new HashMap<>();
                start.put("streamId", streamId);
                start.put("conversationId", payload.get("conversationId"));
                emitter.send(SseEmitter.event().name("start").data(start));
                Consumer<String> modelDelta = content -> {
                    if (!streamOpen.get() || session.cancelled.get() || content == null || content.isEmpty()) return;
                    try {
                        sendDelta(emitter, session, content);
                        emittedModelDelta.set(true);
                    } catch (IOException exception) {
                        streamOpen.set(false);
                        cancelStream(streamId);
                    }
                };
                Map<String, Object> result = new TransactionTemplate(transactionManager).execute(
                        status -> processChat(payload, userId, modelDelta, session));
                if (result == null) throw new IllegalStateException("流式回答事务未返回结果");
                session.chatCommitted.set(true);
                ensureStreamActive(session);
                if (!Integer.valueOf(200).equals(result.get("code"))) {
                    emitter.send(SseEmitter.event().name("error").data(result));
                    finishStreamMetric(session, "error");
                    activeStreams.remove(streamId, session);
                    emitter.complete();
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (!emittedModelDelta.get()) {
                    emitReplyDeltas(emitter, session, String.valueOf(data.getOrDefault("reply", "")));
                }
                finishStreamMetric(session, "completed");
                data.put("streamMetrics", session.metrics());
                emitter.send(SseEmitter.event().name("done").data(data));
                activeStreams.remove(streamId, session);
                emitter.complete();
            } catch (Exception exception) {
                String status = session.cancelled.get() ? "cancelled" : "error";
                if (session.cancelled.get() && !session.chatCommitted.get()
                        && session.cancelledExchangeSaved.compareAndSet(false, true)) {
                    saveCancelledExchange(session.conversationId, session.userId,
                            session.originalMessage, session.partialReply());
                }
                finishStreamMetric(session, status);
                activeStreams.remove(streamId, session);
                if (session.cancelled.get()) {
                    emitter.complete();
                    return;
                }
                LOGGER.log(Level.WARNING, "Assistant SSE stream failed", exception);
                try {
                    Map<String, Object> error = new HashMap<>();
                    error.put("code", 500);
                    error.put("msg", "流式回答失败，请稍后重试");
                    emitter.send(SseEmitter.event().name("error").data(error));
                    emitter.complete();
                } catch (IOException sendError) {
                    emitter.completeWithError(sendError);
                }
            } finally {
                session.quotaPermit.close();
            }
        });
        session.future = future;
        return emitter;
    }

    @DeleteMapping("/chat/stream/{streamId}")
    public Map<String, Object> cancelChatStream(@PathVariable String streamId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        StreamSession session = activeStreams.get(streamId);
        boolean cancelled = session != null && session.userId.equals(userId);
        if (cancelled) cancelStream(streamId);
        Map<String, Object> data = new HashMap<>();
        data.put("streamId", streamId);
        data.put("cancelled", cancelled);
        return result(200, cancelled ? "生成已停止" : "流式请求已结束", data);
    }

    private void cancelStream(String streamId) {
        StreamSession session = activeStreams.get(streamId);
        if (session == null || !session.cancelled.compareAndSet(false, true)) return;
        String requestId = session.agentRequestId;
        if (requestId != null && !requestId.isEmpty()) agentServiceClient.cancelChat(requestId);
        CompletableFuture<Void> future = session.future;
        if (future != null) future.cancel(true);
    }

    private void sendDelta(SseEmitter emitter, StreamSession session, String content) throws IOException {
        ensureStreamActive(session);
        emitter.send(SseEmitter.event().name("delta").data(Collections.singletonMap("content", content)));
        session.recordDelta(content);
    }

    private void emitReplyDeltas(SseEmitter emitter, StreamSession session, String reply) throws IOException {
        int offset = 0;
        while (offset < reply.length()) {
            int remainingCodePoints = reply.codePointCount(offset, reply.length());
            int end = reply.offsetByCodePoints(offset, Math.min(16, remainingCodePoints));
            sendDelta(emitter, session, reply.substring(offset, end));
            offset = end;
        }
    }

    private void finishStreamMetric(StreamSession session, String status) {
        if (!session.finished.compareAndSet(false, true)) return;
        try {
            modelInvocationLogService.finishStream(session.userId, session.traceId(), session.provider, session.model,
                    session.firstTokenMs(), session.totalMs(), session.eventCount.get(),
                    session.charCount.get(), session.modelCallCount.get(), session.estimatedInputTokens.get(),
                    session.estimatedOutputTokens(), session.estimatedCost(), status);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not persist SSE performance metrics", exception);
        }
    }

    private void ensureStreamActive(StreamSession session) {
        if (session != null && (session.cancelled.get() || Thread.currentThread().isInterrupted())) {
            throw new StreamCancelledException();
        }
    }

    private void saveCancelledExchange(Long conversationId, Integer userId,
                                       String originalMessage, String partialReply) {
        if (conversationId == null || originalMessage == null || originalMessage.isEmpty()
                || originalMessage.length() > 2000) return;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                AssistantConversation conversation = assistantConversationMapper
                        .selectByIdAndUserId(conversationId, userId);
                if (conversation == null) return;
                assistantConversationMapper.insertMessage(message(conversationId, "user", originalMessage));
                String visibleReply = partialReply == null ? "" : partialReply.trim();
                visibleReply = visibleReply.isEmpty()
                        ? "（已停止生成，尚未生成回答）"
                        : visibleReply + "\n\n（已停止生成）";
                assistantConversationMapper.insertMessage(message(conversationId, "assistant", visibleReply));
                if ("新对话".equals(conversation.getTitle())) {
                    assistantConversationMapper.updateTitle(conversationId, userId,
                            originalMessage.substring(0, Math.min(originalMessage.length(), 18)));
                } else {
                    assistantConversationMapper.touchConversation(conversationId);
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not persist cancelled assistant exchange", exception);
        }
    }

    private Map<String, Object> processChat(Map<String, Object> payload, Integer userId,
                                            Consumer<String> onDelta, StreamSession streamSession) {
        ensureStreamActive(streamSession);
        Long conversationId = toLong(payload.get("conversationId"));
        AssistantConversation conversation = conversationId == null ? null
                : assistantConversationMapper.selectByIdAndUserId(conversationId, userId);
        if (conversation == null) return result(500, "Conversation not found", null);

        boolean explicitlyRequestedModel = payload.get("modelId") != null;
        String requestedModelId = explicitlyRequestedModel ? String.valueOf(payload.get("modelId")).trim()
                : conversation.getSelectedModelId();
        LlmModelCatalogService.ModelConfig selectedModel;
        try {
            selectedModel = llmModelCatalogService.resolve(requestedModelId);
        } catch (IllegalArgumentException exception) {
            if (explicitlyRequestedModel) return result(400, exception.getMessage(), null);
            selectedModel = llmModelCatalogService.resolve(null);
        }
        if (!selectedModel.getId().equals(conversation.getSelectedModelId())) {
            assistantConversationMapper.updateSelectedModel(conversationId, userId, selectedModel.getId());
            conversation.setSelectedModelId(selectedModel.getId());
        }
        if (streamSession != null) {
            streamSession.provider = selectedModel.getProvider();
            streamSession.model = selectedModel.getModel();
        }

        String message = String.valueOf(payload.getOrDefault("message", "")).trim();
        if (message.isEmpty()) return result(500, "Message cannot be empty", null);
        if (message.length() > 2000) return result(500, "Message cannot exceed 2000 characters", null);

        List<Map<String, String>> history = toHistory(assistantConversationMapper.selectMessagesByConversationId(conversationId));
        AssistantMessage userMessage = message(conversationId, "user", message);
        assistantConversationMapper.insertMessage(userMessage);
        String messageForAgent = enrichWithCurrentAudio(message, conversation.getCurrentAudioId());
        String memoryRequestId = userMessage.getId() == null
                ? "assistant-message-" + UUID.randomUUID()
                : "assistant-message-" + userMessage.getId();
        if (streamSession != null) streamSession.agentRequestId = memoryRequestId;
        DeepSeekMusicAgent.ReplyResult replyResult = invokeAgent(messageForAgent, userId, history,
                conversationId, memoryRequestId, prepareStreamModelCall(streamSession, selectedModel,
                        messageForAgent, history), onDelta);
        ensureStreamActive(streamSession);
        recordModelAttempt(selectedModel, replyResult);

        LlmModelCatalogService.ModelConfig fallbackModel = null;
        DeepSeekMusicAgent.ReplyResult fallbackAttempt = null;
        DeepSeekMusicAgent.ReplyResult requestedAttempt = replyResult;
        String effectiveMemoryRequestId = memoryRequestId;
        if ("local_fallback".equals(replyResult.getExecutionPath())) {
            fallbackModel = llmModelCatalogService.resolveHealthyFallback(selectedModel.getId()).orElse(null);
            if (fallbackModel != null) {
                effectiveMemoryRequestId = memoryRequestId + "-fallback";
                if (streamSession != null) streamSession.agentRequestId = effectiveMemoryRequestId;
                fallbackAttempt = invokeAgent(messageForAgent, userId, history, conversationId,
                        effectiveMemoryRequestId, prepareStreamModelCall(streamSession, fallbackModel,
                                messageForAgent, history), onDelta);
                ensureStreamActive(streamSession);
                recordModelAttempt(fallbackModel, fallbackAttempt);
                replyResult = DeepSeekMusicAgent.ReplyResult.afterFailover(requestedAttempt, fallbackAttempt);
            }
        }
        try {
            modelInvocationLogService.record(memoryRequestId,
                    fallbackModel == null ? "" : effectiveMemoryRequestId,
                    selectedModel, requestedAttempt, fallbackModel, fallbackAttempt, replyResult);
            modelInvocationLogService.associateUser(memoryRequestId, userId);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not persist privacy-safe LLM invocation log", exception);
        }

        String reply = replyResult.getReply();
        if (fallbackModel != null) {
            String reason = fallbackReasonLabel(requestedAttempt.getFallbackReason());
            if ("model_fallback".equals(replyResult.getExecutionPath())) {
                reply = "你选择的 " + selectedModel.getDisplayName() + " 暂时不可用（" + reason
                        + "），本次已切换至 " + fallbackModel.getDisplayName() + "。\n\n" + reply;
            } else {
                reply = "你选择的模型与备用模型当前均不可用，本次已使用本地歌库回答。\n\n" + reply;
            }
        }
        Object memoryCapture = agentMemoryClient.capture(userId, conversationId, effectiveMemoryRequestId, message);
        if (memoryCapture == null && !reply.contains("Agent Service 当前不可用")) {
            reply += "\n\n（Agent 记忆服务当前不可用，本轮长期偏好可能未保存。）";
        }
        AssistantMessage assistantMessage = message(conversationId, "assistant", reply);
        assistantConversationMapper.insertMessage(assistantMessage);
        persistRecommendations(assistantMessage, replyResult.getRecommendations());
        promptVersionService.recordUsage(assistantMessage.getId(), replyResult.getPromptVersion());
        assistantMessage.setPromptVersion(replyResult.getPromptVersion());
        if ("新对话".equals(conversation.getTitle())) {
            assistantConversationMapper.updateTitle(conversationId, userId, message.substring(0, Math.min(message.length(), 18)));
        } else {
            assistantConversationMapper.touchConversation(conversationId);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("reply", reply);
        data.put("promptVersion", replyResult.getPromptVersion());
        data.put("userMessage", userMessage);
        data.put("assistantMessage", assistantMessage);
        data.put("recommendations", replyResult.getRecommendations());
        data.put("recommendationMeta", replyResult.getRecommendationMetadata());
        data.put("selectedModelId", selectedModel.getId());
        data.put("requestedProvider", replyResult.getRequestedProvider());
        data.put("requestedModel", replyResult.getRequestedModel());
        data.put("actualProvider", replyResult.getProvider());
        data.put("actualModel", replyResult.getModel());
        data.put("executionPath", replyResult.getExecutionPath());
        data.put("fallbackReason", replyResult.getFallbackReason());
        data.put("retryCount", replyResult.getRetryCount());
        data.put("fallbackCount", replyResult.getFallbackCount());
        data.put("fallbackModelId", fallbackModel == null ? "" : fallbackModel.getId());
        data.put("fallbackModelName", fallbackModel == null ? "" : fallbackModel.getDisplayName());
        data.put("requestedTraceId", memoryRequestId);
        data.put("fallbackTraceId", fallbackModel == null ? "" : effectiveMemoryRequestId);
        return result(200, "success", data);
    }

    private static final class StreamCancelledException extends RuntimeException { }

    private LlmModelCatalogService.ModelConfig prepareStreamModelCall(StreamSession session,
                                                                       LlmModelCatalogService.ModelConfig model,
                                                                       String message,
                                                                       List<Map<String, String>> history) {
        if (session != null) session.beginModelCall(model, message, history);
        return model;
    }

    private static final class StreamSession {
        private final String streamId;
        private final Integer userId;
        private final Long conversationId;
        private final String originalMessage;
        private final UserLlmQuotaService.Permit quotaPermit;
        private final long startedAtNanos = System.nanoTime();
        private final StringBuilder partialReply = new StringBuilder();
        private final AtomicLong firstTokenNanos = new AtomicLong(0);
        private final AtomicInteger eventCount = new AtomicInteger(0);
        private final AtomicInteger charCount = new AtomicInteger(0);
        private final AtomicInteger modelCallCount = new AtomicInteger(0);
        private final AtomicInteger estimatedInputTokens = new AtomicInteger(0);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean chatCommitted = new AtomicBoolean(false);
        private final AtomicBoolean cancelledExchangeSaved = new AtomicBoolean(false);
        private volatile String agentRequestId = "";
        private volatile String provider = "none";
        private volatile String model = "none";
        private volatile double estimatedInputCost;
        private volatile double activeOutputPrice;
        private volatile CompletableFuture<Void> future;

        private StreamSession(String streamId, Integer userId, Long conversationId, String originalMessage,
                              UserLlmQuotaService.Permit quotaPermit) {
            this.streamId = streamId;
            this.userId = userId;
            this.conversationId = conversationId;
            this.originalMessage = originalMessage;
            this.quotaPermit = quotaPermit;
        }

        private synchronized void recordDelta(String content) {
            firstTokenNanos.compareAndSet(0, System.nanoTime());
            eventCount.incrementAndGet();
            charCount.addAndGet(content.codePointCount(0, content.length()));
            partialReply.append(content);
        }

        private synchronized String partialReply() { return partialReply.toString(); }

        private synchronized void beginModelCall(LlmModelCatalogService.ModelConfig modelConfig,
                                                 String message, List<Map<String, String>> history) {
            int tokens = 256 + estimateTokenCount(message);
            for (Map<String, String> item : history) tokens += estimateTokenCount(item.get("content"));
            modelCallCount.incrementAndGet();
            estimatedInputTokens.addAndGet(tokens);
            estimatedInputCost += tokens * modelConfig.getInputPrice() / 1_000_000d;
            activeOutputPrice = modelConfig.getOutputPrice();
        }

        private synchronized int estimatedOutputTokens() { return estimateTokenCount(partialReply.toString()); }

        private synchronized double estimatedCost() {
            return estimatedInputCost + estimatedOutputTokens() * activeOutputPrice / 1_000_000d;
        }

        private static int estimateTokenCount(String value) {
            if (value == null || value.isEmpty()) return 0;
            double tokens = 0;
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                boolean cjk = script == Character.UnicodeScript.HAN
                        || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA
                        || script == Character.UnicodeScript.HANGUL;
                tokens += cjk ? 0.6d : 0.3d;
                offset += Character.charCount(codePoint);
            }
            return Math.max(1, (int) Math.ceil(tokens));
        }

        private long firstTokenMs() {
            long first = firstTokenNanos.get();
            return first == 0 ? 0 : Math.max(0, (first - startedAtNanos) / 1_000_000L);
        }

        private long totalMs() { return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L); }

        private String traceId() { return agentRequestId.isEmpty() ? "stream-" + streamId : agentRequestId; }

        private Map<String, Object> metrics() {
            Map<String, Object> result = new HashMap<>();
            result.put("streamId", streamId);
            result.put("firstTokenMs", firstTokenMs());
            result.put("totalMs", totalMs());
            result.put("eventCount", eventCount.get());
            result.put("charCount", charCount.get());
            result.put("status", cancelled.get() ? "cancelled" : "completed");
            return result;
        }
    }

    private DeepSeekMusicAgent.ReplyResult invokeAgent(String message, Integer userId,
                                                        List<Map<String, String>> history,
                                                        Long conversationId, String requestId,
                                                        LlmModelCatalogService.ModelConfig model,
                                                        Consumer<String> onDelta) {
        if (onDelta == null) {
            return deepSeekMusicAgent.replyWithResult(message, userId, history, conversationId, requestId,
                    model.getProvider(), model.getModel());
        }
        return deepSeekMusicAgent.replyWithResult(message, userId, history, conversationId, requestId,
                model.getProvider(), model.getModel(), onDelta);
    }

    private void recordModelAttempt(LlmModelCatalogService.ModelConfig model,
                                    DeepSeekMusicAgent.ReplyResult attempt) {
        promptOnlineMetricsService.record(attempt.getPromptVersion(), model.getProvider(), model.getModel(),
                model.getInputPrice(), model.getOutputPrice(), attempt.getModelCalls(), attempt.isSuccess(),
                attempt.getInputTokens(), attempt.getOutputTokens(), attempt.getLatencyMs());
        if (attempt.getModelCalls() > 0) {
            llmModelCatalogService.recordInvocationHealth(model.getId(), attempt.isSuccess(),
                    attempt.getLatencyMs(), attempt.getFallbackReason());
        }
    }

    private String fallbackReasonLabel(String code) {
        if ("AUTHENTICATION_FAILED".equals(code)) return "认证失败";
        if ("MODEL_NOT_FOUND".equals(code)) return "模型不存在";
        if ("RATE_LIMITED".equals(code)) return "请求限流";
        if ("TIMEOUT".equals(code)) return "调用超时";
        if ("EMPTY_RESPONSE".equals(code)) return "模型返回空内容";
        return "模型服务异常";
    }

    @PutMapping("/messages/{messageId}/feedback")
    public Map<String, Object> savePromptFeedback(@PathVariable long messageId,
                                                   @RequestBody Map<String, Object> payload,
                                                   HttpServletRequest request) {
        try {
            Object data = promptFeedbackService.save(messageId, getUserId(request),
                    String.valueOf(payload.getOrDefault("rating", "")),
                    payload.get("reason") == null ? null : String.valueOf(payload.get("reason")));
            return result(200, "Feedback saved", data);
        } catch (IllegalArgumentException exception) {
            return result(400, exception.getMessage(), null);
        }
    }

    @DeleteMapping("/messages/{messageId}/feedback")
    public Map<String, Object> deletePromptFeedback(@PathVariable long messageId,
                                                     HttpServletRequest request) {
        try {
            promptFeedbackService.delete(messageId, getUserId(request));
            return result(200, "Feedback deleted", null);
        } catch (IllegalArgumentException exception) {
            return result(400, exception.getMessage(), null);
        }
    }

    @GetMapping("/conversations")
    public Map<String, Object> conversations(HttpServletRequest request) {
        return result(200, "success", assistantConversationMapper.selectByUserId(getUserId(request)));
    }

    @PostMapping("/conversations")
    public Map<String, Object> createConversation(HttpServletRequest request) {
        AssistantConversation conversation = new AssistantConversation();
        conversation.setUserId(getUserId(request));
        conversation.setTitle("新对话");
        conversation.setSelectedModelId(llmModelCatalogService.resolve(null).getId());
        assistantConversationMapper.insertConversation(conversation);
        AssistantMessage welcomeMessage = message(conversation.getId(), "assistant",
                "你好！我是你的歌库智能助手。我可以查询歌库歌曲、音乐类型、推荐内容和你的收藏。");
        assistantConversationMapper.insertMessage(welcomeMessage);
        return result(200, "Conversation created", conversation);
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return result(200, "success", llmModelCatalogService.listSelectable());
    }

    @PutMapping("/conversations/{conversationId}/model")
    public Map<String, Object> updateConversationModel(@PathVariable Long conversationId,
                                                        @RequestBody Map<String, Object> payload,
                                                        HttpServletRequest request) {
        try {
            LlmModelCatalogService.ModelConfig model = llmModelCatalogService.resolve(
                    payload.get("modelId") == null ? null : String.valueOf(payload.get("modelId")));
            int updated = assistantConversationMapper.updateSelectedModel(conversationId, getUserId(request), model.getId());
            return updated > 0 ? result(200, "Conversation model updated", model.getId())
                    : result(404, "Conversation not found", null);
        } catch (IllegalArgumentException exception) { return result(400, exception.getMessage(), null); }
    }

    @GetMapping("/conversations/{conversationId}")
    public Map<String, Object> conversation(@PathVariable Long conversationId, HttpServletRequest request) {
        AssistantConversation conversation = assistantConversationMapper.selectByIdAndUserId(conversationId, getUserId(request));
        if (conversation == null) return result(500, "Conversation not found", null);
        List<AssistantMessage> messages = assistantConversationMapper.selectMessagesByConversationId(conversationId);
        for (AssistantMessage item : messages) {
            if (item.getId() != null && "assistant".equals(item.getRole())) {
                item.setRecommendations(assistantConversationMapper.selectRecommendationsByMessageId(item.getId()));
            }
        }
        conversation.setMessages(messages);
        return result(200, "success", conversation);
    }

    private void persistRecommendations(AssistantMessage message, List<Audio> recommendations) {
        if (message == null || message.getId() == null || recommendations == null || recommendations.isEmpty()) return;
        java.util.LinkedHashSet<Integer> savedAudioIds = new java.util.LinkedHashSet<>();
        int sortOrder = 0;
        for (Audio audio : recommendations) {
            if (audio == null || audio.getId() == null || !savedAudioIds.add(audio.getId())) continue;
            assistantConversationMapper.insertMessageRecommendation(message.getId(), audio.getId(), sortOrder++);
        }
        message.setRecommendations(recommendations);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(@PathVariable Long conversationId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        int deleted = assistantConversationMapper.deleteByIdAndUserId(conversationId, userId);
        if (deleted > 0) agentMemoryClient.deleteConversationState(userId, conversationId);
        return deleted > 0 ? result(200, "Conversation deleted", null) : result(500, "Conversation not found", null);
    }

    @GetMapping("/memory/settings")
    public Map<String, Object> memorySettings(HttpServletRequest request) {
        Object data = agentMemoryClient.getSettings(getUserId(request));
        return data == null ? result(503, "Agent memory service unavailable", null) : result(200, "success", data);
    }

    @PutMapping("/memory/settings")
    public Map<String, Object> updateMemorySettings(@RequestBody Map<String, Object> payload,
                                                     HttpServletRequest request) {
        if (!payload.containsKey("enabled")) return result(500, "enabled is required", null);
        boolean enabled = Boolean.parseBoolean(String.valueOf(payload.get("enabled")));
        Object data = agentMemoryClient.setEnabled(getUserId(request), enabled);
        return data == null ? result(503, "Agent memory service unavailable", null) : result(200, "success", data);
    }

    @GetMapping("/memories")
    public Map<String, Object> memories(HttpServletRequest request) {
        Object data = agentMemoryClient.list(getUserId(request));
        return data == null ? result(503, "Agent memory service unavailable", null) : result(200, "success", data);
    }

    @PutMapping("/memories/{memoryId}")
    public Map<String, Object> updateMemory(@PathVariable Long memoryId, @RequestBody Map<String, Object> payload,
                                             HttpServletRequest request) {
        String content = String.valueOf(payload.getOrDefault("content", "")).trim();
        if (content.isEmpty() || content.length() > 500) return result(500, "Memory must contain 1 to 500 characters", null);
        Object data = agentMemoryClient.update(getUserId(request), memoryId, content);
        return data == null ? result(500, "Memory not found or memory service unavailable", null)
                : result(200, "Memory updated", data);
    }

    @DeleteMapping("/memories/{memoryId}")
    public Map<String, Object> deleteMemory(@PathVariable Long memoryId, HttpServletRequest request) {
        Object data = agentMemoryClient.delete(getUserId(request), memoryId);
        return data == null ? result(500, "Memory not found or memory service unavailable", null)
                : result(200, "Memory deleted", data);
    }

    @PostMapping("/memories/{memoryId}/reactivate")
    public Map<String, Object> reactivateMemory(@PathVariable Long memoryId, HttpServletRequest request) {
        Object data = agentMemoryClient.reactivate(getUserId(request), memoryId);
        return data == null ? result(500, "Memory not found or memory service unavailable", null)
                : result(200, "Memory reactivated", data);
    }

    @DeleteMapping("/memories")
    public Map<String, Object> clearMemories(HttpServletRequest request) {
        Object data = agentMemoryClient.clear(getUserId(request));
        return data == null ? result(503, "Agent memory service unavailable", null)
                : result(200, "Memories cleared", data);
    }

    @PostMapping("/conversations/{conversationId}/context")
    public Map<String, Object> updateContext(@PathVariable Long conversationId, @RequestBody Map<String, Object> payload,
                                             HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (assistantConversationMapper.selectByIdAndUserId(conversationId, userId) == null) return result(500, "Conversation not found", null);
        assistantConversationMapper.updateCurrentAudio(conversationId, userId, toInteger(payload.get("audioId")));
        return result(200, "Conversation context updated", null);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("configured", deepSeekMusicAgent.isExternalModelConfigured());
        data.put("provider", deepSeekMusicAgent.getConfiguredProviderName());
        data.put("model", deepSeekMusicAgent.getConfiguredModelName());
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", data);
        return result;
    }

    @GetMapping("/recommendations")
    public Map<String, Object> recommendations(@RequestParam(value = "message", required = false) String message,
                                               HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String token = request.getHeader("Authorization");
        String username = JwtUtil.getUsernameByToken(token);
        User user = userMapper.selectByUsername(username);
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", musicLibraryAgent.getRecommendationsForQuery(message, user.getId(), 6));
        return result;
    }

    private List<Map<String, String>> toHistory(List<AssistantMessage> messages) {
        List<Map<String, String>> history = new ArrayList<>();
        for (AssistantMessage item : messages) {
            Map<String, String> message = new HashMap<>();
            message.put("role", item.getRole());
            message.put("content", item.getContent());
            history.add(message);
        }
        return history;
    }

    private AssistantMessage message(Long conversationId, String role, String content) {
        AssistantMessage message = new AssistantMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private Integer getUserId(HttpServletRequest request) {
        String username = JwtUtil.getUsernameByToken(request.getHeader("Authorization"));
        User user = userMapper.selectByUsername(username);
        return user.getId();
    }

    private UserLlmQuotaService.Permit acquireQuota(Integer userId, boolean stream) {
        return userLlmQuotaService == null
                ? UserLlmQuotaService.Permit.none()
                : userLlmQuotaService.acquireRequest(userId, stream);
    }

    private void applyQuotaResponse(HttpServletResponse response,
                                    UserLlmQuotaService.QuotaExceededException exception) {
        if (response == null) return;
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(exception.getRetryAfterSeconds()));
    }

    private Map<String, Object> quotaResult(UserLlmQuotaService.QuotaExceededException exception) {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", exception.getReason());
        data.put("retryAfterSeconds", exception.getRetryAfterSeconds());
        data.put("limit", exception.getLimit());
        data.put("current", exception.getCurrent());
        return result(429, exception.getMessage(), data);
    }

    private Long toLong(Object value) {
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }

    private Integer toInteger(Object value) {
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }

    private String enrichWithCurrentAudio(String message, Integer audioId) {
        if (audioId == null) return message;
        Audio audio = audioMapper.selectById(audioId);
        if (audio == null) return message;
        String normalized = message.toLowerCase();
        boolean refersToCurrentSong = message.contains("这首") || message.contains("这歌") || message.contains("它")
                || message.contains("当前") || normalized.contains("this song") || normalized.contains("it");
        if (!refersToCurrentSong) return message;
        return message + "（当前会话歌曲：" + audio.getSongName() + " - " + audio.getSinger()
                + "，类型：" + (audio.getGenre() == null ? "其他" : audio.getGenre()) + "）";
    }

    private Map<String, Object> result(Integer code, String msg, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        result.put("data", data);
        return result;
    }
}
