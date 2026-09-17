package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PromptVersionService {
    public static final String ANSWER_PROMPT = "music_answer";

    // Used only when the prompt table has not been initialized or MySQL is unavailable.
    public static final String FALLBACK_TEMPLATE = String.join("\n",
            "你是 MusicHub 的歌曲背景助手。只根据公开、可靠的常识介绍歌曲背景；不确定时必须说明不确定。回答使用简洁中文，不编造发行年份、创作经历或人物关系。不得要求或披露用户个人信息。",
            "当用户询问歌曲是否来自某部动漫、影视或游戏时，先直接给出“是”“不是”或“无法确认”的结论，再补充已知出处；不要改为介绍整个歌库，也不要回避问题。",
            "本地检索证据用于确认歌库是否收录、歌曲名称、歌手、类型、出处和简介。只有证据中出现的歌曲才能说成歌库已收录；没有证据必须明确说本地未找到。发行时间等未写入证据的公开背景信息仍需谨慎回答，不得编造。",
            "如果本地上下文明确写着未找到足够可靠的歌曲记录，说明候选未通过置信度阈值。此时必须拒绝依据本地歌库给出具体歌曲结论，并说明“本地歌库未检索到足够可靠的证据”，可以建议用户补充准确歌名、歌手、类型或出处。禁止用模型猜测填补本地检索结果。",
            "本地证据编号和检索方式仅供内部推理使用。面向用户回答时不得输出 [S1] 等证据编号，不得展示候选证据列表、融合分数、检索来源或检索方式。只使用与问题直接相关的歌曲证据组织自然、详细的回答，忽略仅因语义相似而召回但不匹配明确歌名、歌手或出处的候选。公开背景知识若不来自本地证据，应明确写为公开背景信息，不得伪装成本地事实。",
            "面向用户的回答必须使用干净的纯文本，不要使用 Markdown 粗体符号 **，也不要在段落或条目前添加减号。可直接使用“歌手与出处：”这类自然小标题。",
            "当用户要求推荐时，先说明推荐依据，再逐首列出“歌名 - 歌手”，并在有证据时补充类型、出处和一句简介。只介绍本地证据中的歌曲；出处未填写时要明确标注，不能自行猜测。用户说“别的、其他、再来、换一些”时，严禁重复对话中已经推荐的歌曲。");

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptVersionService.class);
    private final JdbcTemplate jdbc;

    public PromptVersionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedInitialVersion() {
        try {
            jdbc.update("INSERT IGNORE INTO prompt_version(name,version,template_text,status,applicable_strategy,published_at) "
                            + "VALUES(?,1,?,'published','production',CURRENT_TIMESTAMP)",
                    ANSWER_PROMPT, FALLBACK_TEMPLATE);
        } catch (DataAccessException exception) {
            LOGGER.warn("Could not initialize answer prompt; built-in fallback remains available: {}",
                    exception.getClass().getSimpleName());
        }
    }

    public SelectedPrompt currentAnswerPrompt() {
        return currentAnswerPrompt(null);
    }

    public SelectedPrompt currentAnswerPrompt(Integer userId) {
        SelectedPrompt baseline;
        try {
            List<SelectedPrompt> prompts = jdbc.query(
                    "SELECT version, template_text FROM prompt_version WHERE name=? AND status='published' LIMIT 1",
                    (rs, rowNum) -> new SelectedPrompt(rs.getString("template_text"),
                            "music_answer:v" + rs.getInt("version")), ANSWER_PROMPT);
            baseline = !prompts.isEmpty() && !prompts.get(0).template.trim().isEmpty()
                    ? prompts.get(0) : new SelectedPrompt(FALLBACK_TEMPLATE, "music_answer:fallback");
        } catch (DataAccessException exception) {
            LOGGER.warn("Published answer prompt unavailable; using built-in fallback: {}", exception.getClass().getSimpleName());
            return new SelectedPrompt(FALLBACK_TEMPLATE, "music_answer:fallback");
        }
        if (userId == null || "music_answer:fallback".equals(baseline.version)) return baseline;
        try {
            List<RolloutCandidate> candidates = jdbc.query(
                    "SELECT pv.version,pv.template_text,pr.traffic_percent FROM prompt_rollout pr "
                            + "JOIN prompt_version pv ON pv.id=pr.candidate_id "
                            + "WHERE pr.name=? AND pr.enabled=1 AND pv.status='gray' LIMIT 1",
                    (rs, rowNum) -> new RolloutCandidate(
                            new SelectedPrompt(rs.getString("template_text"), "music_answer:v" + rs.getInt("version")),
                            rs.getInt("traffic_percent")), ANSWER_PROMPT);
            if (!candidates.isEmpty() && !candidates.get(0).prompt.template.trim().isEmpty()
                    && stableBucket(userId) < candidates.get(0).percent) return candidates.get(0).prompt;
        } catch (DataAccessException exception) {
            LOGGER.warn("Prompt rollout unavailable; using published baseline: {}", exception.getClass().getSimpleName());
        }
        return baseline;
    }

    public SelectedPrompt forEvaluation(int version) {
        List<SelectedPrompt> prompts = jdbc.query(
                "SELECT version,template_text FROM prompt_version WHERE name=? AND version=? AND status<>'deleted'",
                (rs, rowNum) -> new SelectedPrompt(rs.getString("template_text"),
                        "music_answer:v" + rs.getInt("version")), ANSWER_PROMPT, version);
        if (prompts.isEmpty() || prompts.get(0).template.trim().isEmpty()) {
            throw new IllegalArgumentException("指定的 Prompt 版本不存在或已删除");
        }
        return prompts.get(0);
    }

    int stableBucket(Integer userId) {
        return Math.floorMod((ANSWER_PROMPT + ":" + userId).hashCode(), 100);
    }

    public Map<String, Object> rollout() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pr.enabled,pr.traffic_percent AS trafficPercent,pv.id AS candidateId,"
                        + "pv.version AS candidateVersion,base.version AS baselineVersion "
                        + "FROM prompt_rollout pr LEFT JOIN prompt_version pv ON pv.id=pr.candidate_id "
                        + "LEFT JOIN prompt_version base ON base.name=pr.name AND base.status='published' "
                        + "WHERE pr.name=?", ANSWER_PROMPT);
        if (!rows.isEmpty()) return rows.get(0);
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("enabled", false);
        empty.put("trafficPercent", 0);
        return empty;
    }

    @Transactional
    public Map<String, Object> startRollout(long candidateId, int percent) {
        if (percent < 1 || percent > 99) throw new IllegalArgumentException("灰度比例须在 1% 到 99% 之间");
        lockPromptName();
        if (rolloutEnabled()) throw new IllegalArgumentException("已有灰度发布在运行，请先停止");
        Map<String, Object> candidate = byId(candidateId);
        String status = String.valueOf(candidate.get("status"));
        if (!"draft".equals(status) && !"inactive".equals(status)) {
            throw new IllegalArgumentException("只能将草稿或已停用版本设为灰度候选");
        }
        List<Map<String, Object>> baseline = jdbc.queryForList(
                "SELECT id FROM prompt_version WHERE name=? AND status='published'", ANSWER_PROMPT);
        if (baseline.isEmpty()) throw new IllegalArgumentException("灰度发布前必须有一个已发布的基线版本");
        jdbc.update("UPDATE prompt_version SET status='gray' WHERE id=? AND name=? AND status IN ('draft','inactive')",
                candidateId, ANSWER_PROMPT);
        jdbc.update("INSERT INTO prompt_rollout(name,candidate_id,traffic_percent,enabled) VALUES(?,?,?,1) "
                        + "ON DUPLICATE KEY UPDATE candidate_id=VALUES(candidate_id),"
                        + "traffic_percent=VALUES(traffic_percent),enabled=1",
                ANSWER_PROMPT, candidateId, percent);
        return rollout();
    }

    @Transactional
    public Map<String, Object> stopRollout() {
        lockPromptName();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT candidate_id FROM prompt_rollout WHERE name=? AND enabled=1 FOR UPDATE", ANSWER_PROMPT);
        if (rows.isEmpty()) throw new IllegalArgumentException("当前没有运行中的灰度发布");
        Object candidateId = rows.get(0).get("candidate_id");
        jdbc.update("UPDATE prompt_rollout SET enabled=0,traffic_percent=0 WHERE name=?", ANSWER_PROMPT);
        if (candidateId != null) jdbc.update(
                "UPDATE prompt_version SET status='inactive' WHERE id=? AND name=? AND status='gray'",
                candidateId, ANSWER_PROMPT);
        return rollout();
    }

    private boolean rolloutEnabled() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT enabled FROM prompt_rollout WHERE name=? AND enabled=1", ANSWER_PROMPT);
        return !rows.isEmpty();
    }

    private void lockPromptName() {
        jdbc.queryForList("SELECT id FROM prompt_version WHERE name=? ORDER BY id LIMIT 1 FOR UPDATE", ANSWER_PROMPT);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id,name,version,template_text AS templateText,status,applicable_strategy AS applicableStrategy,"
                + "created_at AS createdAt,updated_at AS updatedAt,published_at AS publishedAt "
                + "FROM prompt_version WHERE name=? AND status<>'deleted' ORDER BY version DESC", ANSWER_PROMPT);
    }

    public void recordUsage(Long assistantMessageId, String promptVersion) {
        if (assistantMessageId == null) return;
        try {
            jdbc.update("INSERT INTO assistant_prompt_usage(assistant_message_id,prompt_version) VALUES(?,?)",
                    assistantMessageId, promptVersion == null ? "none" : promptVersion);
        } catch (DataAccessException exception) {
            LOGGER.warn("Could not record prompt version for assistant message {}: {}",
                    assistantMessageId, exception.getClass().getSimpleName());
        }
    }

    @Transactional
    public Map<String, Object> createDraft(String template) {
        validate(template);
        jdbc.queryForList("SELECT id FROM prompt_version WHERE name=? ORDER BY id LIMIT 1 FOR UPDATE", ANSWER_PROMPT);
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM prompt_version WHERE name=?",
                Integer.class, ANSWER_PROMPT);
        jdbc.update("INSERT INTO prompt_version(name,version,template_text,status,applicable_strategy) "
                + "VALUES(?,?,?,'draft','production')", ANSWER_PROMPT, version, template.trim());
        return byVersion(version);
    }

    public Map<String, Object> updateDraft(long id, String template) {
        validate(template);
        int changed = jdbc.update("UPDATE prompt_version SET template_text=? WHERE id=? AND name=? AND status='draft'",
                template.trim(), id, ANSWER_PROMPT);
        if (changed != 1) throw new IllegalArgumentException("只允许修改存在的草稿版本");
        return byId(id);
    }

    @Transactional
    public Map<String, Object> publish(long id) {
        lockPromptName();
        if (rolloutEnabled()) throw new IllegalArgumentException("灰度发布运行中，请先停止灰度再正式发布");
        Map<String, Object> target = byId(id);
        if ("published".equals(target.get("status"))) return target;
        if (!"draft".equals(target.get("status")) && !"inactive".equals(target.get("status"))) {
            throw new IllegalArgumentException("该版本不能发布");
        }
        // The generated active_name unique key in the schema also prevents two published versions.
        jdbc.update("UPDATE prompt_version SET status='inactive' WHERE name=? AND status='published'", ANSWER_PROMPT);
        jdbc.update("UPDATE prompt_version SET status='published',published_at=CURRENT_TIMESTAMP WHERE id=? AND name=?",
                id, ANSWER_PROMPT);
        return byId(id);
    }

    @Transactional
    public Map<String, Object> disable(long id) {
        lockPromptName();
        if (rolloutEnabled()) throw new IllegalArgumentException("灰度发布运行中，请先停止灰度再停用基线版本");
        int changed = jdbc.update("UPDATE prompt_version SET status='inactive' WHERE id=? AND name=? AND status='published'",
                id, ANSWER_PROMPT);
        if (changed != 1) throw new IllegalArgumentException("只有已发布版本可以停用");
        return byId(id);
    }

    @Transactional
    public Map<String, Object> delete(long id) {
        int changed = jdbc.update("UPDATE prompt_version SET status='deleted',template_text='' "
                        + "WHERE id=? AND name=? AND status IN ('draft','inactive')",
                id, ANSWER_PROMPT);
        if (changed != 1) throw new IllegalArgumentException("只能删除草稿或已停用版本；当前发布版本请先停用");
        // Retain the version number for existing audit labels and to prevent future reuse.
        return byId(id);
    }

    @Transactional
    public Map<String, Object> rollback(long id) {
        jdbc.queryForList("SELECT id FROM prompt_version WHERE name=? ORDER BY id LIMIT 1 FOR UPDATE", ANSWER_PROMPT);
        Map<String, Object> target = byId(id);
        if (!"inactive".equals(target.get("status"))) throw new IllegalArgumentException("只能回滚到已停用的历史版本");
        List<Map<String, Object>> active = jdbc.queryForList(
                "SELECT version FROM prompt_version WHERE name=? AND status='published'", ANSWER_PROMPT);
        if (active.isEmpty() || ((Number) target.get("version")).intValue() >= ((Number) active.get(0).get("version")).intValue()) {
            throw new IllegalArgumentException("只能回滚到更早的已发布版本");
        }
        return publish(id);
    }

    private Map<String, Object> byVersion(int version) {
        return one("SELECT id,name,version,template_text AS templateText,status,applicable_strategy AS applicableStrategy,"
                + "created_at AS createdAt,updated_at AS updatedAt,published_at AS publishedAt "
                + "FROM prompt_version WHERE name=? AND version=?", ANSWER_PROMPT, version);
    }

    private Map<String, Object> byId(long id) {
        return one("SELECT id,name,version,template_text AS templateText,status,applicable_strategy AS applicableStrategy,"
                + "created_at AS createdAt,updated_at AS updatedAt,published_at AS publishedAt "
                + "FROM prompt_version WHERE name=? AND id=?", ANSWER_PROMPT, id);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Prompt 版本不存在");
        return rows.get(0);
    }

    private void validate(String template) {
        if (template == null || template.trim().length() < 20 || template.length() > 12000) {
            throw new IllegalArgumentException("Prompt 模板长度须在 20 到 12000 字符之间");
        }
    }

    public static final class SelectedPrompt {
        private final String template;
        private final String version;

        public SelectedPrompt(String template, String version) {
            this.template = template;
            this.version = version;
        }

        public String getTemplate() { return template; }
        public String getVersion() { return version; }
    }

    private static final class RolloutCandidate {
        private final SelectedPrompt prompt;
        private final int percent;

        private RolloutCandidate(SelectedPrompt prompt, int percent) {
            this.prompt = prompt;
            this.percent = percent;
        }
    }
}
