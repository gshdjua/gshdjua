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
    private static final Pattern RENAME_NAME = Pattern.compile("(?:重命名为|改名为|改成)[“\\\"「『]?([^”\\\"」』，。！？]{1,80})");
    private static final Pattern ARABIC_COUNT = Pattern.compile("(?:加入|添加|推荐)?\\s*(\\d{1,2})\\s*首");

    @Autowired
    private AgentTaskMapper agentTaskMapper;

    @Autowired
    private PlaylistMapper playlistMapper;

    @Autowired
    private AudioMapper audioMapper;

    @Transactional
    public AgentTaskPlan createPlan(Integer userId, Long conversationId, Long assistantMessageId,
                                    String userMessage, List<Audio> recommendations, Integer currentAudioId) {
        AgentTaskPlan playlistPlan = createPlaylistPlan(userId, conversationId, assistantMessageId,
                userMessage, recommendations);
        if (playlistPlan != null) return playlistPlan;
        String value = normalizedText(userMessage);
        if (value.isEmpty()) return null;

        if (isRenamePlaylistRequest(value)) {
            String newName = renamedPlaylistName(userMessage);
            UserPlaylist target = findMentionedPlaylist(userId, userMessage, newName);
            if (target == null || newName.isEmpty() || newName.equalsIgnoreCase(target.getName())) return null;
            return persistPlan(basePlan(userId, conversationId, assistantMessageId, "rename_playlist",
                    "将歌单“" + target.getName() + "”重命名为“" + newName + "”", newName, target.getId()),
                    new ArrayList<>());
        }
        if (isDeletePlaylistRequest(value)) {
            UserPlaylist target = findMentionedPlaylist(userId, userMessage, null);
            if (target == null) return null;
            return persistPlan(basePlan(userId, conversationId, assistantMessageId, "delete_playlist",
                    "删除歌单“" + target.getName() + "”", target.getName(), target.getId()), new ArrayList<>());
        }
        if (isAddToPlaylistRequest(value) || isRemoveFromPlaylistRequest(value)) {
            UserPlaylist target = findMentionedPlaylist(userId, userMessage, null);
            List<Audio> songs = resolveSongs(userMessage, recommendations, currentAudioId,
                    containsAny(value, "这些", "推荐的", "上面", "刚才"));
            if (target == null || songs.isEmpty()) return null;
            boolean remove = isRemoveFromPlaylistRequest(value);
            String action = remove ? "remove_from_playlist" : "add_to_playlist";
            String verb = remove ? "从歌单“" + target.getName() + "”移除 " : "向歌单“" + target.getName() + "”加入 ";
            return persistPlan(basePlan(userId, conversationId, assistantMessageId, action,
                    verb + songs.size() + " 首歌曲", target.getName(), target.getId()), songs);
        }
        if (isUnfavoriteRequest(value) || isFavoriteRequest(value)) {
            List<Audio> songs = resolveSongs(userMessage, recommendations, currentAudioId,
                    containsAny(value, "这首", "当前", "它", "这些", "推荐的", "上面", "刚才"));
            if (songs.isEmpty()) return null;
            boolean remove = isUnfavoriteRequest(value);
            String action = remove ? "unfavorite_songs" : "favorite_songs";
            String title = (remove ? "取消收藏 " : "收藏 ") + songs.size() + " 首歌曲";
            return persistPlan(basePlan(userId, conversationId, assistantMessageId, action, title, "", null), songs);
        }
        return null;
    }

    @Transactional
    public AgentTaskPlan createPlaylistPlan(Integer userId, Long conversationId, Long assistantMessageId,
                                             String userMessage, List<Audio> recommendations) {
        if (!isPlaylistCreationRequest(userMessage) || recommendations == null || recommendations.isEmpty()) return null;
        int requestedCount = requestedSongCount(userMessage, recommendations.size());
        List<Audio> songs = uniqueSongs(recommendations, requestedCount);
        if (songs.isEmpty()) return null;
        String name = playlistName(userMessage);
        return persistPlan(basePlan(userId, conversationId, assistantMessageId, "create_playlist",
                "创建歌单“" + name + "”并加入 " + songs.size() + " 首歌曲", name, null), songs);
    }

    public AgentTaskPlan findForMessage(Long messageId, Integer userId) {
        return hydrateAndExpire(agentTaskMapper.selectByMessage(messageId, userId), userId);
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
        if (plan == null) throw new IllegalArgumentException("任务不存在或无权执行");
        List<Audio> songs = agentTaskMapper.selectSongs(taskId);
        String action = plan.getActionType();
        Integer resultPlaylistId = plan.getTargetPlaylistId();
        String resultMessage;

        if ("create_playlist".equals(action)) {
            if (songs.isEmpty()) throw new IllegalStateException("任务中没有可加入歌单的歌曲");
            UserPlaylist playlist = new UserPlaylist();
            playlist.setUserId(userId);
            playlist.setName(plan.getPlaylistName());
            playlistMapper.insert(playlist);
            int sortOrder = 1;
            for (Audio song : requireExistingSongs(songs)) playlistMapper.insertSong(playlist.getId(), song.getId(), sortOrder++);
            playlistMapper.touch(playlist.getId());
            resultPlaylistId = playlist.getId();
            resultMessage = "已创建歌单“" + playlist.getName() + "”并加入 " + songs.size() + " 首歌曲";
        } else if ("favorite_songs".equals(action) || "unfavorite_songs".equals(action)) {
            boolean remove = "unfavorite_songs".equals(action);
            int changed = updateFavorites(userId, requireExistingSongs(songs), remove);
            resultPlaylistId = null;
            resultMessage = remove ? "已取消收藏 " + changed + " 首歌曲" : "已收藏 " + changed + " 首歌曲";
        } else if ("add_to_playlist".equals(action) || "remove_from_playlist".equals(action)) {
            UserPlaylist target = requireOwnedPlaylist(plan.getTargetPlaylistId(), userId);
            boolean remove = "remove_from_playlist".equals(action);
            int changed = updatePlaylistSongs(target.getId(), requireExistingSongs(songs), remove);
            resultPlaylistId = target.getId();
            resultMessage = remove ? "已从歌单“" + target.getName() + "”移除 " + changed + " 首歌曲"
                    : "已向歌单“" + target.getName() + "”加入 " + changed + " 首歌曲";
        } else if ("rename_playlist".equals(action)) {
            UserPlaylist target = requireOwnedPlaylist(plan.getTargetPlaylistId(), userId);
            if (playlistMapper.renameByIdAndUserId(target.getId(), userId, plan.getPlaylistName()) != 1) {
                throw new IllegalStateException("歌单重命名失败，请刷新后重试");
            }
            resultPlaylistId = target.getId();
            resultMessage = "已将歌单“" + target.getName() + "”重命名为“" + plan.getPlaylistName() + "”";
        } else if ("delete_playlist".equals(action)) {
            UserPlaylist target = requireOwnedPlaylist(plan.getTargetPlaylistId(), userId);
            if (playlistMapper.deleteByIdAndUserId(target.getId(), userId) != 1) {
                throw new IllegalStateException("歌单删除失败，请刷新后重试");
            }
            resultPlaylistId = null;
            resultMessage = "已删除歌单“" + target.getName() + "”";
        } else {
            throw new IllegalStateException("不支持的任务类型");
        }

        if (agentTaskMapper.markSucceeded(taskId, userId, resultPlaylistId, resultMessage) != 1) {
            throw new IllegalStateException("任务状态发生变化，请刷新后查看");
        }
        return hydrateAndExpire(agentTaskMapper.selectOwned(taskId, userId), userId);
    }

    @Transactional
    public AgentTaskPlan cancel(Long taskId, Integer userId) {
        int changed = agentTaskMapper.cancel(taskId, userId);
        AgentTaskPlan plan = hydrateAndExpire(agentTaskMapper.selectOwned(taskId, userId), userId);
        if (plan == null) throw new IllegalArgumentException("任务不存在或无权取消");
        if (changed == 0 && !"cancelled".equals(plan.getStatus())) throw new IllegalStateException(statusMessage(plan.getStatus()));
        return plan;
    }

    private AgentTaskPlan basePlan(Integer userId, Long conversationId, Long assistantMessageId,
                                   String actionType, String title, String playlistName, Integer targetPlaylistId) {
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setUserId(userId);
        plan.setConversationId(conversationId);
        plan.setAssistantMessageId(assistantMessageId);
        plan.setActionType(actionType);
        plan.setTitle(title);
        plan.setPlaylistName(playlistName == null ? "" : playlistName);
        plan.setTargetPlaylistId(targetPlaylistId);
        plan.setConfirmationToken(UUID.randomUUID().toString());
        plan.setStatus("pending");
        plan.setExpiresAt(new Date(System.currentTimeMillis() + CONFIRMATION_TTL_MS));
        return plan;
    }

    private AgentTaskPlan persistPlan(AgentTaskPlan plan, List<Audio> songs) {
        agentTaskMapper.insert(plan);
        for (int index = 0; index < songs.size(); index++) agentTaskMapper.insertSong(plan.getId(), songs.get(index).getId(), index);
        plan.setSongs(songs);
        return plan;
    }

    private int updateFavorites(Integer userId, List<Audio> songs, boolean remove) {
        int changed = 0;
        for (Audio song : songs) {
            boolean collected = audioMapper.isCollected(userId, song.getId()) > 0;
            if (remove && collected) {
                audioMapper.removeCollect(userId, song.getId());
                audioMapper.decrementCollect(song.getId());
                changed++;
            } else if (!remove && !collected) {
                audioMapper.addCollect(userId, song.getId());
                audioMapper.incrementCollect(song.getId());
                changed++;
            }
        }
        return changed;
    }

    private int updatePlaylistSongs(Integer playlistId, List<Audio> songs, boolean remove) {
        int changed = 0;
        int sortOrder = playlistMapper.selectMaxSortOrder(playlistId) + 1;
        for (Audio song : songs) {
            boolean exists = playlistMapper.countSong(playlistId, song.getId()) > 0;
            if (remove && exists) changed += playlistMapper.deleteSong(playlistId, song.getId());
            else if (!remove && !exists) {
                playlistMapper.insertSong(playlistId, song.getId(), sortOrder++);
                changed++;
            }
        }
        if (changed > 0) playlistMapper.touch(playlistId);
        return changed;
    }

    private List<Audio> requireExistingSongs(List<Audio> songs) {
        if (songs == null || songs.isEmpty()) throw new IllegalStateException("任务中没有可操作的歌曲");
        List<Audio> result = new ArrayList<>();
        for (Audio song : songs) {
            Audio existing = song == null || song.getId() == null ? null : audioMapper.selectById(song.getId());
            if (existing == null) throw new IllegalStateException("候选歌曲已不存在，请重新生成计划");
            result.add(existing);
        }
        return result;
    }

    private UserPlaylist requireOwnedPlaylist(Integer playlistId, Integer userId) {
        UserPlaylist playlist = playlistId == null ? null : playlistMapper.selectByIdAndUserId(playlistId, userId);
        if (playlist == null) throw new IllegalStateException("目标歌单不存在或不属于当前用户");
        return playlist;
    }

    private UserPlaylist findMentionedPlaylist(Integer userId, String message, String excludedName) {
        List<UserPlaylist> playlists = playlistMapper.selectByUserId(userId);
        if (playlists == null) return null;
        String normalizedMessage = normalizedText(message);
        for (UserPlaylist playlist : playlists) {
            if (playlist == null || playlist.getName() == null) continue;
            if (excludedName != null && excludedName.equalsIgnoreCase(playlist.getName())) continue;
            String name = normalizedText(playlist.getName());
            if (!name.isEmpty() && normalizedMessage.contains(name)) return playlist;
        }
        return null;
    }

    private List<Audio> resolveSongs(String message, List<Audio> recommendations, Integer currentAudioId,
                                     boolean allowContextSongs) {
        List<Audio> matches = new ArrayList<>();
        String normalizedMessage = normalizedText(message);
        List<Audio> catalog = audioMapper.selectAll();
        if (catalog != null) {
            for (Audio audio : catalog) {
                String name = audio == null ? "" : normalizedText(audio.getSongName());
                if (!name.isEmpty() && normalizedMessage.contains(name)) matches.add(audio);
            }
        }
        if (matches.isEmpty() && currentAudioId != null && containsAny(normalizedMessage, "这首", "当前", "它")) {
            Audio current = audioMapper.selectById(currentAudioId);
            if (current != null) matches.add(current);
        }
        if (matches.isEmpty() && allowContextSongs && recommendations != null) matches.addAll(recommendations);
        return uniqueSongs(matches, 20);
    }

    private List<Audio> uniqueSongs(List<Audio> songs, int limit) {
        Set<Integer> ids = new LinkedHashSet<>();
        List<Audio> result = new ArrayList<>();
        if (songs == null) return result;
        for (Audio song : songs) {
            if (song == null || song.getId() == null || !ids.add(song.getId())) continue;
            result.add(song);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private AgentTaskPlan hydrateAndExpire(AgentTaskPlan plan, Integer userId) {
        if (plan == null) return null;
        if ("pending".equals(plan.getStatus()) && plan.getExpiresAt() != null && !plan.getExpiresAt().after(new Date())) {
            agentTaskMapper.expire(plan.getId(), userId);
            plan = agentTaskMapper.selectOwned(plan.getId(), userId);
        }
        if (plan != null) plan.setSongs(agentTaskMapper.selectSongs(plan.getId()));
        return plan;
    }

    static boolean isPlaylistCreationRequest(String message) {
        String value = normalizedText(message);
        return value.contains("歌单") && containsAny(value, "创建", "新建", "生成", "建一个", "建个");
    }

    static boolean isFavoriteRequest(String value) {
        return !isUnfavoriteRequest(value) && (value.contains("加入收藏") || value.contains("收藏这首")
                || value.matches(".*把.+收藏.*") || (value.contains("收藏")
                && !containsAny(value, "收藏了什么", "收藏了哪些", "我的收藏", "收藏列表",
                "查询收藏", "查看收藏", "收藏多少", "收藏几首")));
    }

    static boolean isUnfavoriteRequest(String value) {
        return containsAny(value, "取消收藏", "不再收藏", "移出收藏", "删除收藏");
    }

    static boolean isAddToPlaylistRequest(String value) {
        return value.contains("歌单") && containsAny(value, "加入", "添加", "放进")
                && !isPlaylistCreationRequest(value) && !isRemoveFromPlaylistRequest(value);
    }

    static boolean isRemoveFromPlaylistRequest(String value) {
        return value.contains("歌单") && containsAny(value, "移除", "移出", "删除", "拿掉")
                && (value.contains("从") || value.contains("移出"));
    }

    static boolean isDeletePlaylistRequest(String value) {
        return value.contains("歌单") && containsAny(value, "删除", "删掉", "移除")
                && !isRemoveFromPlaylistRequest(value);
    }

    static boolean isRenamePlaylistRequest(String value) {
        return value.contains("歌单") && containsAny(value, "重命名", "改名", "改成");
    }

    static String playlistName(String message) {
        String value = message == null ? "" : message.trim();
        for (Pattern pattern : new Pattern[]{QUOTED_NAME, PLAIN_NAME}) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) return trimName(matcher.group(1));
        }
        return "AI 推荐歌单";
    }

    static String renamedPlaylistName(String message) {
        Matcher matcher = RENAME_NAME.matcher(message == null ? "" : message.trim());
        return matcher.find() ? trimName(matcher.group(1)) : "";
    }

    static int requestedSongCount(String message, int available) {
        Matcher arabic = ARABIC_COUNT.matcher(message == null ? "" : message);
        if (arabic.find()) return Math.max(1, Math.min(Math.min(Integer.parseInt(arabic.group(1)), 20), available));
        String[] chinese = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        String value = message == null ? "" : message;
        for (int index = 1; index < chinese.length; index++) if (value.contains(chinese[index] + "首")) return Math.min(index, available);
        return Math.min(5, available);
    }

    private static String trimName(String value) {
        String name = value == null ? "" : value.trim();
        return name.substring(0, Math.min(name.length(), 80));
    }

    private static String normalizedText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s《》()（）【】,，。！？?、:：\\-]", "");
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private String statusMessage(String status) {
        if ("expired".equals(status)) return "确认已过期，请重新生成任务";
        if ("cancelled".equals(status)) return "任务已经取消";
        if ("executing".equals(status)) return "任务正在执行，请勿重复提交";
        if ("succeeded".equals(status)) return "任务已经执行完成";
        return "任务当前不可执行";
    }
}
