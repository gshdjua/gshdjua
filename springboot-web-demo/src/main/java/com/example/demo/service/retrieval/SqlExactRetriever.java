package com.example.demo.service.retrieval;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.AssistantQueryUnderstandingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class SqlExactRetriever {

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    public List<RetrievalResult> retrieve(String question, int limit) {
        String query = normalize(queryUnderstandingService.normalize(question));
        if (query.isEmpty() || limit < 1) return new ArrayList<>();

        List<ScoredAudio> candidates = new ArrayList<>();
        for (Audio audio : audioMapper.selectAll()) {
            double score = exactScore(query, audio);
            if (score > 0) candidates.add(new ScoredAudio(audio, score));
        }
        candidates.sort(Comparator.comparingDouble(ScoredAudio::getScore).reversed()
                .thenComparing(item -> item.getAudio().getUploadTime(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<RetrievalResult> results = new ArrayList<>();
        for (ScoredAudio candidate : candidates) {
            if (results.size() >= limit) break;
            results.add(new RetrievalResult(candidate.getAudio().getId(), RetrievalSource.SQL_EXACT,
                    candidate.getScore(), RetrievalEvidenceBuilder.fromAudio(candidate.getAudio())));
        }
        return results;
    }

    private double exactScore(String query, Audio audio) {
        String songName = normalize(audio.getSongName());
        String singer = normalize(audio.getSinger());
        String source = normalize(audio.getSource());
        double score = 0;
        if (!songName.isEmpty() && query.contains(songName)) score = Math.max(score, 1000 + songName.length());
        if (!singer.isEmpty() && query.contains(singer)) score = Math.max(score, 800 + singer.length());
        if (!source.isEmpty() && query.contains(source)) score = Math.max(score, 950 + source.length());
        int sourceScore = sourceFragmentScore(query, source);
        if (sourceScore > 0) score = Math.max(score, 600 + sourceScore);
        return score;
    }

    private int sourceFragmentScore(String query, String source) {
        if (source.isEmpty()) return 0;
        int maxLength = Math.min(20, query.length());
        for (int length = maxLength; length >= 3; length--) {
            for (int index = 0; index + length <= query.length(); index++) {
                String fragment = query.substring(index, index + length);
                if (!isGenericFragment(fragment) && source.contains(fragment)) return length;
            }
        }
        return 0;
    }

    private boolean isGenericFragment(String fragment) {
        return "动画".equals(fragment) || "动漫".equals(fragment) || "番剧".equals(fragment)
                || "歌曲".equals(fragment) || "音乐".equals(fragment) || "推荐".equals(fragment)
                || "主题曲".equals(fragment) || "片尾曲".equals(fragment) || "片头曲".equals(fragment)
                || "这部".equals(fragment) || "有关".equals(fragment);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s《》()（）【】,，。！？?、:：-]", "");
    }

    private static class ScoredAudio {
        private final Audio audio;
        private final double score;

        private ScoredAudio(Audio audio, double score) {
            this.audio = audio;
            this.score = score;
        }

        private Audio getAudio() { return audio; }
        private double getScore() { return score; }
    }
}
