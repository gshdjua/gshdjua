package com.example.demo.service;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AgentToolService {

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private MusicLibraryAgent musicLibraryAgent;

    @Autowired
    private VectorRagClient vectorRagClient;

    public Map<String, Object> searchSongs(String query, int limit) {
        String normalizedQuery = normalize(query);
        List<ScoredAudio> matches = new ArrayList<>();
        for (Audio audio : audioMapper.selectAll()) {
            int score = score(audio, normalizedQuery);
            if (score > 0) matches.add(new ScoredAudio(audio, score));
        }
        matches.sort(Comparator.comparingInt(ScoredAudio::getScore).reversed()
                .thenComparing(item -> item.getAudio().getCollectCount() == null
                        ? 0 : item.getAudio().getCollectCount(), Comparator.reverseOrder())
                .thenComparing(item -> item.getAudio().getUploadTime(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ScoredAudio match : matches.subList(0, Math.min(limit, matches.size()))) {
            items.add(safeAudio(match.getAudio()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    public Map<String, Object> searchFavorites(Integer userId, String query, int limit) {
        requireUser(userId);
        String normalizedQuery = normalize(query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Audio audio : audioMapper.selectUserCollects(userId)) {
            if (!normalizedQuery.isEmpty() && score(audio, normalizedQuery) <= 0) continue;
            items.add(safeAudio(audio));
            if (items.size() >= limit) break;
        }
        return listResult(query, items);
    }

    public Map<String, Object> recommendSongs(Integer userId, String query, int limit, List<Integer> excludedAudioIds) {
        requireUser(userId);
        Set<Integer> excluded = new LinkedHashSet<>();
        if (excludedAudioIds != null) excluded.addAll(excludedAudioIds);
        MusicLibraryAgent.RecommendationOutcome outcome = musicLibraryAgent.getRecommendationOutcome(
                query, userId, limit, excluded);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Audio audio : outcome.getSongs()) items.add(safeAudio(audio));
        Map<String, Object> result = listResult(query, items);
        result.putAll(outcome.toMetadata());
        return result;
    }

    public Map<String, Object> vectorSearch(String query, int limit, List<Integer> audioIds) {
        List<RetrievalResult> matches = vectorRagClient.searchResults(query, limit, audioIds);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RetrievalResult match : matches) {
            Audio audio = audioMapper.selectById(match.getAudioId());
            if (audio == null) continue;
            Map<String, Object> item = safeAudio(audio);
            item.put("semanticScore", match.getScore());
            item.put("evidence", match.getEvidence());
            items.add(item);
        }
        return listResult(query, items);
    }

    public Map<String, Object> songDetail(Integer audioId, String query) {
        Audio audio = audioId == null ? findDetail(query) : audioMapper.selectById(audioId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", audio != null);
        result.put("item", audio == null ? null : safeAudio(audio));
        return result;
    }

    private Audio findDetail(String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) return null;
        Audio best = null;
        int bestScore = 0;
        for (Audio audio : audioMapper.selectAll()) {
            String songName = normalize(audio.getSongName());
            String singer = normalize(audio.getSinger());
            int current = 0;
            if (!songName.isEmpty() && songName.equals(normalizedQuery)) current = 200;
            else if (songName.length() >= 2 && normalizedQuery.contains(songName)) current = 150;
            if (current > 0 && !singer.isEmpty() && normalizedQuery.contains(singer)) current += 50;
            if (current > bestScore) {
                best = audio;
                bestScore = current;
            }
        }
        return best;
    }

    private Map<String, Object> safeAudio(Audio audio) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", audio.getId());
        item.put("songName", audio.getSongName());
        item.put("singer", audio.getSinger());
        item.put("genre", audio.getGenre());
        item.put("source", audio.getSource());
        item.put("introduction", audio.getIntroduction());
        item.put("collectCount", audio.getCollectCount() == null ? 0 : audio.getCollectCount());
        item.put("coverPath", audio.getCoverPath());
        return item;
    }

    private Map<String, Object> listResult(String query, List<Map<String, Object>> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query == null ? "" : query);
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    private void requireUser(Integer userId) {
        if (userId == null) throw new IllegalArgumentException("需要登录用户上下文");
    }

    private int score(Audio audio, String query) {
        return fieldScore(audio.getSongName(), query, 100)
                + fieldScore(audio.getSinger(), query, 80)
                + fieldScore(audio.getGenre(), query, 60)
                + fieldScore(audio.getSource(), query, 50)
                + fieldScore(audio.getIntroduction(), query, 20);
    }

    private int fieldScore(String value, String query, int weight) {
        String field = normalize(value);
        if (field.isEmpty() || query.isEmpty()) return 0;
        if (field.equals(query)) return weight + 30;
        if (field.contains(query)) return weight;
        if (field.length() >= 2 && query.contains(field)) return Math.max(1, weight - 10);
        for (String rawToken : value.split("[\\s,，、;/|]+")) {
            String token = normalize(rawToken);
            if (token.length() >= 2 && (token.contains(query) || query.contains(token))) {
                return Math.max(1, weight - 15);
            }
        }
        return 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s《》()（）【】,，。！？?、:：\\-]", "");
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
}
