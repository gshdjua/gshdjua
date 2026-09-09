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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ThreadLocal<Long> currentConversationId = new ThreadLocal<>();
    private final ThreadLocal<Integer> currentUserId = new ThreadLocal<>();
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

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
        List<Audio> exactSourceSongs = musicLibraryAgent.findExactSourceSongs(normalizedMessage, 5);
        if (asksForSourceSongList(normalizedMessage) && !exactSourceSongs.isEmpty()) {
            return replyWithLocalEvidence(normalizedMessage, userId, history, exactSourceSongs, "出处精确 SQL", AssistantIntent.SOURCE_QUERY);
        }
        if (intent == AssistantIntent.RECOMMENDATION && isAnimeMoodQuestion(normalizedMessage)) {
            boolean asksForAlternatives = asksForAlternatives(normalizedMessage);
            Set<Integer> excludedAudioIds = asksForAlternatives ? previouslyRecommendedAudioIds(history) : new LinkedHashSet<>();
            List<Audio> recommendations = musicLibraryAgent.getRecommendationsForQuery(normalizedMessage, userId, 5, excludedAudioIds);
            if (asksForAlternatives && recommendations.isEmpty()) {
                return "我已排除本次对话中推荐过的歌曲，但歌库里暂时没有更多已确认符合“"
                        + moodLabel(normalizedMessage) + "的动漫歌”条件的歌曲。你可以补充更多歌曲出处或简介后再试。";
            }
            return replyWithLocalEvidence(normalizedMessage, userId, history, recommendations, "动画出处筛选 + 本地向量 RAG", AssistantIntent.RECOMMENDATION);
        }
        if (intent == AssistantIntent.RECOMMENDATION || intent == AssistantIntent.FAVORITES
                || intent == AssistantIntent.GENRE_QUERY || intent == AssistantIntent.LIBRARY_QUERY
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
        currentConversationId.set(conversationId);
        currentUserId.set(userId);
        currentRequestId.set(requestId);
        try {
            return reply(message, userId, history);
        } finally {
            currentConversationId.remove();
            currentUserId.remove();
            currentRequestId.remove();
        }
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
            return answer.isEmpty() ? ensureEvidenceReferences(localAnswer, evidenceContext) : answer;
        } catch (Exception exception) {
            return ensureEvidenceReferences(localAnswer, evidenceContext);
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
                    ? ensureEvidenceReferences(musicLibraryAgent.reply(message, userId, fallbackIntent), evidenceContext)
                    : answer;
        } catch (Exception exception) {
            return ensureEvidenceReferences(musicLibraryAgent.reply(message, userId, fallbackIntent), evidenceContext);
        }
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
                currentRequestId.get());
        if (agentResult != null) {
            lastApiUsage.set(new ApiUsage(agentResult.getInputTokens(), agentResult.getOutputTokens(), agentResult.getTotalTokens()));
            return ensureEvidenceReferences(agentResult.getAnswer(), evidenceContext);
        }

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelName);
        requestBody.put("temperature", 0.4);
        requestBody.put("messages", messages);

        lastApiUsage.remove();
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
        return ensureEvidenceReferences(answer, evidenceContext);
    }

    private JSONArray buildRequestMessages(String message, EvidenceContext evidenceContext,
                                            List<Map<String, String>> history) {
        JSONArray messages = new JSONArray();
        messages.add(message("system", "你是 MusicHub 的歌曲背景助手。只根据公开、可靠的常识介绍歌曲背景；不确定时必须说明不确定。回答使用简洁中文，不编造发行年份、创作经历或人物关系。不得要求或披露用户个人信息。"));
        messages.add(message("system", "当用户询问歌曲是否来自某部动漫、影视或游戏时，先直接给出“是”“不是”或“无法确认”的结论，再补充已知出处；不要改为介绍整个歌库，也不要回避问题。"));
        messages.add(message("system", "本地检索证据用于确认歌库是否收录、歌曲名称、歌手、类型、出处和简介。只有证据中出现的歌曲才能说成歌库已收录；没有证据必须明确说本地未找到。发行时间等未写入证据的公开背景信息仍需谨慎回答，不得编造。"));
        messages.add(message("system", "如果本地上下文明确写着未找到足够可靠的歌曲记录，说明候选未通过置信度阈值。此时必须拒绝依据本地歌库给出具体歌曲结论，并说明“本地歌库未检索到足够可靠的证据”，可以建议用户补充准确歌名、歌手、类型或出处。禁止用模型猜测填补本地检索结果。"));
        messages.add(message("system", "本地证据编号和检索方式仅供内部推理使用。面向用户回答时不得输出 [S1] 等证据编号，不得展示候选证据列表、融合分数、检索来源或检索方式。只使用与问题直接相关的歌曲证据组织自然、详细的回答，忽略仅因语义相似而召回但不匹配明确歌名、歌手或出处的候选。公开背景知识若不来自本地证据，应明确写为公开背景信息，不得伪装成本地事实。"));
        messages.add(message("system", "面向用户的回答必须使用干净的纯文本，不要使用 Markdown 粗体符号 **，也不要在段落或条目前添加减号。可直接使用“歌手与出处：”这类自然小标题。"));
        messages.add(message("system", "当用户要求推荐时，先说明推荐依据，再逐首列出“歌名 - 歌手”，并在有证据时补充类型、出处和一句简介。只介绍本地证据中的歌曲；出处未填写时要明确标注，不能自行猜测。用户说“别的、其他、再来、换一些”时，严禁重复对话中已经推荐的歌曲。"));
        appendConversationHistory(messages, history);
        messages.add(message("user", "用户问题：" + message + "\n\n本地歌库提供的最小歌曲元数据：\n" + evidenceContext.getPrompt()));
        return messages;
    }

    public Map<String, Object> evaluateLlmCost(String message, Integer userId, List<Map<String, String>> history,
                                                boolean realCall, int expectedOutputTokens,
                                                double inputPricePerMillion, double outputPricePerMillion) {
        long startTime = System.currentTimeMillis();
        CostPreview preview = buildCostPreview(message, userId, history);
        int inputTokens = preview.modelRequired
                ? estimateTokens(JSON.toJSONString(buildRequestMessages(preview.normalizedMessage, preview.evidenceContext, history)))
                : 0;
        int outputTokens = preview.modelRequired ? Math.max(1, expectedOutputTokens) : 0;
        boolean actualUsage = false;
        String answer = "";
        if (realCall && preview.modelRequired && isExternalModelConfigured()) {
            lastApiUsage.remove();
            answer = reply(message, userId, history);
            ApiUsage usage = lastApiUsage.get();
            if (usage != null) {
                inputTokens = usage.promptTokens;
                outputTokens = usage.completionTokens;
                actualUsage = true;
            }
            lastApiUsage.remove();
        }
        int totalTokens = inputTokens + outputTokens;
        double estimatedCost = inputTokens / 1_000_000d * Math.max(0d, inputPricePerMillion)
                + outputTokens / 1_000_000d * Math.max(0d, outputPricePerMillion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", preview.intent.name());
        result.put("modelRequired", preview.modelRequired);
        result.put("modelConfigured", isExternalModelConfigured());
        result.put("realCallRequested", realCall);
        result.put("actualUsage", actualUsage);
        result.put("retrievalTopK", CONVERSATION_RETRIEVAL_TOP_K);
        result.put("plannedEvidenceTopK", preview.plannedEvidenceTopK);
        result.put("evidenceCount", preview.evidenceCount);
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("totalTokens", totalTokens);
        result.put("estimatedCost", estimatedCost);
        result.put("elapsedMs", System.currentTimeMillis() - startTime);
        result.put("answerPreview", answer.length() > 300 ? answer.substring(0, 300) + "…" : answer);
        return result;
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
        List<Audio> exactSourceSongs = musicLibraryAgent.findExactSourceSongs(normalized, 5);
        if (asksForSourceSongList(normalized) && !exactSourceSongs.isEmpty()) {
            int limit = evidenceLimitForIntent(AssistantIntent.SOURCE_QUERY, normalized);
            return new CostPreview(normalized, AssistantIntent.SOURCE_QUERY, true, limit,
                    Math.min(limit, exactSourceSongs.size()), buildSongEvidenceContext("出处精确 SQL", exactSourceSongs, limit));
        }
        if (intent == AssistantIntent.RECOMMENDATION && isAnimeMoodQuestion(normalized)) {
            int limit = evidenceLimitForIntent(intent, normalized);
            List<Audio> songs = musicLibraryAgent.getRecommendationsForQuery(normalized, userId, limit, new LinkedHashSet<>());
            return new CostPreview(normalized, intent, !songs.isEmpty(), limit, Math.min(limit, songs.size()),
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
        return "DeepSeek 暂时未能返回结果，已改用本地歌库回答：\n" + musicLibraryAgent.reply(message, userId);
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
