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
public class StrictEntityRetriever {

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    public List<RetrievalResult> retrieve(StructuredEntityQuery query, int limit) {
        List<ScoredAudio> matched = new ArrayList<>();
        if (query == null || !query.isStrict() || !query.hasEntity() || limit < 1) return new ArrayList<>();
        String entity = normalize(query.getEntityValue());
        for (Audio audio : audioMapper.selectAll()) {
            double score = matchScore(query.getEntityType(), entity, audio);
            if (score > 0) matched.add(new ScoredAudio(audio, score));
        }
        matched.sort(Comparator.comparingDouble(ScoredAudio::getScore).reversed()
                .thenComparing(item -> item.getAudio().getUploadTime(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<RetrievalResult> results = new ArrayList<>();
        for (ScoredAudio item : matched) {
            if (results.size() >= limit) break;
            RetrievalResult result = new RetrievalResult(item.getAudio().getId(), RetrievalSource.SQL_EXACT,
                    item.getScore(), RetrievalEvidenceBuilder.fromAudio(item.getAudio()));
            result.setFusionProfile("STRICT_ENTITY_" + query.getEntityType().name());
            result.setFusionScore(item.getScore());
            result.setRerankScore(item.getScore());
            result.setMetadataScore(1.0);
            result.setCorroborationScore(1.0);
            result.setConfidenceScore(1.0);
            result.setConfidenceThreshold(1.0);
            result.setConfidenceAccepted(true);
            result.setConfidenceReason("严格实体字段匹配");
            results.add(result);
        }
        return results;
    }

    private double matchScore(EntityType type, String entity, Audio audio) {
        if (entity.isEmpty() || audio == null) return 0;
        if (type == EntityType.SONG) return fieldScore(entity, audio.getSongName(), 1100);
        if (type == EntityType.SINGER) return fieldScore(entity, audio.getSinger(), 1000);
        if (type == EntityType.SOURCE) return fieldScore(entity, audio.getSource(), 1050);
        if (type == EntityType.GENRE) return fieldScore(entity, audio.getGenre(), 950);
        if (type == EntityType.COLLECTION_COUNT) {
            try {
                int expectedCount = Integer.parseInt(entity);
                int actualCount = audio.getCollectCount() == null ? 0 : audio.getCollectCount();
                return expectedCount == actualCount ? 1200 : 0;
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        return Math.max(Math.max(fieldScore(entity, audio.getSongName(), 1100),
                        fieldScore(entity, audio.getSinger(), 1000)),
                Math.max(fieldScore(entity, audio.getSource(), 1050), fieldScore(entity, audio.getGenre(), 950)));
    }

    private double fieldScore(String entity, String field, double baseScore) {
        String normalizedField = normalize(field);
        if (normalizedField.isEmpty()) return 0;
        if (normalizedField.equals(entity)) return baseScore + entity.length();
        if (normalizedField.contains(entity)) return baseScore + entity.length() * 0.8;
        if (entity.length() >= 3 && entity.contains(normalizedField)) return baseScore + normalizedField.length() * 0.6;
        return 0;
    }

    private String normalize(String value) {
        String normalized = queryUnderstandingService == null
                ? (value == null ? "" : value)
                : queryUnderstandingService.normalize(value);
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s《》()（）【】,，。！？?、:：·・\\-~～]", "");
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
