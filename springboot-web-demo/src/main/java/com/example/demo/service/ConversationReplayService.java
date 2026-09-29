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
import org.springframework.dao.DataAccessException;
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
import java.util.concurrent.CompletableFuture;

/** Immutable session snapshots and isolated model replay runs for administrators. */
@Service
public class ConversationReplayService {
    private final JdbcTemplate jdbc;
    private final AssistantConversationMapper conversationMapper;
    private final AudioMapper audioMapper;
    private final DeepSeekMusicAgent musicAgent;
    private final LlmModelCatalogService modelCatalog;
    private final ModelInvocationLogService invocationLog;
    private final PromptVersionService promptVersionService;

    public ConversationReplayService(JdbcTemplate jdbc, AssistantConversationMapper conversationMapper,
                                     AudioMapper audioMapper,
                                     DeepSeekMusicAgent musicAgent, LlmModelCatalogService modelCatalog,
                                     ModelInvocationLogService invocationLog,
                                     PromptVersionService promptVersionService) {
        this.jdbc = jdbc;
        this.conversationMapper = conversationMapper;
        this.audioMapper = audioMapper;
        this.musicAgent = musicAgent;
        this.modelCatalog = modelCatalog;
        this.invocationLog = invocationLog;
        this.promptVersionService = promptVersionService;
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
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversation_replay_experiment ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,snapshot_id BIGINT NOT NULL,name VARCHAR(100) NOT NULL,"
                + "status VARCHAR(20) NOT NULL,model_ids_json LONGTEXT NOT NULL,prompt_versions_json LONGTEXT NOT NULL,"
                + "strategies_json LONGTEXT NOT NULL,total_runs INT NOT NULL DEFAULT 0,completed_runs INT NOT NULL DEFAULT 0,"
                + "failed_runs INT NOT NULL DEFAULT 0,total_input_tokens INT NOT NULL DEFAULT 0,"
                + "total_output_tokens INT NOT NULL DEFAULT 0,total_cost DECIMAL(16,8) NOT NULL DEFAULT 0,"
                + "average_latency_ms DECIMAL(12,2) NOT NULL DEFAULT 0,error_message VARCHAR(500) NOT NULL DEFAULT '',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMP NULL,"
                + "KEY idx_replay_experiment_snapshot(snapshot_id,created_at),"
                + "FOREIGN KEY(snapshot_id) REFERENCES conversation_snapshot(id) ON DELETE CASCADE) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("conversation_replay_run", "experiment_id", "BIGINT NULL");
        addColumnIfMissing("conversation_replay_run", "requested_prompt_version", "INT NULL");
        addColumnIfMissing("conversation_replay_run", "requested_strategy", "VARCHAR(20) NOT NULL DEFAULT 'auto'");
        addColumnIfMissing("conversation_replay_run", "selected_strategy", "VARCHAR(20) NOT NULL DEFAULT ''");
        addColumnIfMissing("conversation_replay_run", "strategy_reason", "VARCHAR(100) NOT NULL DEFAULT ''");
        jdbc.update("UPDATE conversation_replay_experiment SET status='interrupted',"
                + "error_message='服务重启导致评测中断',completed_at=CURRENT_TIMESTAMP WHERE status='running'");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", Integer.class, table, column);
            if (count == null || count == 0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (DataAccessException ignored) { /* Fresh schema already contains the evaluation columns. */ }
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
        return replayVariant(snapshotId, requestedModelId, null, "auto", null);
    }

    private Map<String, Object> replayVariant(long snapshotId, String requestedModelId, Integer promptVersion,
                                              String strategy, Long experimentId) {
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
            DeepSeekMusicAgent.ReplyResult result = promptVersion == null
                    ? musicAgent.replyWithResult(replayQuestion, userId, input.history, null, traceId,
                    model.getProvider(), model.getModel())
                    : musicAgent.replyForEvaluation(replayQuestion, userId, input.history, traceId,
                    model.getProvider(), model.getModel(), promptVersion, strategy);
            double cost = result.getInputTokens() * model.getInputPrice() / 1_000_000d
                    + result.getOutputTokens() * model.getOutputPrice() / 1_000_000d;
            double similarity = similarity(input.originalReply, result.getReply());
            String status = result.getModelCalls() > 0 && !result.isSuccess() ? "failed" : "completed";
            jdbc.update("INSERT INTO conversation_replay_run(snapshot_id,experiment_id,target_model_id,provider,model_name,"
                            + "prompt_version,requested_prompt_version,requested_strategy,selected_strategy,strategy_reason,"
                            + "execution_path,status,input_tokens,output_tokens,latency_ms,estimated_cost,"
                            + "similarity_score,original_reply,replay_reply,error_message,trace_id) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshotId, experimentId, model.getId(), result.getProvider(), result.getModel(), result.getPromptVersion(),
                    promptVersion, result.getRequestedStrategy(), result.getSelectedStrategy(), result.getStrategyReason(),
                    result.getExecutionPath(), status, result.getInputTokens(), result.getOutputTokens(),
                    result.getLatencyMs(), cost, similarity, input.originalReply, result.getReply(),
                    "failed".equals(status) ? limited(result.getFallbackReason(), 500) : "", traceId);
            try { invocationLog.record(userId, traceId, "", model, result, null, null, result); }
            catch (RuntimeException ignored) { /* Replay result remains useful if observability is unavailable. */ }
        } catch (RuntimeException exception) {
            String safeModelId = model == null ? limited(modelId, 80) : model.getId();
            jdbc.update("INSERT INTO conversation_replay_run(snapshot_id,experiment_id,target_model_id,provider,model_name,"
                            + "requested_prompt_version,requested_strategy,status,original_reply,replay_reply,error_message,trace_id) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshotId, experimentId, safeModelId == null ? "" : safeModelId,
                    model == null ? "none" : model.getProvider(), model == null ? "none" : model.getModel(),
                    promptVersion, normalizeStrategy(strategy), "failed", input.originalReply, "",
                    limited(exception.getMessage(), 500), traceId);
        }
        return replayByTrace(traceId);
    }

