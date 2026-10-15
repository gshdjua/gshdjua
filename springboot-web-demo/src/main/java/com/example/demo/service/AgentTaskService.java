package com.example.demo.service;

import com.example.demo.entity.AgentTaskPlan;
import com.example.demo.entity.Audio;
import com.example.demo.entity.UserPlaylist;
import com.example.demo.mapper.AgentTaskMapper;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.PlaylistMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentTaskService {
    private static final long CONFIRMATION_TTL_MS = 30L * 60L * 1000L;
    private static final Pattern QUOTED_NAME = Pattern.compile("[“\\\"「『]([^”\\\"」』]{1,80})[”\\\"」』](?:的)?歌单");
    private static final Pattern PLAIN_NAME = Pattern.compile("(?:名为|叫做|创建(?:一个|一份)?)([^，。！？\\s]{1,80})歌单");
    private static final Pattern ARABIC_COUNT = Pattern.compile("(?:加入|添加|推荐)?\\s*(\\d{1,2})\\s*首");

    @Autowired
    private AgentTaskMapper agentTaskMapper;

    @Autowired
    private PlaylistMapper playlistMapper;

    @Autowired
    private AudioMapper audioMapper;

    @Transactional
    public AgentTaskPlan createPlaylistPlan(Integer userId, Long conversationId, Long assistantMessageId,
                                             String userMessage, List<Audio> recommendations) {
        if (!isPlaylistCreationRequest(userMessage) || recommendations == null || recommendations.isEmpty()) {
            return null;
        }
        int requestedCount = requestedSongCount(userMessage, recommendations.size());
        Set<Integer> uniqueIds = new LinkedHashSet<>();
        List<Audio> songs = new ArrayList<>();
        for (Audio song : recommendations) {
            if (song == null || song.getId() == null || !uniqueIds.add(song.getId())) continue;
            songs.add(song);
            if (songs.size() >= requestedCount) break;
        }
        if (songs.isEmpty()) return null;

        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setUserId(userId);
        plan.setConversationId(conversationId);
        plan.setAssistantMessageId(assistantMessageId);
        plan.setActionType("create_playlist");
        plan.setPlaylistName(playlistName(userMessage));
        plan.setTitle("创建歌单“" + plan.getPlaylistName() + "”并加入 " + songs.size() + " 首歌曲");
        plan.setConfirmationToken(UUID.randomUUID().toString());
        plan.setStatus("pending");
        plan.setExpiresAt(new Date(System.currentTimeMillis() + CONFIRMATION_TTL_MS));
        agentTaskMapper.insert(plan);
        for (int index = 0; index < songs.size(); index++) {
            agentTaskMapper.insertSong(plan.getId(), songs.get(index).getId(), index);
        }
        plan.setSongs(songs);
        return plan;
    }

    public AgentTaskPlan findForMessage(Long messageId, Integer userId) {
        AgentTaskPlan plan = agentTaskMapper.selectByMessage(messageId, userId);
        return hydrateAndExpire(plan, userId);
    }

    @Transactional
    public AgentTaskPlan confirm(Long taskId, Integer userId, String token) {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("缺少确认令牌");
        if (agentTaskMapper.claim(taskId, userId, token.trim()) != 1) {
            AgentTaskPlan current = hydrateAndExpire(agentTaskMapper.selectOwned(taskId, userId), userId);
            if (current == null) throw new IllegalArgumentException("任务不存在或无权执行");
            if ("succeeded".equals(current.getStatus())) return current;
            throw new IllegalStateException(statusMessage(current.getStatus()));
        }

        AgentTaskPlan plan = agentTaskMapper.selectOwned(taskId, userId);
        List<Audio> songs = agentTaskMapper.selectSongs(taskId);
        if (songs.isEmpty()) throw new IllegalStateException("任务中没有可加入歌单的歌曲");

        UserPlaylist playlist = new UserPlaylist();
        playlist.setUserId(userId);
        playlist.setName(plan.getPlaylistName());
        playlistMapper.insert(playlist);
        int sortOrder = 1;
        for (Audio song : songs) {
            if (song.getId() == null || audioMapper.selectById(song.getId()) == null) {
                throw new IllegalStateException("候选歌曲已不存在，请重新生成计划");
            }
            playlistMapper.insertSong(playlist.getId(), song.getId(), sortOrder++);
        }
        playlistMapper.touch(playlist.getId());
        if (agentTaskMapper.markSucceeded(taskId, userId, playlist.getId()) != 1) {
            throw new IllegalStateException("任务状态发生变化，请刷新后查看");
        }
        return hydrateAndExpire(agentTaskMapper.selectOwned(taskId, userId), userId);
    }

    public AgentTaskPlan cancel(Long taskId, Integer userId) {
        int changed = agentTaskMapper.cancel(taskId, userId);
        AgentTaskPlan plan = hydrateAndExpire(agentTaskMapper.selectOwned(taskId, userId), userId);
        if (plan == null) throw new IllegalArgumentException("任务不存在或无权取消");
        if (changed == 0 && !"cancelled".equals(plan.getStatus())) {
            throw new IllegalStateException(statusMessage(plan.getStatus()));
        }
        return plan;
    }

    private AgentTaskPlan hydrateAndExpire(AgentTaskPlan plan, Integer userId) {
        if (plan == null) return null;
        if ("pending".equals(plan.getStatus()) && plan.getExpiresAt() != null
                && !plan.getExpiresAt().after(new Date())) {
            agentTaskMapper.expire(plan.getId(), userId);
            plan = agentTaskMapper.selectOwned(plan.getId(), userId);
        }
        if (plan != null) plan.setSongs(agentTaskMapper.selectSongs(plan.getId()));
        return plan;
    }

    static boolean isPlaylistCreationRequest(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return value.contains("歌单") && (value.contains("创建") || value.contains("新建")
                || value.contains("生成") || value.contains("建一个") || value.contains("建个"));
    }

    static String playlistName(String message) {
        String value = message == null ? "" : message.trim();
        for (Pattern pattern : new Pattern[]{QUOTED_NAME, PLAIN_NAME}) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                if (!name.isEmpty()) return name.substring(0, Math.min(name.length(), 80));
            }
        }
        return "AI 推荐歌单";
    }

    static int requestedSongCount(String message, int available) {
        Matcher arabic = ARABIC_COUNT.matcher(message == null ? "" : message);
        if (arabic.find()) return Math.max(1, Math.min(Math.min(Integer.parseInt(arabic.group(1)), 20), available));
        String[] chinese = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        String value = message == null ? "" : message;
        for (int index = 1; index < chinese.length; index++) {
            if (value.contains(chinese[index] + "首")) return Math.min(index, available);
        }
        return Math.min(5, available);
    }

    private String statusMessage(String status) {
        if ("expired".equals(status)) return "确认已过期，请重新生成任务";
        if ("cancelled".equals(status)) return "任务已经取消";
        if ("executing".equals(status)) return "任务正在执行，请勿重复提交";
        if ("succeeded".equals(status)) return "任务已经执行完成";
        return "任务当前不可执行";
    }
}
