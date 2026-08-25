package com.example.demo.service;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.example.demo.util.MusicGenreUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PersonalizedRecommendationService {

    private static final int FAVORITE_GENRE_WEIGHT = 5;

    @Autowired
    private AudioMapper audioMapper;

    public List<Audio> recommend(Integer userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        if (userId == null) return fallback(safeLimit);

        Map<String, Integer> genreScores = genreScores(userId);
        if (genreScores.isEmpty()) return fallbackForUser(userId, safeLimit);

        List<Audio> candidates = new ArrayList<>(audioMapper.selectRecommendedForUser(userId));
        Collections.shuffle(candidates);
        candidates.sort(Comparator
                .comparingInt((Audio audio) -> recommendationScore(audio, genreScores)).reversed()
                .thenComparing((Audio audio) -> audio.getCollectCount() == null ? 0 : audio.getCollectCount(), Comparator.reverseOrder())
                .thenComparing(Audio::getUploadTime, Comparator.nullsLast(Comparator.reverseOrder())));
        return candidates.subList(0, Math.min(safeLimit, candidates.size()));
    }

    private Map<String, Integer> genreScores(Integer userId) {
        Map<String, Integer> scores = new HashMap<>();
        for (Audio audio : audioMapper.selectUserCollects(userId)) {
            for (String genre : MusicGenreUtils.splitOrOther(audio.getGenre())) {
                scores.merge(normalizeGenre(genre), FAVORITE_GENRE_WEIGHT, Integer::sum);
            }
        }
        for (Map<String, Object> item : audioMapper.selectUserGenrePlayCounts(userId)) {
            Object genre = item.get("genre");
            for (String value : MusicGenreUtils.splitOrOther(genre == null ? null : String.valueOf(genre))) {
                scores.merge(normalizeGenre(value), number(item.get("playCount")), Integer::sum);
            }
        }
        return scores;
    }

    private List<Audio> fallback(int limit) {
        List<Audio> songs = new ArrayList<>(audioMapper.selectRecommended());
        return songs.subList(0, Math.min(limit, songs.size()));
    }

    private List<Audio> fallbackForUser(Integer userId, int limit) {
        List<Audio> songs = new ArrayList<>(audioMapper.selectRecommendedForUser(userId));
        return songs.subList(0, Math.min(limit, songs.size()));
    }

    private int number(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private int recommendationScore(Audio audio, Map<String, Integer> genreScores) {
        int score = 0;
        for (String genre : MusicGenreUtils.splitOrOther(audio.getGenre())) {
            score += genreScores.getOrDefault(normalizeGenre(genre), 0);
        }
        return score;
    }

    private String normalizeGenre(String genre) {
        if (genre == null || genre.trim().isEmpty()) return "其他";
        return genre.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