    public List<Map<String, Object>> listReplays(long snapshotId, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return jdbc.queryForList("SELECT id,snapshot_id snapshotId,experiment_id experimentId,target_model_id targetModelId,provider,"
                + "model_name model,prompt_version promptVersion,requested_prompt_version requestedPromptVersion,"
                + "requested_strategy requestedStrategy,selected_strategy selectedStrategy,strategy_reason strategyReason,"
                + "execution_path executionPath,status,input_tokens inputTokens,"
                + "output_tokens outputTokens,latency_ms latencyMs,estimated_cost estimatedCost,"
                + "similarity_score similarityScore,original_reply originalReply,replay_reply replayReply,"
                + "error_message errorMessage,trace_id traceId,created_at createdAt "
                + "FROM conversation_replay_run WHERE snapshot_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                snapshotId, safeLimit);
    }

    public Map<String, Object> createExperiment(long snapshotId, String name, List<String> modelIds,
                                                List<Integer> promptVersions, List<String> strategies) {
        Map<String, Object> snapshot = rawSnapshot(snapshotId);
        verifyChecksum(snapshot);
        List<String> models = distinctModels(modelIds);
        List<Integer> prompts = distinctPrompts(promptVersions);
        List<String> paths = distinctStrategies(strategies);
        int combinations = models.size() * prompts.size() * paths.size();
        if (combinations < 2) throw new IllegalArgumentException("至少选择两个模型、Prompt 或策略组合进行对比");
        if (combinations > 12) throw new IllegalArgumentException("单次最多评测 12 个组合");
        for (String modelId : models) modelCatalog.resolve(modelId);
        for (Integer version : prompts) promptVersionService.forEvaluation(version);
        String safeName = name == null || name.trim().isEmpty()
                ? "快照 #" + snapshotId + " 多版本对比" : limited(name.trim(), 100);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversation_replay_experiment(snapshot_id,name,status,model_ids_json,"
                            + "prompt_versions_json,strategies_json,total_runs) VALUES(?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, snapshotId);
            statement.setString(2, safeName);
            statement.setString(3, "running");
            statement.setString(4, JSON.toJSONString(models));
            statement.setString(5, JSON.toJSONString(prompts));
            statement.setString(6, JSON.toJSONString(paths));
            statement.setInt(7, combinations);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("评测任务编号生成失败");
        long experimentId = key.longValue();
        CompletableFuture.runAsync(() -> runExperiment(experimentId, snapshotId, models, prompts, paths));
        return experimentById(experimentId);
    }

    private void runExperiment(long experimentId, long snapshotId, List<String> models,
                               List<Integer> prompts, List<String> strategies) {
        try {
            for (String model : models) {
                for (Integer prompt : prompts) {
                    for (String strategy : strategies) {
                        replayVariant(snapshotId, model, prompt, strategy, experimentId);
                        refreshExperiment(experimentId, false, "");
                    }
                }
            }
            refreshExperiment(experimentId, true, "");
        } catch (RuntimeException exception) {
            refreshExperiment(experimentId, true, limited(exception.getMessage(), 500));
        }
    }

    private void refreshExperiment(long experimentId, boolean finished, String fatalError) {
        Map<String, Object> totals = jdbc.queryForMap("SELECT COUNT(*) completedRuns,"
                + "SUM(CASE WHEN status='failed' THEN 1 ELSE 0 END) failedRuns,"
                + "COALESCE(SUM(input_tokens),0) inputTokens,COALESCE(SUM(output_tokens),0) outputTokens,"
                + "COALESCE(SUM(estimated_cost),0) totalCost,COALESCE(AVG(latency_ms),0) averageLatency "
                + "FROM conversation_replay_run WHERE experiment_id=?", experimentId);
        String status = finished ? (fatalError.isEmpty() ? "completed" : "failed") : "running";
        jdbc.update("UPDATE conversation_replay_experiment SET status=?,completed_runs=?,failed_runs=?,"
                        + "total_input_tokens=?,total_output_tokens=?,total_cost=?,average_latency_ms=?,error_message=?,"
                        + "completed_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE completed_at END WHERE id=?",
                status, number(totals.get("completedRuns")).intValue(), number(totals.get("failedRuns")).intValue(),
                number(totals.get("inputTokens")).intValue(), number(totals.get("outputTokens")).intValue(),
                totals.get("totalCost"), totals.get("averageLatency"), fatalError, finished, experimentId);
    }

    public List<Map<String, Object>> listExperiments(long snapshotId) {
        return jdbc.queryForList("SELECT id,snapshot_id snapshotId,name,status,total_runs totalRuns,"
                + "completed_runs completedRuns,failed_runs failedRuns,total_input_tokens totalInputTokens,"
                + "total_output_tokens totalOutputTokens,total_cost totalCost,average_latency_ms averageLatencyMs,"
                + "error_message errorMessage,created_at createdAt,completed_at completedAt "
                + "FROM conversation_replay_experiment WHERE snapshot_id=? ORDER BY created_at DESC,id DESC", snapshotId);
    }

    public List<Map<String, Object>> listExperimentRuns(long experimentId) {
        return jdbc.queryForList("SELECT id,snapshot_id snapshotId,experiment_id experimentId,target_model_id targetModelId,"
                + "provider,model_name model,prompt_version promptVersion,requested_prompt_version requestedPromptVersion,"
                + "requested_strategy requestedStrategy,selected_strategy selectedStrategy,strategy_reason strategyReason,"
                + "execution_path executionPath,status,input_tokens inputTokens,output_tokens outputTokens,"
                + "latency_ms latencyMs,estimated_cost estimatedCost,similarity_score similarityScore,"
                + "original_reply originalReply,replay_reply replayReply,error_message errorMessage,trace_id traceId,"
                + "created_at createdAt FROM conversation_replay_run WHERE experiment_id=? ORDER BY id", experimentId);
    }

    public String exportExperiment(long experimentId) {
        Map<String, Object> experiment = experimentById(experimentId);
        JSONObject report = new JSONObject(true);
        report.put("exportFormat", "musichub.snapshot-comparison.v1");
        report.put("exportedAt", Instant.now().toString());
        report.put("experiment", experiment);
        report.put("runs", listExperimentRuns(experimentId));
        return JSON.toJSONString(report, true);
    }

    private Map<String, Object> experimentById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,snapshot_id snapshotId,name,status,"
                + "total_runs totalRuns,completed_runs completedRuns,failed_runs failedRuns,"
                + "total_input_tokens totalInputTokens,total_output_tokens totalOutputTokens,total_cost totalCost,"
                + "average_latency_ms averageLatencyMs,error_message errorMessage,created_at createdAt,"
                + "completed_at completedAt FROM conversation_replay_experiment WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("对比评测不存在");
        return rows.get(0);
    }

    private Map<String, Object> replayByTrace(String traceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,snapshot_id snapshotId,experiment_id experimentId,"
                + "target_model_id targetModelId,provider,model_name model,prompt_version promptVersion,"
                + "requested_prompt_version requestedPromptVersion,requested_strategy requestedStrategy,"
                + "selected_strategy selectedStrategy,strategy_reason strategyReason,execution_path executionPath,status,"
                + "input_tokens inputTokens,output_tokens outputTokens,latency_ms latencyMs,estimated_cost estimatedCost,"
                + "similarity_score similarityScore,original_reply originalReply,replay_reply replayReply,"
                + "error_message errorMessage,trace_id traceId,created_at createdAt "
                + "FROM conversation_replay_run WHERE trace_id=? LIMIT 1", traceId);
        if (rows.isEmpty()) throw new IllegalStateException("重放记录保存失败");
        return rows.get(0);
    }

    private List<String> distinctModels(List<String> values) {
        Set<String> unique = new java.util.LinkedHashSet<>();
        if (values != null) for (String value : values) if (value != null && !value.trim().isEmpty()) unique.add(value.trim());
        if (unique.isEmpty()) throw new IllegalArgumentException("请至少选择一个模型");
        return new ArrayList<>(unique);
    }

    private List<Integer> distinctPrompts(List<Integer> values) {
        Set<Integer> unique = new java.util.LinkedHashSet<>();
        if (values != null) for (Integer value : values) if (value != null && value > 0) unique.add(value);
        if (unique.isEmpty()) throw new IllegalArgumentException("请至少选择一个 Prompt 版本");
        return new ArrayList<>(unique);
    }

    private List<String> distinctStrategies(List<String> values) {
        Set<String> unique = new java.util.LinkedHashSet<>();
        if (values != null) for (String value : values) unique.add(normalizeStrategy(value));
        if (unique.isEmpty()) unique.add("auto");
        return new ArrayList<>(unique);
    }

    private String normalizeStrategy(String value) {
        String normalized = value == null ? "auto" : value.trim().toLowerCase(Locale.ROOT);
        return "direct".equals(normalized) || "react".equals(normalized) ? normalized : "auto";
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
