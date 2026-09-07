package com.example.demo.service;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.util.MusicGenreUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MusicLibraryAgent {

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private PersonalizedRecommendationService personalizedRecommendationService;

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    @Autowired
    private VectorRagClient vectorRagClient;

    @Autowired
    private MusicRagRetriever musicRagRetriever;

    public String reply(String message, Integer userId, AssistantIntent intent) {
        List<Audio> songs = audioMapper.selectAll();
        if (intent == AssistantIntent.RECOMMENDATION) {
            Audio seedSong = findMentionedSong(message, songs);
            List<Audio> recommendations = getRecommendationsForQuery(message, userId, 3);
            List<String> requestedGenres = queryUnderstandingService.requestedGenres(message);
            return recommendations.isEmpty() ? requestedGenres.isEmpty()
                    ? "歌库中暂时没有更多未收藏的歌曲可推荐。"
                    : "歌库中暂时没有同时属于“" + String.join(" + ", requestedGenres) + "”类型的未收藏歌曲。"
                    : !requestedGenres.isEmpty()
                    ? "我按歌曲类型“" + String.join(" + ", requestedGenres) + "”筛选"
                    + (isMoodRecommendation(message) ? "，并以“" + moodLabel(message) + "”的听感排序" : "") + "推荐："
                    + readableSongList(recommendations, 3) + "。"
                    : isMoodRecommendation(message)
                    ? "我按“" + moodLabel(message) + "”的听感和歌曲资料进行语义排序推荐："
                    + readableSongList(recommendations, 3) + "。"
                    : seedSong == null
                    ? "我按你的收藏和播放偏好推荐：" + readableSongList(recommendations, 3) + "。"
                    : "我以《" + seedSong.getSongName() + "》为参考，优先按相同类型、出处和歌手筛选，并已排除这首歌本身："
                    + readableSongList(recommendations, 3) + "。";
        }
        if (intent == AssistantIntent.SOURCE_QUERY) {
            List<Audio> sourceSongs = findSongsBySource(message, songs);
            return sourceSongs.isEmpty()
                    ? "歌库中没有找到与该出处精确匹配的歌曲。请检查后台是否已为歌曲填写出处，或使用作品的完整名称提问。"
                    : "我在该出处下找到 " + sourceSongs.size() + " 首歌曲：" + readableSongList(sourceSongs, 6) + "。";
        }
        if (intent == AssistantIntent.FAVORITES) {
            List<Audio> favorites = audioMapper.selectUserCollects(userId);
            return favorites.isEmpty() ? "你的收藏夹目前还是空的。"
                    : "你收藏了 " + favorites.size() + " 首歌：" + readableSongList(favorites, 5) + "。";
        }
        if (intent == AssistantIntent.GENRE_QUERY) return readableGenreReply(songs, queryUnderstandingService.normalize(message));
        if (intent == AssistantIntent.LIBRARY_QUERY) {
            return songs.isEmpty() ? "歌库目前没有歌曲。" : "歌库现在有 " + songs.size() + " 首歌：" + readableSongList(songs, 6) + "。";
        }
        return reply(message, userId);
    }

    public String reply(String message, Integer userId) {
        String question = message == null ? "" : message.trim();
        List<Audio> songs = audioMapper.selectAll();
        if (question.isEmpty()) {
            return "你好！我可以查询歌库、推荐热门歌曲，或告诉你收藏了哪些歌。";
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "类型", "曲风", "风格", "分类", "genre")) {
            return genreReply(songs, normalized);
        }
        // “收藏量高”描述的是全站歌曲热度，不是当前用户的收藏列表；必须优先于“收藏”意图判断。
        if (isPopularityQuestion(normalized)) {
            List<Audio> popularSongs = userId == null ? audioMapper.selectRecommended() : audioMapper.selectRecommendedForUser(userId);
            if (popularSongs.isEmpty()) return "歌库中暂时没有更多未收藏的热门歌曲可推荐。";
            return "我已排除你收藏过的歌曲，并按全站收藏量从高到低推荐：" + popularSongList(popularSongs, 3) + "。";
        }
        if (containsAny(normalized, "收藏", "favorite")) {
            List<Audio> favorites = audioMapper.selectUserCollects(userId);
            if (favorites.isEmpty()) return "你的收藏夹目前还是空的，去歌曲页挑几首喜欢的歌吧。";
            return "你收藏了 " + favorites.size() + " 首歌：" + songList(favorites, 5) + "。";
        }

        List<Audio> matchedSongs = songs.stream()
                .filter(song -> normalized.contains(song.getSongName().toLowerCase(Locale.ROOT))
                        || normalized.contains(song.getSinger().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        if (!matchedSongs.isEmpty()) {
            return "我找到了：" + songList(matchedSongs, 5) + "。";
        }

        if (containsAny(normalized, "多少", "几首", "歌库", "歌曲", "有什么", "哪些")) {
            if (songs.isEmpty()) return "歌库目前没有歌曲。";
            return "歌库现在有 " + songs.size() + " 首歌，包括：" + songList(songs, 6) + "。";
        }

        if (songs.isEmpty()) return "歌库目前没有歌曲，管理员上传歌曲后我就能帮你查询。";
        return "我可以帮你了解歌库。例如你可以问“歌库有多少歌”、“推荐热门歌曲”、“我收藏了什么”，或直接问某首歌、某位歌手。";
    }

    public List<Audio> getRecommendations(Integer userId) {
        return personalizedRecommendationService.recommend(userId, 6);
    }

    public List<Audio> getRecommendationsForQuery(String message, Integer userId, int limit) {
        return getRecommendationsForQuery(message, userId, limit, Collections.emptySet());
    }

    public List<Audio> getRecommendationsForQuery(String message, Integer userId, int limit, Set<Integer> excludedAudioIds) {
        List<Audio> songs = audioMapper.selectAll();
        Set<Integer> excludedIds = new LinkedHashSet<>();
        if (excludedAudioIds != null) excludedIds.addAll(excludedAudioIds);
        if (userId != null) {
            for (Audio favorite : audioMapper.selectUserCollects(userId)) excludedIds.add(favorite.getId());
        }
        if (queryUnderstandingService.classify(message) == AssistantIntent.SOURCE_QUERY) {
            List<Audio> sourceSongs = findSongsBySource(message, songs).stream()
                    .filter(song -> !excludedIds.contains(song.getId()))
                    .collect(Collectors.toList());
            return sourceSongs.subList(0, Math.min(Math.max(1, limit), sourceSongs.size()));
        }
        List<String> requestedGenres = queryUnderstandingService.requestedGenres(message);
        if (!requestedGenres.isEmpty()) {
            return getGenreConstrainedRecommendations(message, songs, limit, excludedIds, requestedGenres);
        }
        if (isMoodRecommendation(message)) {
            return getMoodRecommendations(message, songs, limit, excludedIds);
        }
        Audio seedSong = findMentionedSong(message, songs);
        if (seedSong == null) return personalizedRecommendationService.recommend(userId, limit).stream()
                .filter(song -> !excludedIds.contains(song.getId()))
                .limit(limit)
                .collect(Collectors.toList());

        List<Audio> candidates = new ArrayList<>();
        for (Audio song : songs) {
            if (!seedSong.getId().equals(song.getId()) && !excludedIds.contains(song.getId())) candidates.add(song);
        }
        candidates.sort(Comparator
                .comparingInt((Audio song) -> similarityScore(seedSong, song)).reversed()
                .thenComparing((Audio song) -> song.getCollectCount() == null ? 0 : song.getCollectCount(), Comparator.reverseOrder())
                .thenComparing(Audio::getUploadTime, Comparator.nullsLast(Comparator.reverseOrder())));
        return candidates.subList(0, Math.min(Math.max(1, limit), candidates.size()));
    }

    public List<Audio> findExactSongs(String message, int limit) {
        List<Audio> songs = audioMapper.selectAll();
        String normalized = queryUnderstandingService.normalize(message);
        Map<Integer, Audio> matches = new LinkedHashMap<>();
        for (Audio song : songs) {
            String songName = song.getSongName() == null ? "" : song.getSongName().toLowerCase(Locale.ROOT);
            String singer = song.getSinger() == null ? "" : song.getSinger().toLowerCase(Locale.ROOT);
            if ((!songName.isEmpty() && normalized.contains(songName)) || (!singer.isEmpty() && normalized.contains(singer))) {
                matches.put(song.getId(), song);
            }
        }
        for (Audio song : findExactSongsBySource(normalized, songs)) matches.put(song.getId(), song);
        return new ArrayList<>(matches.values()).subList(0, Math.min(Math.max(1, limit), matches.size()));
    }

    public List<Audio> findExactSourceSongs(String message, int limit) {
        List<Audio> matches = findExactSongsBySource(message, audioMapper.selectAll());
        return matches.subList(0, Math.min(Math.max(1, limit), matches.size()));
    }

    private List<Audio> findSongsBySource(String message, List<Audio> songs) {
        List<Audio> exactSongs = findExactSongsBySource(message, songs);
        if (!exactSongs.isEmpty()) return exactSongs;

        List<Audio> semanticSongs = vectorRagClient.search(queryUnderstandingService.normalize(message), 6);
        if (!semanticSongs.isEmpty()) return semanticSongs;

        return musicRagRetriever.retrieve(message, null, 6);
    }

    private List<Audio> getMoodRecommendations(String message, List<Audio> songs, int limit, Set<Integer> excludedIds) {
        Map<Integer, Integer> semanticRanks = semanticRanks(message, excludedIds);

        List<Audio> candidates = songs.stream()
                .filter(song -> !excludedIds.contains(song.getId()))
                .sorted(Comparator.comparingInt((Audio song) -> moodKeywordScore(song, message)).reversed()
                        .thenComparingInt(song -> semanticRanks.getOrDefault(song.getId(), Integer.MAX_VALUE))
                        .thenComparing((Audio song) -> song.getCollectCount() == null ? 0 : song.getCollectCount(), Comparator.reverseOrder())
                        .thenComparing(Audio::getUploadTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        return candidates.subList(0, Math.min(Math.max(1, limit), candidates.size()));
    }

    private List<Audio> getGenreConstrainedRecommendations(String message, List<Audio> songs, int limit,
                                                            Set<Integer> excludedIds, List<String> requestedGenres) {
        Map<Integer, Integer> semanticRanks = semanticRanks(message, excludedIds);
        List<Audio> candidates = songs.stream()
                .filter(song -> !excludedIds.contains(song.getId()))
                .filter(song -> MusicGenreUtils.containsAll(song.getGenre(), requestedGenres))
                .sorted(Comparator.comparingInt((Audio song) -> isMoodRecommendation(message) ? moodKeywordScore(song, message) : 0).reversed()
                        .thenComparingInt(song -> semanticRanks.getOrDefault(song.getId(), Integer.MAX_VALUE))
                        .thenComparing((Audio song) -> song.getCollectCount() == null ? 0 : song.getCollectCount(), Comparator.reverseOrder())
                        .thenComparing(Audio::getUploadTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        return candidates.subList(0, Math.min(Math.max(1, limit), candidates.size()));
    }

    private Map<Integer, Integer> semanticRanks(String message, Set<Integer> excludedIds) {
        Map<Integer, Integer> ranks = new LinkedHashMap<>();
        int rank = 0;
        for (Audio song : vectorRagClient.search(queryUnderstandingService.normalize(message), 20)) {
            if (!excludedIds.contains(song.getId())) ranks.putIfAbsent(song.getId(), rank++);
        }
        for (Audio song : musicRagRetriever.retrieve(message, null, 20)) {
            if (!excludedIds.contains(song.getId())) ranks.putIfAbsent(song.getId(), rank++);
        }
        return ranks;
    }

    private boolean isMoodRecommendation(String message) {
        String normalized = queryUnderstandingService.normalize(message);
        return containsAny(normalized, "轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "悲伤", "安静");
    }

    private int moodKeywordScore(Audio song, String message) {
        String text = ((song.getGenre() == null ? "" : song.getGenre()) + " "
                + (song.getIntroduction() == null ? "" : song.getIntroduction())).toLowerCase(Locale.ROOT);
        String normalized = queryUnderstandingService.normalize(message);
        int score = 0;
        if (normalized.contains("轻松") && containsAny(text, "轻松", "轻音乐", "纯音乐", "钢琴", "治愈", "舒缓", "欢快", "日常", "明快", "放松", "温柔", "宁静")) score += 20;
        if (normalized.contains("治愈") && containsAny(text, "治愈", "温柔", "舒缓", "宁静", "轻音乐", "钢琴")) score += 20;
        if (normalized.contains("舒缓") && containsAny(text, "舒缓", "宁静", "温柔", "轻音乐", "纯音乐", "钢琴", "放松")) score += 20;
        if (normalized.contains("欢快") && containsAny(text, "欢快", "明快", "活泼", "轻快")) score += 20;
        if (normalized.contains("热血") && containsAny(text, "热血", "激昂", "摇滚", "燃")) score += 20;
        if (containsAny(normalized, "伤感", "悲伤") && containsAny(text, "伤感", "悲伤", "忧郁", "抒情")) score += 20;
        if (normalized.contains("安静") && containsAny(text, "安静", "宁静", "舒缓", "轻音乐", "纯音乐", "钢琴")) score += 20;
        return score;
    }

    private String moodLabel(String message) {
        String normalized = queryUnderstandingService.normalize(message);
        for (String mood : new String[]{"轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "悲伤", "安静"}) {
            if (normalized.contains(mood)) return mood;
        }
        return "当前";
    }

    private List<Audio> findExactSongsBySource(String message, List<Audio> songs) {
        String query = normalizeForMatch(queryUnderstandingService.normalize(message));
        List<ScoredAudio> matches = new ArrayList<>();
        for (Audio song : songs) {
            int score = sourceMatchScore(query, normalizeForMatch(song.getSource()));
            if (score > 0) matches.add(new ScoredAudio(song, score));
        }
        matches.sort(Comparator.comparingInt(ScoredAudio::getScore).reversed()
                .thenComparing(item -> item.getAudio().getUploadTime(), Comparator.nullsLast(Comparator.reverseOrder())));
        List<Audio> result = new ArrayList<>();
        for (ScoredAudio item : matches) result.add(item.getAudio());
        return result;
    }

    private int sourceMatchScore(String query, String source) {
        if (source.isEmpty()) return 0;
        if (query.contains(source)) return source.length() + 100;
        int maxLength = Math.min(16, query.length());
        for (int length = maxLength; length >= 3; length--) {
            for (int index = 0; index + length <= query.length(); index++) {
                String fragment = query.substring(index, index + length);
                if (!isGenericSourceFragment(fragment) && source.contains(fragment)) return length;
            }
        }
        return 0;
    }

    private String normalizeForMatch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s《》()（）【】,，。！？?、:：-]", "");
    }

    private boolean isGenericSourceFragment(String fragment) {
        return "动画".equals(fragment) || "动漫".equals(fragment) || "番剧".equals(fragment)
                || "歌曲".equals(fragment) || "音乐".equals(fragment) || "推荐".equals(fragment)
                || "主题曲".equals(fragment) || "片尾曲".equals(fragment) || "片头曲".equals(fragment)
                || "这部".equals(fragment) || "有关".equals(fragment);
    }

    private static class ScoredAudio {
        private final Audio audio;
        private final int score;

        private ScoredAudio(Audio audio, int score) {
            this.audio = audio;
            this.score = score;
        }

        private Audio getAudio() { return audio; }
        private int getScore() { return score; }
    }

    private Audio findMentionedSong(String message, List<Audio> songs) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Audio matched = null;
        for (Audio song : songs) {
            String songName = song.getSongName() == null ? "" : song.getSongName().toLowerCase(Locale.ROOT);
            if (!songName.isEmpty() && normalized.contains(songName)
                    && (matched == null || songName.length() > matched.getSongName().length())) {
                matched = song;
            }
        }
        return matched;
    }

    private int similarityScore(Audio seedSong, Audio candidate) {
        int score = 0;
        if (MusicGenreUtils.overlaps(seedSong.getGenre(), candidate.getGenre())) score += 100;
        if (same(seedSong.getSource(), candidate.getSource())) score += 35;
        if (same(seedSong.getSinger(), candidate.getSinger())) score += 20;
        return score;
    }

    private boolean same(String first, String second) {
        return first != null && !first.trim().isEmpty() && first.trim().equalsIgnoreCase(second == null ? "" : second.trim());
    }

    private String readableSongList(List<Audio> songs, int limit) {
        return songs.stream().limit(limit)
                .map(song -> "《" + song.getSongName() + "》- " + song.getSinger())
                .collect(Collectors.joining("；"));
    }

    private String readableGenreReply(List<Audio> songs, String normalizedQuestion) {
        if (songs.isEmpty()) return "歌库目前没有歌曲，因此还没有可统计的音乐类型。";
        List<String> requestedGenres = queryUnderstandingService.requestedGenres(normalizedQuestion);
        if (!requestedGenres.isEmpty()) {
            List<Audio> matches = songs.stream()
                    .filter(song -> MusicGenreUtils.containsAll(song.getGenre(), requestedGenres))
                    .collect(Collectors.toList());
            return matches.isEmpty()
                    ? "歌库中没有同时属于“" + String.join(" + ", requestedGenres) + "”类型的歌曲。"
                    : "歌库中同时属于“" + String.join(" + ", requestedGenres) + "”类型的歌曲有 " + matches.size() + " 首："
                    + readableSongList(matches, 6) + "。";
        }
        Map<String, List<Audio>> songsByGenre = new LinkedHashMap<>();
        for (Audio song : songs) {
            for (String genre : MusicGenreUtils.splitOrOther(song.getGenre())) {
                songsByGenre.computeIfAbsent(genre, key -> new java.util.ArrayList<>()).add(song);
            }
        }
        for (Map.Entry<String, List<Audio>> entry : songsByGenre.entrySet()) {
            if (normalizedQuestion.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return "歌库中的“" + entry.getKey() + "”类型共有 " + entry.getValue().size() + " 首："
                        + readableSongList(entry.getValue(), 6) + "。";
            }
        }
        return "歌库目前的音乐类型有：" + songsByGenre.entrySet().stream()
                .map(entry -> entry.getKey() + "（" + entry.getValue().size() + " 首）")
                .collect(Collectors.joining("、")) + "。";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private boolean isPopularityQuestion(String text) {
        return containsAny(text, "推荐", "热门", "最受欢迎", "top", "人气", "好听", "听什么", "听啥",
                "收藏量", "收藏数", "收藏最多", "高收藏", "收藏最高");
    }

    private String songList(List<Audio> songs, int limit) {
        return songs.stream()
                .limit(limit)
                .map(song -> "《" + song.getSongName() + "》- " + song.getSinger())
                .collect(Collectors.joining("；"));
    }

    private String popularSongList(List<Audio> songs, int limit) {
        return songs.stream()
                .limit(limit)
                .map(song -> "《" + song.getSongName() + "》- " + song.getSinger()
                        + "（" + (song.getCollectCount() == null ? 0 : song.getCollectCount()) + " 次收藏）")
                .collect(Collectors.joining("；"));
    }

    private String genreReply(List<Audio> songs, String normalizedQuestion) {
        if (songs.isEmpty()) return "歌库目前没有歌曲，因此还没有可统计的音乐类型。";

        Map<String, List<Audio>> songsByGenre = new LinkedHashMap<>();
        for (Audio song : songs) {
            for (String genre : MusicGenreUtils.splitOrOther(song.getGenre())) {
                songsByGenre.computeIfAbsent(genre, key -> new java.util.ArrayList<>()).add(song);
            }
        }

        for (Map.Entry<String, List<Audio>> entry : songsByGenre.entrySet()) {
            if (normalizedQuestion.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return "歌库中的“" + entry.getKey() + "”类型共有 " + entry.getValue().size() + " 首："
                        + songList(entry.getValue(), 6) + "。";
            }
        }

        return "歌库目前有以下音乐类型：" + songsByGenre.entrySet().stream()
                .map(entry -> entry.getKey() + "（" + entry.getValue().size() + " 首）")
                .collect(Collectors.joining("、")) + "。你也可以继续问我某一种类型有哪些歌曲。";
    }
}
