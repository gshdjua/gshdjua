package com.example.demo.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.HybridMusicRetriever;
import com.example.demo.service.retrieval.EntityQueryParser;
import com.example.demo.service.retrieval.EntityType;
import com.example.demo.service.retrieval.RetrievalResult;
import com.example.demo.service.retrieval.StrictEntityRetriever;
import com.example.demo.service.retrieval.StructuredEntityQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeepSeekMusicAgent {

    private static final int CONVERSATION_RETRIEVAL_TOP_K = 10;
    private static final int DEFAULT_RECOMMENDATION_EVIDENCE_TOP_K = 5;
    private static final int MAX_RECOMMENDATION_EVIDENCE_TOP_K = 12;
    private static final Pattern REQUESTED_SONG_COUNT = Pattern.compile("(\\d+)\\s*首");
    private static final String[] CHINESE_SONG_COUNTS = {
            "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"
    };
    private final ThreadLocal<ApiUsage> lastApiUsage = new ThreadLocal<>();
    private final ThreadLocal<AgentServiceClient.AgentResult> lastAgentResult = new ThreadLocal<>();
    private final ThreadLocal<MusicLibraryAgent.RecommendationOutcome> currentRecommendationOutcome = new ThreadLocal<>();
    private final ThreadLocal<Long> currentConversationId = new ThreadLocal<>();
    private final ThreadLocal<Integer> currentUserId = new ThreadLocal<>();
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();
    private final ThreadLocal<String> lastPromptVersion = new ThreadLocal<>();
    private final ThreadLocal<Integer> evaluationPromptVersion = new ThreadLocal<>();

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private MusicLibraryAgent musicLibraryAgent;

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    @Autowired
    private HybridMusicRetriever hybridMusicRetriever;

    @Autowired
    private EntityQueryParser entityQueryParser;

    @Autowired
    private StrictEntityRetriever strictEntityRetriever;

    @Autowired
    private AgentServiceClient agentServiceClient;

    @Autowired
    private PromptVersionService promptVersionService;

    public String reply(String message, Integer userId, List<Map<String, String>> history) {
        StructuredEntityQuery entityQuery = entityQueryParser.parse(message);
        if (entityQuery.isStrict()) {
            if (!entityQuery.hasEntity()) {
                return "我识别到你在查询本地是否收录某个实体，但没有识别出具体歌名、歌手、出处或类型，请补充完整名称。";
            }
            List<RetrievalResult> strictResults = strictEntityRetriever.retrieve(entityQuery, 10);
            if (strictResults.isEmpty()) {
                return "本地歌库未找到与“" + entityQuery.getEntityValue() + "”匹配的歌曲记录。";
            }
            List<Audio> strictSongs = new ArrayList<>();
            for (RetrievalResult result : strictResults) {
                Audio song = audioMapper.selectById(result.getAudioId());
                if (song != null) strictSongs.add(song);
            }
            return replyWithStrictEntityEvidence(message, history, entityQuery, strictSongs);
        }
        AssistantIntent intent = queryUnderstandingService.classify(message);
        String normalizedMessage = queryUnderstandingService.normalize(message);
        if (intent != AssistantIntent.RECOMMENDATION && asksForSourceSongList(normalizedMessage)) {
            List<Audio> exactSourceSongs = musicLibraryAgent.findExactSourceSongs(normalizedMessage, 5);
            if (!exactSourceSongs.isEmpty()) {
                return replyWithLocalEvidence(normalizedMessage, userId, history, exactSourceSongs,
                        "出处精确 SQL", AssistantIntent.SOURCE_QUERY);
            }
        }
        if (intent == AssistantIntent.RECOMMENDATION && isMoodQuestion(normalizedMessage)) {
            boolean asksForAlternatives = asksForAlternatives(normalizedMessage);
            Set<Integer> excludedAudioIds = asksForAlternatives ? previouslyRecommendedAudioIds(history) : new LinkedHashSet<>();
            int requestedCount = requestedRecommendationCount(normalizedMessage);
            MusicLibraryAgent.RecommendationOutcome outcome = musicLibraryAgent.getRecommendationOutcome(
                    normalizedMessage, userId, requestedCount, excludedAudioIds);
            currentRecommendationOutcome.set(outcome);
            List<Audio> recommendations = outcome.getSongs();
            if (asksForAlternatives && recommendations.isEmpty()) {
                return "我已排除本次对话中推荐过的歌曲，但歌库里暂时没有更多已确认符合“"
                        + moodLabel(normalizedMessage) + "”条件的歌曲。你可以换一种听感后再试。";
            }
            String retrievalMethod = isAnimeMoodQuestion(normalizedMessage)
                    ? "动画出处筛选 + 本地向量 RAG"
                    : "听感筛选 + 本地向量 RAG";
            return replyWithRecommendationEvidence(normalizedMessage, history, outcome, retrievalMethod);
        }
        if (intent == AssistantIntent.RECOMMENDATION) {
            int requestedCount = requestedRecommendationCount(normalizedMessage);
            MusicLibraryAgent.RecommendationOutcome outcome = musicLibraryAgent.getRecommendationOutcome(
                    normalizedMessage, userId, requestedCount, new LinkedHashSet<>());
            currentRecommendationOutcome.set(outcome);
            return replyWithRecommendationEvidence(normalizedMessage, history, outcome, "个性化推荐与类型筛选");
        }
        if (intent == AssistantIntent.FAVORITES || intent == AssistantIntent.GENRE_QUERY || intent == AssistantIntent.LIBRARY_QUERY
                || intent == AssistantIntent.SOURCE_QUERY) {
            return musicLibraryAgent.reply(normalizedMessage, userId, intent);
        }
        if (intent == AssistantIntent.ASSISTANT_INFO) {
            String modelName = getFirstConfigOrDefault("DEEPSEEK_MODEL", "OPENAI_MODEL", "deepseek-chat");
            return getApiKey().isEmpty()
                    ? "我是 MusicHub 歌库智能助手，目前使用本地歌库数据回答问题，尚未配置外部模型。"
                    : "我是 MusicHub 歌库智能助手。本地查询由歌库数据库完成，歌曲背景类问题由 " + modelName + " 辅助回答。";
        }
        message = normalizedMessage;
        if (asksAboutAssistant(message)) {
            String modelName = getFirstConfigOrDefault("DEEPSEEK_MODEL", "OPENAI_MODEL", "deepseek-chat");
            if (getApiKey().isEmpty()) return "我是 MusicHub 歌库智能助手。目前使用本地歌库数据回答问题，尚未配置外部模型。";
            return "我是 MusicHub 歌库智能助手。歌库查询使用本地数据库；歌曲背景、发行信息和出处等问题由 " + modelName + " 提供辅助回答。";
        }
        if (intent != AssistantIntent.SONG_METADATA && !shouldUseRemoteModel(message, history)) {
            return musicLibraryAgent.reply(message, userId);
        }
        if (getApiKey().isEmpty()) return "DeepSeek 尚未配置：后端没有读取到 OPENAI_API_KEY 或 DEEPSEEK_API_KEY。请在 springboot-web-demo/.env 中填写 Key 后重启服务。";

        EvidenceContext evidenceContext = songContext(message, history, intent);
        try {
            String answer = requestDeepSeek(message, evidenceContext, history);
            return answer.isEmpty() ? ensureEvidenceReferences(modelFallback(message, userId), evidenceContext) : answer;
        } catch (Exception exception) {
            return ensureEvidenceReferences(modelFallback(message, userId), evidenceContext);
        }
    }

    public String reply(String message, Integer userId, List<Map<String, String>> history, Long conversationId) {
        return reply(message, userId, history, conversationId, null);
    }

    public String reply(String message, Integer userId, List<Map<String, String>> history, Long conversationId,
                        String requestId) {
        return replyWithResult(message, userId, history, conversationId, requestId).getReply();
    }

    public ReplyResult replyWithResult(String message, Integer userId, List<Map<String, String>> history,
                                       Long conversationId, String requestId) {
        currentConversationId.set(conversationId);
        currentUserId.set(userId);
        currentRequestId.set(requestId);
        currentRecommendationOutcome.remove();
        lastPromptVersion.remove();
        try {
            String reply = reply(message, userId, history);
            return new ReplyResult(reply, currentRecommendationOutcome.get(),
                    lastPromptVersion.get() == null ? "none" : lastPromptVersion.get());
        } finally {
            currentConversationId.remove();
            currentUserId.remove();
            currentRequestId.remove();
            currentRecommendationOutcome.remove();
            lastApiUsage.remove();
            lastAgentResult.remove();
            lastPromptVersion.remove();
        }
    }

    private String replyWithRecommendationEvidence(String message, List<Map<String, String>> history,
                                                   MusicLibraryAgent.RecommendationOutcome outcome,
                                                   String retrievalMethod) {
        List<Audio> songs = outcome.getSongs();
        boolean modelJudgesMood = isAnimeMoodQuestion(message);
        String localAnswer = modelJudgesMood
                ? unverifiedMoodCandidateReply(outcome) : musicLibraryAgent.formatRecommendationReply(message, outcome);
        if (songs.isEmpty() || getApiKey().isEmpty() || (outcome.hasShortfall() && !modelJudgesMood)) {
            if (modelJudgesMood) currentRecommendationOutcome.remove();
            return localAnswer;
        }
        EvidenceContext evidenceContext = buildSongEvidenceContext(
                retrievalMethod, songs, outcome.getRequestedCount());
        try {
            String answer = requestDeepSeek(message, evidenceContext, history);
            if (answer.isEmpty() || (!modelJudgesMood && !mentionsEverySong(answer, songs))
                    || mentionsUnselectedSong(answer, songs)) {
                if (modelJudgesMood) currentRecommendationOutcome.remove();
                return localAnswer;
            }
            if (modelJudgesMood) currentRecommendationOutcome.set(recommendationsMentionedInAnswer(outcome, answer));
            return answer;
        } catch (Exception exception) {
            if (modelJudgesMood) currentRecommendationOutcome.remove();
            return localAnswer;
        }
    }

    private String unverifiedMoodCandidateReply(MusicLibraryAgent.RecommendationOutcome outcome) {
        List<Audio> songs = outcome.getSongs();
        if (songs.isEmpty()) return "本地歌库没有找到可核实动漫出处的候选歌曲，无法确认有符合轻松听感的歌曲。";
        return "本地歌库找到 " + songs.size() + " 首有动漫类型和出处证据的候选："
                + songs.stream().map(song -> "《" + song.getSongName() + "》- " + song.getSinger())
                .collect(Collectors.joining("；"))
                + "。当前无法核实这些歌曲是否符合轻松听感，因此不把它们算作已确认的推荐。";
    }

    MusicLibraryAgent.RecommendationOutcome recommendationsMentionedInAnswer(
            MusicLibraryAgent.RecommendationOutcome candidates, String answer) {
        String normalized = answer.toLowerCase(Locale.ROOT);
        List<Audio> mentioned = candidates.getSongs().stream()
                .filter(song -> song.getSongName() != null && !song.getSongName().isEmpty()
                        && normalized.contains(song.getSongName().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        return new MusicLibraryAgent.RecommendationOutcome(candidates.getRequestedCount(),
                mentioned.size(), candidates.getFavoriteExcludedCount(),
                mentioned.size() < candidates.getRequestedCount() ? "INSUFFICIENT_MATCHES" : "", mentioned);
    }

    private boolean mentionsEverySong(String answer, List<Audio> songs) {
        if (answer == null) return false;
        String normalized = answer.toLowerCase();
        for (Audio song : songs) {
            String songName = song.getSongName() == null ? "" : song.getSongName().toLowerCase();
            if (songName.isEmpty() || !normalized.contains(songName)) return false;
        }
        return true;
    }

    boolean mentionsUnselectedSong(String answer, List<Audio> selectedSongs) {
        if (answer == null || answer.isEmpty()) return false;
        Set<Integer> selectedIds = new LinkedHashSet<>();
        for (Audio selected : selectedSongs) selectedIds.add(selected.getId());
        String normalizedAnswer = answer.toLowerCase(Locale.ROOT);
        for (Audio song : audioMapper.selectAll()) {
            String songName = song.getSongName();
            if (!selectedIds.contains(song.getId()) && songName != null && songName.length() >= 3
                    && normalizedAnswer.contains(songName.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String replyWithStrictEntityEvidence(String message, List<Map<String, String>> history,
                                                 StructuredEntityQuery entityQuery, List<Audio> songs) {
        String localAnswer = strictEntityLocalAnswer(entityQuery, songs);
        if (songs.isEmpty() || getApiKey().isEmpty()) return localAnswer;
        EvidenceContext evidenceContext = buildSongEvidenceContext(
                "严格实体 SQL（" + entityQuery.getEntityType().name() + "）", songs,
                strictEntityEvidenceLimit(entityQuery.getEntityType()));
        try {
            String answer = requestDeepSeek(message, evidenceContext, history);
            return answer.isEmpty() ? unavailableModelFallback(localAnswer, evidenceContext) : answer;
        } catch (Exception exception) {
            return unavailableModelFallback(localAnswer, evidenceContext);
        }
    }

    private String strictEntityLocalAnswer(StructuredEntityQuery entityQuery, List<Audio> songs) {
        StringBuilder answer = new StringBuilder("本地歌库找到与“")
                .append(entityQuery.getEntityValue()).append("”严格匹配的歌曲：");
        for (int index = 0; index < songs.size(); index++) {
            if (index > 0) answer.append("；");
            Audio song = songs.get(index);
            answer.append("《").append(song.getSongName()).append("》- ").append(song.getSinger());
        }
        return answer.append("。").toString();
    }

    private String replyWithLocalEvidence(String message, Integer userId, List<Map<String, String>> history,
                                          List<Audio> songs, String retrievalMethod, AssistantIntent fallbackIntent) {
        if (songs == null || songs.isEmpty()) return musicLibraryAgent.reply(message, userId, fallbackIntent);
        if (getApiKey().isEmpty()) return musicLibraryAgent.reply(message, userId, fallbackIntent);
        EvidenceContext evidenceContext = buildSongEvidenceContext(
                retrievalMethod, songs, evidenceLimitForIntent(fallbackIntent, message));
        try {
            String answer = requestDeepSeek(message, evidenceContext, history);
            return answer.isEmpty()
                    ? unavailableModelFallback(musicLibraryAgent.reply(message, userId, fallbackIntent), evidenceContext)
                    : answer;
        } catch (Exception exception) {
            return unavailableModelFallback(musicLibraryAgent.reply(message, userId, fallbackIntent), evidenceContext);
        }
    }

    private String unavailableModelFallback(String localAnswer, EvidenceContext evidenceContext) {
        return "Agent/DeepSeek 服务暂时不可用，以下回答由本地歌库生成：\n"
                + ensureEvidenceReferences(localAnswer, evidenceContext);
    }

    private boolean needsBackgroundIntroduction(String message) {
        if (message == null) return false;
        return message.contains("背景") || message.contains("介绍") || message.contains("故事")
                || message.contains("创作") || message.contains("含义") || message.contains("发行")
                || message.contains("发布") || message.contains("哪年") || message.contains("哪一年") || message.contains("年份")
                || message.contains("何时") || message.contains("什么时候")
                || message.toLowerCase().contains("background");
    }

    private boolean isRecommendationQuestion(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase();
        return message.contains("推荐") || message.contains("好听") || message.contains("听什么")
                || message.contains("听啥") || message.contains("热门") || message.contains("人气")
                || normalized.contains("recommend") || normalized.contains("popular");
    }

    private boolean asksForSourceSongList(String message) {
        if (message == null) return false;
        return message.contains("哪些") || message.contains("有哪些") || message.contains("有什么")
                || message.contains("歌曲") || message.contains("音乐") || message.contains("推荐") || message.endsWith("歌");
    }

    private boolean isAnimeMoodQuestion(String message) {
        return containsAny(message, "动画", "番剧", "动漫", "番")
                && containsAny(message, "轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "悲伤", "安静");
    }

    private boolean isMoodQuestion(String message) {
        return containsAny(message, "轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "悲伤", "安静");
    }

    private boolean asksForAlternatives(String message) {
        return containsAny(message, "别的", "其他", "另外", "换一些", "换点", "再来", "再推荐", "更多");
    }

    private Set<Integer> previouslyRecommendedAudioIds(List<Map<String, String>> history) {
        Set<Integer> result = new LinkedHashSet<>();
        if (history == null || history.isEmpty()) return result;
        for (Map<String, String> item : history) {
            if (!"assistant".equals(item.get("role"))) continue;
            String content = item.get("content");
            if (content == null || content.isEmpty()) continue;
            String normalizedContent = content.toLowerCase();
            for (Audio song : audioMapper.selectAll()) {
                String songName = song.getSongName() == null ? "" : song.getSongName().toLowerCase();
                if (!songName.isEmpty() && normalizedContent.contains(songName)) result.add(song.getId());
            }
        }
        return result;
    }

    private String moodLabel(String message) {
        for (String mood : new String[]{"轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "悲伤", "安静"}) {
            if (message.contains(mood)) return mood;
        }
        return "当前";
    }

    private boolean containsAny(String message, String... keywords) {
        if (message == null) return false;
        for (String keyword : keywords) {
            if (message.contains(keyword)) return true;
        }
        return false;
    }

    private boolean asksAboutAssistant(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase();
        return message.contains("你是什么模型") || message.contains("你是谁") || message.contains("你用什么模型") || message.contains("你调用")
                || normalized.contains("what model") || normalized.contains("who are you");
    }

    private boolean shouldUseRemoteModel(String message, List<Map<String, String>> history) {
        if (message == null) return false;
        if (message.contains("收藏") || message.contains("我的") || message.toLowerCase().contains("favorite") || isSensitiveContent(message)) return false;
        if (needsBackgroundIntroduction(message)) return true;
        String question = message.toLowerCase();
        for (Audio song : audioMapper.selectAll()) {
            if (question.contains(song.getSongName().toLowerCase()) || question.contains(song.getSinger().toLowerCase())) {
                return true;
            }
        }
        if (isFollowUpQuestion(message) && history != null && !history.isEmpty()) return true;
        return !isLocalDataQuestion(message);
    }

    private boolean isLocalDataQuestion(String message) {
        return message.contains("歌库有多少") || message.contains("多少首") || message.contains("歌曲数量")
                || message.contains("播放次数") || message.contains("播放记录") || message.contains("歌曲列表")
                || message.contains("类型") || message.contains("曲风") || message.contains("风格")
                || message.contains("分类") || message.toLowerCase().contains("genre");
    }

    public boolean isExternalModelConfigured() {
        return !getApiKey().isEmpty();
    }

    public String getConfiguredModelName() {
        return getFirstConfigOrDefault("DEEPSEEK_MODEL", "OPENAI_MODEL", "deepseek-chat");
    }

    private boolean isFollowUpQuestion(String message) {
        return message.contains("它") || message.contains("这首") || message.contains("那首")
                || message.contains("这个") || message.contains("再") || message.contains("详细")
                || message.contains("为什么") || message.contains("然后") || message.contains("呢");
    }

    private EvidenceContext songContext(String message, List<Map<String, String>> history, AssistantIntent intent) {
        List<RetrievalResult> results = hybridMusicRetriever.retrieve(
                message, history, CONVERSATION_RETRIEVAL_TOP_K);
        if (results.isEmpty()) return EvidenceContext.empty();
        int evidenceLimit = Math.min(evidenceLimitForIntent(intent, message), results.size());
        StringBuilder context = new StringBuilder("本地歌曲证据：\n");
        List<String> references = new ArrayList<>();
        for (int index = 0; index < evidenceLimit; index++) {
            RetrievalResult result = results.get(index);
            String citationId = result.getCitationId();
            context.append("[").append(citationId).append("] ")
                    .append(result.getEvidence())
                    .append("\n");
            Audio song = audioMapper.selectById(result.getAudioId());
            references.add(referenceLine(citationId, song, result.getSources().toString()));
        }
        return new EvidenceContext(context.toString(), references);
    }

    private EvidenceContext buildSongEvidenceContext(String retrievalMethod, List<Audio> relatedSongs, int evidenceTopK) {
        if (relatedSongs == null || relatedSongs.isEmpty()) return EvidenceContext.empty();
        StringBuilder context = new StringBuilder("本地歌曲证据：\n");
        List<String> references = new ArrayList<>();
        int evidenceLimit = Math.min(Math.max(1, evidenceTopK), relatedSongs.size());
        for (int index = 0; index < evidenceLimit; index++) {
            Audio song = relatedSongs.get(index);
            String citationId = "S" + (index + 1);
            context.append("[").append(citationId).append("] 歌曲：").append(song.getSongName())
                    .append("；歌手：").append(song.getSinger())
                    .append("；类型：").append(song.getGenre() == null || song.getGenre().trim().isEmpty() ? "其他" : song.getGenre())
                    .append("；出处：").append(song.getSource() == null || song.getSource().trim().isEmpty() ? "未填写" : song.getSource())
                    .append("；简介：").append(song.getIntroduction() == null || song.getIntroduction().trim().isEmpty() ? "未填写" : song.getIntroduction())
                    .append("\n");
            references.add(referenceLine(citationId, song, retrievalMethod));
        }
        return new EvidenceContext(context.toString(), references);
    }

    int evidenceLimitForIntent(AssistantIntent intent, String message) {
        if (intent == AssistantIntent.SONG_METADATA) return 1;
        if (intent == AssistantIntent.SOURCE_QUERY || intent == AssistantIntent.GENRE_QUERY) return 5;
        if (intent == AssistantIntent.RECOMMENDATION) return requestedRecommendationCount(message);
        return 3;
    }

    int strictEntityEvidenceLimit(EntityType entityType) {
        return entityType == EntityType.SONG ? 1 : 5;
    }

    private int requestedRecommendationCount(String message) {
        if (message != null) {
            Matcher matcher = REQUESTED_SONG_COUNT.matcher(message);
            if (matcher.find()) {
                try {
                    return Math.max(1, Math.min(MAX_RECOMMENDATION_EVIDENCE_TOP_K,
                            Integer.parseInt(matcher.group(1))));
                } catch (NumberFormatException ignored) {
                }
            }
            for (int index = CHINESE_SONG_COUNTS.length - 1; index >= 0; index--) {
                if (message.contains(CHINESE_SONG_COUNTS[index] + "首")) return index + 1;
            }
        }
        return DEFAULT_RECOMMENDATION_EVIDENCE_TOP_K;
    }

    private String requestDeepSeek(String message, EvidenceContext evidenceContext, List<Map<String, String>> history) throws Exception {
        String baseUrl = getFirstConfig("DEEPSEEK_BASE_URL", "OPENAI_BASE_URL");
        if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com";
        String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        String modelName = getFirstConfigOrDefault("DEEPSEEK_MODEL", "OPENAI_MODEL", "deepseek-chat");
        JSONArray messages = buildRequestMessages(message, evidenceContext, history);
        AgentServiceClient.AgentResult agentResult = agentServiceClient.chat(
                messages, modelName, 0.4, message, currentConversationId.get(), currentUserId.get(),
                currentRequestId.get(), lastPromptVersion.get());
        if (agentResult != null) {
            lastAgentResult.set(agentResult);
            lastApiUsage.set(new ApiUsage(agentResult.getInputTokens(), agentResult.getOutputTokens(), agentResult.getTotalTokens()));
            return ensureEvidenceReferences(agentResult.getAnswer(), evidenceContext);
        }

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelName);
        requestBody.put("temperature", 0.4);
        requestBody.put("messages", messages);

        lastApiUsage.remove();
        lastAgentResult.remove();
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Authorization", "Bearer " + getApiKey());
        try (OutputStream output = connection.getOutputStream()) {
            output.write(JSON.toJSONString(requestBody).getBytes(StandardCharsets.UTF_8));
        }

        InputStream responseStream = connection.getResponseCode() >= 200 && connection.getResponseCode() < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(responseStream);
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return "";

        JSONObject responseJson = JSON.parseObject(response);
        JSONObject usage = responseJson.getJSONObject("usage");
        if (usage != null) {
            lastApiUsage.set(new ApiUsage(
                    usage.getIntValue("prompt_tokens"),
                    usage.getIntValue("completion_tokens"),
                    usage.getIntValue("total_tokens")));
        }
        JSONArray choices = responseJson.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) return "";
        JSONObject firstChoice = choices.getJSONObject(0);
        JSONObject responseMessage = firstChoice.getJSONObject("message");
        String answer = responseMessage == null ? "" : responseMessage.getString("content");
        if (answer == null || answer.trim().isEmpty()) return "";
        return "Agent Service 当前不可用，已临时直连 DeepSeek；本轮长期记忆可能未保存。\n"
                + ensureEvidenceReferences(answer, evidenceContext);
    }

    private JSONArray buildRequestMessages(String message, EvidenceContext evidenceContext,
                                            List<Map<String, String>> history) {
        JSONArray messages = new JSONArray();
        appendAnswerSystemMessages(messages);
        appendConversationHistory(messages, history);
        messages.add(message("user", "用户问题：" + message + "\n\n本地歌库提供的最小歌曲元数据：\n" + evidenceContext.getPrompt()));
        return messages;
    }

    void appendAnswerSystemMessages(JSONArray messages) {
        messages.add(message("system", "安全规则：不得披露用户个人信息；本地歌库收录、歌曲名称、歌手、类型和出处只以本地证据为准；证据不足时明确说明，禁止编造歌曲或事实。"));
        Integer requestedVersion = evaluationPromptVersion.get();
        PromptVersionService.SelectedPrompt prompt = requestedVersion == null
                ? promptVersionService.currentAnswerPrompt(currentUserId.get())
                : promptVersionService.forEvaluation(requestedVersion);
        lastPromptVersion.set(prompt.getVersion());
        messages.add(message("system", prompt.getTemplate()));
        messages.add(message("system", "推荐规则：用户同时要求动漫出处与轻松听感时，只从本地提供的动漫候选中判断听感；轻松不是数据库必填标签，应依据候选简介谨慎判断。不要为凑够数量纳入不合适的歌曲；不足时说明只能确认几首。不要在最终回答中点名未推荐的候选歌曲。"));
    }

    public Map<String, Object> evaluateLlmCost(String message, Integer userId, List<Map<String, String>> history,
                                                boolean realCall, int expectedOutputTokens,
                                                double inputPricePerMillion, double outputPricePerMillion) {
        return evaluateLlmCost(message, userId, history, realCall, expectedOutputTokens,
                inputPricePerMillion, outputPricePerMillion, "production", "auto", "standard");
    }

    public Map<String, Object> evaluateLlmCost(String message, Integer userId, List<Map<String, String>> history,
                                                boolean realCall, int expectedOutputTokens,
                                                double inputPricePerMillion, double outputPricePerMillion,
                                                String executionTarget, String requestedStrategy, String costBudget) {
        return evaluateLlmCost(message, userId, history, realCall, expectedOutputTokens,
                inputPricePerMillion, outputPricePerMillion, executionTarget, requestedStrategy, costBudget,
                null, false);
    }

    public Map<String, Object> evaluateLlmCost(String message, Integer userId, List<Map<String, String>> history,
                                                boolean realCall, int expectedOutputTokens,
                                                double inputPricePerMillion, double outputPricePerMillion,
                                                String executionTarget, String requestedStrategy, String costBudget,
                                                Integer promptVersion, boolean includeAnswerPreview) {
        if (promptVersion != null && promptVersion < 1) throw new IllegalArgumentException("Prompt 版本号不合法");
        if (promptVersion != null && "agent_native".equalsIgnoreCase(executionTarget)) {
            throw new IllegalArgumentException("Agent 原生链路不使用 music_answer Prompt，不能指定版本");
        }
        try {
            currentUserId.set(userId);
            if (promptVersion != null) evaluationPromptVersion.set(promptVersion);
            return evaluateLlmCostInternal(message, userId, history, realCall, expectedOutputTokens,
                    inputPricePerMillion, outputPricePerMillion, executionTarget, requestedStrategy,
                    costBudget, includeAnswerPreview);
        } finally {
            currentUserId.remove();
            evaluationPromptVersion.remove();
            lastPromptVersion.remove();
            lastApiUsage.remove();
            lastAgentResult.remove();
        }
    }

    private Map<String, Object> evaluateLlmCostInternal(String message, Integer userId,
                                                         List<Map<String, String>> history, boolean realCall,
                                                         int expectedOutputTokens, double inputPricePerMillion,
                                                         double outputPricePerMillion, String executionTarget,
                                                         String requestedStrategy, String costBudget,
                                                         boolean includeAnswerPreview) {
        if ("agent_native".equalsIgnoreCase(executionTarget)) {
            return evaluateAgentNativeCost(message, userId, realCall, expectedOutputTokens,
                    inputPricePerMillion, outputPricePerMillion, requestedStrategy, costBudget);
        }
        long startTime = System.currentTimeMillis();
        CostPreview preview = buildCostPreview(message, userId, history);
        int inputTokens = preview.modelRequired
                ? estimateTokens(JSON.toJSONString(buildRequestMessages(preview.normalizedMessage, preview.evidenceContext, history)))
                : 0;
        int outputTokens = preview.modelRequired ? Math.max(1, expectedOutputTokens) : 0;
        boolean actualUsage = false;
        AgentServiceClient.AgentResult agentExecution = null;
        String answerPreview = "";
        if (realCall && preview.modelRequired && isExternalModelConfigured()) {
            lastApiUsage.remove();
            lastAgentResult.remove();
            String answer = reply(message, userId, history);
            if (includeAnswerPreview) answerPreview = answer;
            ApiUsage usage = lastApiUsage.get();
            agentExecution = lastAgentResult.get();
            if (usage != null) {
                inputTokens = usage.promptTokens;
                outputTokens = usage.completionTokens;
                actualUsage = true;
            }
            lastApiUsage.remove();
            lastAgentResult.remove();
        }
        int totalTokens = inputTokens + outputTokens;
        double estimatedCost = inputTokens / 1_000_000d * Math.max(0d, inputPricePerMillion)
                + outputTokens / 1_000_000d * Math.max(0d, outputPricePerMillion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluationVersion", "2.0");
        result.put("executionTarget", "production");
        result.put("intent", preview.intent.name());
        result.put("modelRequired", preview.modelRequired);
        result.put("modelConfigured", isExternalModelConfigured());
        result.put("realCallRequested", realCall);
        result.put("actualUsage", actualUsage);
        boolean modelExecutedOrEstimated = preview.modelRequired && (!realCall || actualUsage);
        result.put("executionPath", !preview.modelRequired ? "local" : agentExecution != null ? "agent"
                : !realCall ? "estimated_agent" : actualUsage ? "direct_model_fallback" : "model_unavailable");
        result.put("promptVersion", preview.modelRequired && (!realCall || actualUsage)
                ? lastPromptVersion.get() : "none");
        result.put("traceId", agentExecution == null ? "" : agentExecution.getTraceId());
        result.put("selectedStrategy", agentExecution == null ? (preview.modelRequired ? "direct" : "local") : agentExecution.getStrategy());
        result.put("strategyReason", agentExecution == null ? (preview.modelRequired ? "estimated" : "local_rule") : agentExecution.getStrategyReason());
        result.put("costBudget", agentExecution == null ? (preview.modelRequired ? "standard" : "none") : agentExecution.getCostBudget());
        result.put("modelCalls", agentExecution == null ? (modelExecutedOrEstimated ? 1 : 0) : agentExecution.getModelCalls());
        result.put("toolCalls", agentExecution == null ? 0 : agentExecution.getToolCalls());
        result.put("toolRounds", agentExecution == null ? 0 : agentExecution.getToolRounds());
        result.put("toolExecutions", agentExecution == null ? Collections.emptyList() : agentExecution.getToolExecutions());
        result.put("budgetExceeded", agentExecution != null && agentExecution.isBudgetExceeded());
        result.put("stopReason", agentExecution == null ? "" : agentExecution.getStopReason());
        result.put("finishReason", agentExecution == null ? (preview.modelRequired ? "estimated" : "local") : agentExecution.getFinishReason());
        result.put("retrievalTopK", CONVERSATION_RETRIEVAL_TOP_K);
        result.put("plannedEvidenceTopK", preview.plannedEvidenceTopK);
        result.put("evidenceCount", preview.evidenceCount);
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("totalTokens", totalTokens);
        result.put("estimatedCost", estimatedCost);
        result.put("elapsedMs", agentExecution == null ? System.currentTimeMillis() - startTime : agentExecution.getLatencyMs());
        if (includeAnswerPreview && realCall) result.put("answerPreview", answerPreview);
        lastPromptVersion.remove();
        return result;
    }

    private Map<String, Object> evaluateAgentNativeCost(String message, Integer userId, boolean realCall,
                                                         int expectedOutputTokens, double inputPricePerMillion,
                                                         double outputPricePerMillion, String requestedStrategy,
                                                         String costBudget) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> preview = agentServiceClient.previewStrategy(
                message, requestedStrategy, costBudget);
        String selectedStrategy = textValue(preview == null ? null : preview.get("selectedStrategy"),
                normalizeEvaluationStrategy(requestedStrategy));
        String strategyReason = textValue(preview == null ? null : preview.get("strategyReason"),
                preview == null ? "preview_unavailable" : "");
        String budget = textValue(preview == null ? null : preview.get("costBudget"),
                normalizeEvaluationBudget(costBudget));
        String plannedTool = textValue(preview == null ? null : preview.get("plannedTool"), "");
        AgentServiceClient.AgentResult execution = realCall
                ? agentServiceClient.chatNativeEvaluation(message, userId, requestedStrategy, costBudget)
                : null;

        boolean executed = execution != null;
        int inputTokens = executed ? execution.getInputTokens()
                : realCall ? 0 : estimateTokens("MusicHub Agent native evaluation " + message);
        int outputTokens = executed ? execution.getOutputTokens()
                : realCall ? 0 : Math.max(1, expectedOutputTokens);
        int totalTokens = inputTokens + outputTokens;
        int modelCalls = executed ? execution.getModelCalls()
                : realCall ? 0 : ("react".equals(selectedStrategy) ? 2 : 1);
        double estimatedCost = inputTokens / 1_000_000d * Math.max(0d, inputPricePerMillion)
                + outputTokens / 1_000_000d * Math.max(0d, outputPricePerMillion);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluationVersion", "2.0");
        result.put("executionTarget", "agent_native");
        result.put("promptVersion", "none");
        result.put("intent", "AGENT_NATIVE");
        result.put("modelRequired", true);
        result.put("modelConfigured", isExternalModelConfigured());
        result.put("realCallRequested", realCall);
        result.put("actualUsage", executed);
        result.put("executionPath", !realCall ? "estimated_agent_native"
                : executed ? "agent_native" : "agent_unavailable");
        result.put("traceId", executed ? execution.getTraceId() : "");
        result.put("selectedStrategy", executed ? execution.getStrategy() : selectedStrategy);
        result.put("strategyReason", executed ? execution.getStrategyReason() : strategyReason);
        result.put("costBudget", executed ? execution.getCostBudget() : budget);
        result.put("modelCalls", executed ? execution.getModelCalls() : modelCalls);
        result.put("toolCalls", executed ? execution.getToolCalls() : 0);
        result.put("toolRounds", executed ? execution.getToolRounds() : 0);
        result.put("toolExecutions", executed ? execution.getToolExecutions() : Collections.emptyList());
        result.put("plannedTool", plannedTool);
        result.put("budgetExceeded", executed && execution.isBudgetExceeded());
        result.put("stopReason", executed ? execution.getStopReason() : "");
        result.put("finishReason", executed ? execution.getFinishReason() : "estimated");
        result.put("retrievalTopK", 0);
        result.put("plannedEvidenceTopK", 0);
        result.put("evidenceCount", 0);
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("totalTokens", totalTokens);
        result.put("estimatedCost", estimatedCost);
        result.put("elapsedMs", executed ? execution.getLatencyMs() : System.currentTimeMillis() - startTime);
        return result;
    }

    private String normalizeEvaluationStrategy(String value) {
        String normalized = value == null ? "auto" : value.trim().toLowerCase();
        return "direct".equals(normalized) || "react".equals(normalized) ? normalized : "direct";
    }

    private String normalizeEvaluationBudget(String value) {
        String normalized = value == null ? "standard" : value.trim().toLowerCase();
        return "low".equals(normalized) || "high".equals(normalized) ? normalized : "standard";
    }

    private String textValue(Object value, String fallback) {
        String result = value == null ? "" : String.valueOf(value).trim();
        return result.isEmpty() ? fallback : result;
    }

    private CostPreview buildCostPreview(String message, Integer userId, List<Map<String, String>> history) {
        String normalized = queryUnderstandingService.normalize(message);
        StructuredEntityQuery entityQuery = entityQueryParser.parse(normalized);
        if (entityQuery.isStrict()) {
            List<RetrievalResult> strictResults = entityQuery.hasEntity()
                    ? strictEntityRetriever.retrieve(entityQuery, CONVERSATION_RETRIEVAL_TOP_K) : new ArrayList<>();
            List<Audio> songs = new ArrayList<>();
            for (RetrievalResult result : strictResults) {
                Audio song = audioMapper.selectById(result.getAudioId());
                if (song != null) songs.add(song);
            }
            int limit = strictEntityEvidenceLimit(entityQuery.getEntityType());
            return new CostPreview(normalized, AssistantIntent.SONG_METADATA, !songs.isEmpty(), limit,
                    Math.min(limit, songs.size()), buildSongEvidenceContext("严格实体 SQL", songs, limit));
        }
        AssistantIntent intent = queryUnderstandingService.classify(normalized);
        if (intent != AssistantIntent.RECOMMENDATION && asksForSourceSongList(normalized)) {
            List<Audio> exactSourceSongs = musicLibraryAgent.findExactSourceSongs(normalized, 5);
            if (!exactSourceSongs.isEmpty()) {
                int limit = evidenceLimitForIntent(AssistantIntent.SOURCE_QUERY, normalized);
                return new CostPreview(normalized, AssistantIntent.SOURCE_QUERY, true, limit,
                        Math.min(limit, exactSourceSongs.size()), buildSongEvidenceContext("出处精确 SQL", exactSourceSongs, limit));
            }
        }
        if (intent == AssistantIntent.RECOMMENDATION && isAnimeMoodQuestion(normalized)) {
            int limit = evidenceLimitForIntent(intent, normalized);
            MusicLibraryAgent.RecommendationOutcome outcome = musicLibraryAgent.getRecommendationOutcome(
                    normalized, userId, limit, new LinkedHashSet<>());
            List<Audio> songs = outcome.getSongs();
            return new CostPreview(normalized, intent, !songs.isEmpty(),
                    limit, Math.min(limit, songs.size()),
                    buildSongEvidenceContext("动画出处筛选 + 本地向量 RAG", songs, limit));
        }
        if (intent == AssistantIntent.RECOMMENDATION || intent == AssistantIntent.FAVORITES
                || intent == AssistantIntent.GENRE_QUERY || intent == AssistantIntent.LIBRARY_QUERY
                || intent == AssistantIntent.SOURCE_QUERY || intent == AssistantIntent.ASSISTANT_INFO) {
            return new CostPreview(normalized, intent, false, 0, 0, EvidenceContext.empty());
        }
        boolean modelRequired = intent == AssistantIntent.SONG_METADATA || shouldUseRemoteModel(normalized, history);
        if (!modelRequired) return new CostPreview(normalized, intent, false, 0, 0, EvidenceContext.empty());
        EvidenceContext context = songContext(normalized, history, intent);
        int limit = evidenceLimitForIntent(intent, normalized);
        return new CostPreview(normalized, intent, true, limit, countEvidence(context.getPrompt()), context);
    }

    private int countEvidence(String prompt) {
        if (prompt == null || prompt.isEmpty()) return 0;
        int count = 0;
        Matcher matcher = Pattern.compile("(?m)^\\[S\\d+\\]").matcher(prompt);
        while (matcher.find()) count++;
        return count;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value >= 0x2E80 && value <= 0x9FFF) cjk++;
            else if (!Character.isWhitespace(value)) other++;
        }
        return Math.max(1, cjk + (int) Math.ceil(other / 4d));
    }

    private String referenceLine(String citationId, Audio song, String retrievalMethod) {
        if (song == null) return "[" + citationId + "] 本地歌库记录；检索方式：" + retrievalMethod;
        String source = song.getSource() == null || song.getSource().trim().isEmpty() ? "未填写" : song.getSource();
        return "[" + citationId + "] 本地歌库：《" + song.getSongName() + "》 - " + song.getSinger()
                + "；出处：" + source + "；检索方式：" + retrievalMethod;
    }

    private String ensureEvidenceReferences(String answer, EvidenceContext evidenceContext) {
        return sanitizeUserFacingAnswer(answer);
    }

    String sanitizeUserFacingAnswer(String answer) {
        if (answer == null || answer.trim().isEmpty()) return "";
        String visibleAnswer = answer;
        int evidenceBlockIndex = visibleAnswer.indexOf("本地证据来源：");
        if (evidenceBlockIndex >= 0) visibleAnswer = visibleAnswer.substring(0, evidenceBlockIndex);
        visibleAnswer = visibleAnswer.replaceAll("\\s*\\[S\\d+\\]", "");
        visibleAnswer = visibleAnswer.replaceAll("(?m)^.*检索方式[:：].*(?:\\R|$)", "");
        visibleAnswer = visibleAnswer.replace("**", "");
        visibleAnswer = visibleAnswer.replaceAll("(?m)^[\\t ]*-\\s*", "");
        return visibleAnswer.trim();
    }

    boolean shouldShowEvidenceReferences(String answer) {
        if (answer == null || answer.trim().isEmpty()) return false;
        return !containsAny(answer,
                "本地歌库未检索到足够可靠的证据",
                "本地歌库未找到足够可靠的证据",
                "本地歌库未找到与",
                "本地歌库没有找到与",
                "本地未找到与",
                "本地未找到相关",
                "本地歌库中未找到",
                "本地歌库中没有找到",
                "本地未检索到",
                "不属于本地歌库证据",
                "候选未通过置信度阈值");
    }

    private void appendConversationHistory(JSONArray messages, List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) return;
        int startIndex = Math.max(0, history.size() - 8);
        for (int index = startIndex; index < history.size(); index++) {
            Map<String, String> item = history.get(index);
            String role = item.get("role");
            String content = item.get("content");
            if ((!("user".equals(role) || "assistant".equals(role))) || content == null || isSensitiveContent(content)) continue;
            messages.add(message(role, content.substring(0, Math.min(content.length(), 500))));
        }
    }

    private boolean isSensitiveContent(String content) {
        String normalized = content.toLowerCase();
        return normalized.contains("收藏") || normalized.contains("账号") || normalized.contains("密码")
                || normalized.contains("手机号") || normalized.contains("身份证") || normalized.contains("邮箱")
                || normalized.contains("住址") || normalized.contains("地址");
    }

    private JSONObject message(String role, String content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String getFirstConfigOrDefault(String firstKey, String secondKey, String defaultValue) {
        String value = getFirstConfig(firstKey, secondKey);
        return value.isEmpty() ? defaultValue : value;
    }

    private String getApiKey() {
        return getFirstConfig("DEEPSEEK_API_KEY", "OPENAI_API_KEY");
    }

    private String getFirstConfig(String firstKey, String secondKey) {
        String value = getConfig(firstKey);
        return value.isEmpty() ? getConfig(secondKey) : value;
    }

    private String getConfig(String key) {
        String environmentValue = System.getenv(key);
        if (environmentValue != null && !environmentValue.trim().isEmpty()) return normalizeConfigValue(environmentValue);
        File envFile = new File(".env");
        if (!envFile.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(envFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.trim();
                if (entry.startsWith("export ")) entry = entry.substring(7).trim();
                int separatorIndex = entry.indexOf('=');
                if (separatorIndex > 0 && key.equals(entry.substring(0, separatorIndex).trim())) {
                    return normalizeConfigValue(entry.substring(separatorIndex + 1));
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String normalizeConfigValue(String value) {
        String normalized = value.trim();
        if (normalized.length() >= 2 && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            return normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    public static class ReplyResult {
        private final String reply;
        private final MusicLibraryAgent.RecommendationOutcome recommendationOutcome;
        private final String promptVersion;

        public ReplyResult(String reply, MusicLibraryAgent.RecommendationOutcome recommendationOutcome) {
            this(reply, recommendationOutcome, "none");
        }

        public ReplyResult(String reply, MusicLibraryAgent.RecommendationOutcome recommendationOutcome, String promptVersion) {
            this.reply = reply;
            this.recommendationOutcome = recommendationOutcome;
            this.promptVersion = promptVersion;
        }

        public String getReply() { return reply; }
        public String getPromptVersion() { return promptVersion; }

        public List<Audio> getRecommendations() {
            return recommendationOutcome == null ? new ArrayList<>() : recommendationOutcome.getSongs();
        }

        public Map<String, Object> getRecommendationMetadata() {
            return recommendationOutcome == null ? new LinkedHashMap<>() : recommendationOutcome.toMetadata();
        }
    }

    private static class EvidenceContext {
        private final String prompt;
        private final List<String> references;

        private EvidenceContext(String prompt, List<String> references) {
            this.prompt = prompt;
            this.references = references;
        }

        private static EvidenceContext empty() {
            return new EvidenceContext("RAG 检索结果：候选歌曲未通过置信度阈值，本地歌库未找到足够可靠的歌曲记录。不得猜测或编造本地收录结果。", new ArrayList<>());
        }

        private String getPrompt() {
            return prompt;
        }

        private List<String> getReferences() {
            return references;
        }
    }

    private String modelFallback(String message, Integer userId) {
        return "Agent/DeepSeek 服务暂时不可用，以下回答由本地歌库生成：\n"
                + musicLibraryAgent.reply(message, userId);
    }

    private String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line);
        }
        return content.toString();
    }

    private static class CostPreview {
        private final String normalizedMessage;
        private final AssistantIntent intent;
        private final boolean modelRequired;
        private final int plannedEvidenceTopK;
        private final int evidenceCount;
        private final EvidenceContext evidenceContext;

        private CostPreview(String normalizedMessage, AssistantIntent intent, boolean modelRequired,
                            int plannedEvidenceTopK, int evidenceCount, EvidenceContext evidenceContext) {
            this.normalizedMessage = normalizedMessage;
            this.intent = intent;
            this.modelRequired = modelRequired;
            this.plannedEvidenceTopK = plannedEvidenceTopK;
            this.evidenceCount = evidenceCount;
            this.evidenceContext = evidenceContext;
        }
    }

    private static class ApiUsage {
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        private ApiUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }
}
