package com.example.demo.service;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.RetrievalEvidenceBuilder;
import com.example.demo.service.retrieval.RetrievalResult;
import com.example.demo.service.retrieval.RetrievalSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MusicRagRetriever {

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    public List<Audio> retrieve(String question, List<Map<String, String>> history, int limit) {
        List<ScoredAudio> candidates = rankedCandidates(question, history);
        List<Audio> results = new ArrayList<>();
        for (ScoredAudio candidate : candidates) {
            if (results.size() >= limit) break;
            results.add(candidate.getAudio());
        }
        return results;
    }

    public List<RetrievalResult> retrieveResults(String question, List<Map<String, String>> history, int limit) {
        List<ScoredAudio> candidates = rankedCandidates(question, history);
        List<RetrievalResult> results = new ArrayList<>();
        for (ScoredAudio candidate : candidates) {
            if (results.size() >= limit) break;
            results.add(new RetrievalResult(candidate.getAudio().getId(), RetrievalSource.KEYWORD,
                    candidate.getScore(), RetrievalEvidenceBuilder.fromAudio(candidate.getAudio())));
        }
        return results;
    }

    private List<ScoredAudio> rankedCandidates(String question, List<Map<String, String>> history) {
        String retrievalQuery = queryUnderstandingService.normalize(buildRetrievalQuery(question, history));
        List<String> tokens = tokenize(retrievalQuery);
        List<ScoredAudio> candidates = new ArrayList<>();

        for (Audio audio : audioMapper.selectAll()) {
            int score = score(audio, retrievalQuery, tokens);
            if (score > 0) candidates.add(new ScoredAudio(audio, score));
        }

        candidates.sort(Comparator.comparingInt(ScoredAudio::getScore).reversed()
                .thenComparing(item -> item.getAudio().getUploadTime(), Comparator.nullsLast(Comparator.reverseOrder())));

        return candidates;
    }

    private String buildRetrievalQuery(String question, List<Map<String, String>> history) {
        StringBuilder query = new StringBuilder(question == null ? "" : question);
        if (history == null) return query.toString();
        int startIndex = Math.max(0, history.size() - 8);
        for (int index = startIndex; index < history.size(); index++) {
            String content = history.get(index).get("content");
            if (content != null) query.append(' ').append(content);
        }
        return query.toString();
    }

    private int score(Audio audio, String query, List<String> tokens) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String songName = safeLower(audio.getSongName());
        String singer = safeLower(audio.getSinger());
        String genre = safeLower(audio.getGenre());
        String source = safeLower(audio.getSource());
        String introduction = safeLower(audio.getIntroduction());
        int score = 0;

        if (!songName.isEmpty() && normalizedQuery.contains(songName)) score += 100;
        if (!singer.isEmpty() && normalizedQuery.contains(singer)) score += 70;
        if (!genre.isEmpty() && normalizedQuery.contains(genre)) score += 55;
        if (!source.isEmpty() && normalizedQuery.contains(source)) score += 65;
        for (String token : tokens) {
            if (token.length() < 2) continue;
            if (songName.contains(token)) score += 12;
            if (singer.contains(token)) score += 8;
            if (genre.contains(token)) score += 10;
            if (source.contains(token)) score += 12;
            if (introduction.contains(token)) score += 5;
        }
        return score;
    }

    private List<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String part : normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (part.length() < 2) continue;
            tokens.add(part);
            if (containsChinese(part)) {
                for (int index = 0; index < part.length() - 1; index++) {
                    tokens.add(part.substring(index, index + 2));
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    private boolean containsChinese(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) >= 0x4e00 && text.charAt(index) <= 0x9fa5) return true;
        }
        return false;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static class ScoredAudio {
        private final Audio audio;
        private final int score;

        private ScoredAudio(Audio audio, int score) {
            this.audio = audio;
            this.score = score;
        }

        private Audio getAudio() {
            return audio;
        }

        private int getScore() {
            return score;
        }
    }
}
