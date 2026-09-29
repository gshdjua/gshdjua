package com.example.demo.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.demo.entity.AssistantMessage;
import com.example.demo.entity.Audio;
import com.example.demo.mapper.AssistantConversationMapper;
import com.example.demo.mapper.AudioMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable session snapshots and isolated model replay runs for administrators. */
@Service
public class ConversationReplayService {
    private final JdbcTemplate jdbc;
    private final AssistantConversationMapper conversationMapper;
    private final AudioMapper audioMapper;
    private final DeepSeekMusicAgent musicAgent;
    private final LlmModelCatalogService modelCatalog;
    private final ModelInvocationLogService invocationLog;

    public ConversationReplayService(JdbcTemplate jdbc, AssistantConversationMapper conversationMapper,
                                     AudioMapper audioMapper,
                                     DeepSeekMusicAgent musicAgent, LlmModelCatalogService modelCatalog,
                                     ModelInvocationLogService invocationLog) {
        this.jdbc = jdbc;
        this.conversationMapper = conversationMapper;
        this.audioMapper = audioMapper;
        this.musicAgent = musicAgent;
        this.modelCatalog = modelCatalog;
        this.invocationLog = invocationLog;
    }

    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversation_snapshot ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,source_conversation_id BIGINT NULL,source_user_id INT NOT NULL,"
                + "title VARCHAR(100) NOT NULL,selected_model_id VARCHAR(80) NULL,message_count INT NOT NULL,"
                + "snapshot_json LONGTEXT NOT NULL,checksum CHAR(64) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "KEY idx_conversation_snapshot_created(created_at),"
                + "KEY idx_conversation_snapshot_source(source_conversation_id,created_at),"
                + "FOREIGN KEY(source_conversation_id) REFERENCES assistant_conversation(id) ON DELETE SET NULL) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversation_replay_run ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,snapshot_id BIGINT NOT NULL,target_model_id VARCHAR(80) NOT NULL,"
                + "provider VARCHAR(40) NOT NULL DEFAULT 'none',model_name VARCHAR(120) NOT NULL DEFAULT 'none',"
                + "prompt_version VARCHAR(120) NOT NULL DEFAULT 'none',execution_path VARCHAR(30) NOT NULL DEFAULT 'local',"
                + "status VARCHAR(20) NOT NULL,input_tokens INT NOT NULL DEFAULT 0,output_tokens INT NOT NULL DEFAULT 0,"
                + "latency_ms INT NOT NULL DEFAULT 0,estimated_cost DECIMAL(16,8) NOT NULL DEFAULT 0,"
                + "similarity_score DECIMAL(8,6) NOT NULL DEFAULT 0,original_reply LONGTEXT NOT NULL,"
                + "replay_reply LONGTEXT NOT NULL,error_message VARCHAR(500) NOT NULL DEFAULT '',"
                + "trace_id VARCHAR(100) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "KEY idx_conversation_replay_snapshot(snapshot_id,created_at),"
                + "FOREIGN KEY(snapshot_id) REFERENCES conversation_snapshot(id) ON DELETE CASCADE) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public List<Map<String, Object>> listConversations(int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        return jdbc.queryForList("SELECT c.id,c.user_id userId,u.username,c.title,c.selected_model_id selectedModelId,"
                + "c.update_time updateTime,COUNT(m.id) messageCount,"
                + "SUM(CASE WHEN m.role='user' THEN 1 ELSE 0 END) questionCount "
                + "FROM assistant_conversation c JOIN user u ON u.id=c.user_id "
                + "LEFT JOIN assistant_message m ON m.conversation_id=c.id "
                + "GROUP BY c.id,c.user_id,u.username,c.title,c.selected_model_id,c.update_time "
                + "HAVING COUNT(m.id)>0 ORDER BY c.update_time DESC,c.id DESC LIMIT ?", safeLimit);
    }

    @Transactional
    public Map<String, Object> createSnapshot(long conversationId) {
        List<Map<String, Object>> conversations = jdbc.queryForList(
                "SELECT id,user_id userId,title,current_audio_id currentAudioId,"
                        + "selected_model_id selectedModelId,create_time createTime,update_time updateTime "
                        + "FROM assistant_conversation WHERE id=?", conversationId);
        if (conversations.isEmpty()) throw new IllegalArgumentException("会话不存在");
        Map<String, Object> conversation = conversations.get(0);
        List<AssistantMessage> messages = conversationMapper.selectMessagesByConversationId(conversationId);
        if (messages.isEmpty()) throw new IllegalArgumentException("空会话不能创建快照");

        JSONObject snapshot = new JSONObject(true);
        snapshot.put("schemaVersion", "musichub.session-snapshot.v1");
        snapshot.put("capturedAt", Instant.now().toString());
        JSONObject source = new JSONObject(true);
        source.put("conversationId", conversationId);
        source.put("userId", number(conversation.get("userId")).intValue());
        source.put("title", text(conversation.get("title")));
        source.put("currentAudioId", conversation.get("currentAudioId"));
        source.put("selectedModelId", text(conversation.get("selectedModelId")));
        source.put("createTime", dateText(conversation.get("createTime")));
        source.put("updateTime", dateText(conversation.get("updateTime")));
        snapshot.put("source", source);
        JSONArray snapshotMessages = new JSONArray();
        int sequence = 1;
        for (AssistantMessage message : messages) {
            JSONObject item = new JSONObject(true);
            item.put("sequence", sequence++);
            item.put("role", message.getRole());
            item.put("content", message.getContent());
            item.put("promptVersion", message.getPromptVersion() == null ? "" : message.getPromptVersion());
            item.put("createdAt", dateText(message.getCreateTime()));
            snapshotMessages.add(item);
        }
        snapshot.put("messages", snapshotMessages);
        String json = JSON.toJSONString(snapshot);
        String checksum = sha256(json);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversation_snapshot(source_conversation_id,source_user_id,title,selected_model_id,"
                            + "message_count,snapshot_json,checksum) VALUES(?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, conversationId);
            statement.setInt(2, number(conversation.get("userId")).intValue());
            statement.setString(3, limited(text(conversation.get("title")), 100));
            statement.setString(4, text(conversation.get("selectedModelId")));
            statement.setInt(5, messages.size());
            statement.setString(6, json);
            statement.setString(7, checksum);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("快照编号生成失败");
        return snapshotById(id.longValue());
    }

    public List<Map<String, Object>> listSnapshots(int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        return jdbc.queryForList("SELECT s.id,s.source_conversation_id sourceConversationId,s.source_user_id sourceUserId,"
                + "s.title,s.selected_model_id selectedModelId,s.message_count messageCount,s.checksum,s.created_at createdAt,"
                + "COUNT(r.id) replayCount,MAX(r.created_at) lastReplayAt "
                + "FROM conversation_snapshot s LEFT JOIN conversation_replay_run r ON r.snapshot_id=s.id "
                + "GROUP BY s.id,s.source_conversation_id,s.source_user_id,s.title,s.selected_model_id,"
                + "s.message_count,s.checksum,s.created_at ORDER BY s.created_at DESC,s.id DESC LIMIT ?", safeLimit);
    }

    public String exportSnapshot(long snapshotId) {
        Map<String, Object> row = rawSnapshot(snapshotId);
        verifyChecksum(row);
        JSONObject exported = new JSONObject(true);
        exported.put("exportFormat", "musichub.session-snapshot-export.v1");
        exported.put("snapshotId", snapshotId);
        exported.put("checksum", text(row.get("checksum")));
        exported.put("snapshot", JSON.parseObject(text(row.get("snapshot_json"))));
        return JSON.toJSONString(exported, true);
    }

    public Map<String, Object> replay(long snapshotId, String requestedModelId) {
        Map<String, Object> row = rawSnapshot(snapshotId);
        verifyChecksum(row);
        JSONObject snapshot = JSON.parseObject(text(row.get("snapshot_json")));
        JSONObject source = snapshot.getJSONObject("source");
        JSONArray messages = snapshot.getJSONArray("messages");
        ReplayInput input = replayInput(messages);
        String modelId = requestedModelId == null || requestedModelId.trim().isEmpty()
                ? source.getString("selectedModelId") : requestedModelId.trim();
        LlmModelCatalogService.ModelConfig model = null;
        String traceId = "replay-" + snapshotId + "-" + UUID.randomUUID();
        try {
            model = modelCatalog.resolve(modelId);
            Integer userId = source.getInteger("userId");
            String replayQuestion = enrichWithCurrentAudio(input.question, source.getInteger("currentAudioId"));
            DeepSeekMusicAgent.ReplyResult result = musicAgent.replyWithResult(replayQuestion, userId,
                    input.history, null, traceId, model.getProvider(), model.getModel());
            double cost = result.getInputTokens() * model.getInputPrice() / 1_000_000d
                    + result.getOutputTokens() * model.getOutputPrice() / 1_000_000d;
            double similarity = similarity(input.originalReply, result.getReply());
            jdbc.update("INSERT INTO conversation_replay_run(snapshot_id,target_model_id,provider,model_name,"
                            + "prompt_version,execution_path,status,input_tokens,output_tokens,latency_ms,estimated_cost,"
                            + "similarity_score,original_reply,replay_reply,error_message,trace_id) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshotId, model.getId(), result.getProvider(), result.getModel(), result.getPromptVersion(),
                    result.getExecutionPath(), "completed", result.getInputTokens(), result.getOutputTokens(),
                    result.getLatencyMs(), cost, similarity, input.originalReply, result.getReply(), "", traceId);
            try { invocationLog.record(userId, traceId, "", model, result, null, null, result); }
            catch (RuntimeException ignored) { /* Replay result remains useful if observability is unavailable. */ }
        } catch (RuntimeException exception) {
            String safeModelId = model == null ? limited(modelId, 80) : model.getId();
            jdbc.update("INSERT INTO conversation_replay_run(snapshot_id,target_model_id,provider,model_name,"
                            + "status,original_reply,replay_reply,error_message,trace_id) VALUES(?,?,?,?,?,?,?,?,?)",
                    snapshotId, safeModelId == null ? "" : safeModelId,
                    model == null ? "none" : model.getProvider(), model == null ? "none" : model.getModel(),
                    "failed", input.originalReply, "", limited(exception.getMessage(), 500), traceId);
        }
        return latestReplay(snapshotId);
    }

    public List<Map<String, Object>> listReplays(long snapshotId, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return jdbc.queryForList("SELECT id,snapshot_id snapshotId,target_model_id targetModelId,provider,"
                + "model_name model,prompt_version promptVersion,execution_path executionPath,status,input_tokens inputTokens,"
                + "output_tokens outputTokens,latency_ms latencyMs,estimated_cost estimatedCost,"
                + "similarity_score similarityScore,original_reply originalReply,replay_reply replayReply,"
                + "error_message errorMessage,trace_id traceId,created_at createdAt "
                + "FROM conversation_replay_run WHERE snapshot_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                snapshotId, safeLimit);
    }

    private Map<String, Object> snapshotById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,source_conversation_id sourceConversationId,"
                + "source_user_id sourceUserId,title,selected_model_id selectedModelId,message_count messageCount,"
                + "checksum,created_at createdAt FROM conversation_snapshot WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("快照不存在");
        return rows.get(0);
    }

    private Map<String, Object> rawSnapshot(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,snapshot_json,checksum FROM conversation_snapshot WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("快照不存在");
        return rows.get(0);
    }

    private void verifyChecksum(Map<String, Object> row) {
        String json = text(row.get("snapshot_json"));
        if (!sha256(json).equalsIgnoreCase(text(row.get("checksum")))) {
            throw new IllegalStateException("快照校验失败，内容可能已被修改");
        }
    }

    private Map<String, Object> latestReplay(long snapshotId) {
        List<Map<String, Object>> rows = listReplays(snapshotId, 1);
        if (rows.isEmpty()) throw new IllegalStateException("重放记录保存失败");
        return rows.get(0);
    }

    ReplayInput replayInput(JSONArray messages) {
        int userIndex = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equalsIgnoreCase(messages.getJSONObject(index).getString("role"))) {
                userIndex = index;
                break;
            }
        }
        if (userIndex < 0) throw new IllegalArgumentException("快照中没有可重新执行的用户问题");
        List<Map<String, String>> history = new ArrayList<>();
        for (int index = 0; index < userIndex; index++) {
            JSONObject item = messages.getJSONObject(index);
            String role = item.getString("role");
            if (!("user".equals(role) || "assistant".equals(role))) continue;
            Map<String, String> historyItem = new LinkedHashMap<>();
            historyItem.put("role", role);
            historyItem.put("content", item.getString("content"));
            history.add(historyItem);
        }
        String originalReply = "";
        for (int index = userIndex + 1; index < messages.size(); index++) {
            JSONObject item = messages.getJSONObject(index);
            if ("assistant".equalsIgnoreCase(item.getString("role"))) {
                originalReply = item.getString("content");
                break;
            }
        }
        return new ReplayInput(messages.getJSONObject(userIndex).getString("content"), history,
                originalReply == null ? "" : originalReply);
    }

    double similarity(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        if (left.equals(right)) return 1d;
        if (left.isEmpty() || right.isEmpty()) return 0d;
        Set<String> leftParts = bigrams(left);
        Set<String> rightParts = bigrams(right);
        Set<String> intersection = new HashSet<>(leftParts);
        intersection.retainAll(rightParts);
        Set<String> union = new HashSet<>(leftParts);
        union.addAll(rightParts);
        return union.isEmpty() ? 0d : (double) intersection.size() / union.size();
    }

    private Set<String> bigrams(String value) {
        String bounded = value.length() > 10000 ? value.substring(0, 10000) : value;
        Set<String> result = new HashSet<>();
        if (bounded.length() == 1) result.add(bounded);
        for (int index = 0; index < bounded.length() - 1; index++) {
            result.add(bounded.substring(index, index + 2));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private String enrichWithCurrentAudio(String message, Integer audioId) {
        if (audioId == null || audioMapper == null) return message;
        Audio audio = audioMapper.selectById(audioId);
        if (audio == null) return message;
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean refersToCurrentSong = message != null && (message.contains("这首") || message.contains("这歌")
                || message.contains("它") || message.contains("当前")
                || normalized.contains("this song") || normalized.contains("it"));
        if (!refersToCurrentSong) return message;
        return message + "（当前会话歌曲：" + audio.getSongName() + " - " + audio.getSinger()
                + "，类型：" + (audio.getGenre() == null ? "其他" : audio.getGenre()) + "）";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("快照校验值生成失败", exception);
        }
    }

    private String dateText(Object value) {
        if (value == null) return "";
        if (value instanceof Date) return ((Date) value).toInstant().toString();
        return String.valueOf(value);
    }

    private Number number(Object value) {
        if (value instanceof Number) return (Number) value;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception exception) { return 0; }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String limited(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    static final class ReplayInput {
        private final String question;
        private final List<Map<String, String>> history;
        private final String originalReply;
        private ReplayInput(String question, List<Map<String, String>> history, String originalReply) {
            this.question = question;
            this.history = history;
            this.originalReply = originalReply;
        }
        String getQuestion() { return question; }
        List<Map<String, String>> getHistory() { return history; }
        String getOriginalReply() { return originalReply; }
    }
}
