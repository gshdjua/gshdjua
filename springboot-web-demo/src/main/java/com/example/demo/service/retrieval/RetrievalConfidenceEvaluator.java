package com.example.demo.service.retrieval;

import com.example.demo.service.AssistantIntent;
import com.example.demo.service.AssistantQueryUnderstandingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RetrievalConfidenceEvaluator {

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    @Value("${retrieval.confidence.enabled:true}")
    private boolean enabled;

    @Value("${retrieval.confidence.retrieval-weight:0.45}")
    private double retrievalWeight;

    @Value("${retrieval.confidence.metadata-weight:0.40}")
    private double metadataWeight;

    @Value("${retrieval.confidence.corroboration-weight:0.15}")
    private double corroborationWeight;

    @Value("${retrieval.confidence.factual-threshold:0.42}")
    private double factualThreshold;

    @Value("${retrieval.confidence.recommendation-threshold:0.30}")
    private double recommendationThreshold;

    @Value("${retrieval.confidence.default-threshold:0.36}")
    private double defaultThreshold;

    public List<RetrievalResult> filterAccepted(String question, List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return new ArrayList<>();
        AssistantIntent intent = queryUnderstandingService.classify(question);
        double threshold = thresholdFor(intent);
        List<RetrievalResult> accepted = new ArrayList<>();
        for (RetrievalResult result : results) {
            double retrievalQuality = retrievalQuality(result.getSourceScores());
            double confidence = retrievalWeight * retrievalQuality
                    + metadataWeight * result.getMetadataScore()
                    + corroborationWeight * result.getCorroborationScore();
            boolean isAccepted = !enabled || confidence >= threshold;

            result.setConfidenceScore(confidence);
            result.setConfidenceThreshold(threshold);
            result.setConfidenceAccepted(isAccepted);
            result.setConfidenceReason(isAccepted
                    ? "置信度达到阈值"
                    : "检索证据不足：来源质量、元数据匹配或多路一致性未达到阈值");
            if (isAccepted) accepted.add(result);
        }
        return accepted;
    }

    private double thresholdFor(AssistantIntent intent) {
        if (intent == AssistantIntent.RECOMMENDATION) return recommendationThreshold;
        if (intent == AssistantIntent.SOURCE_QUERY || intent == AssistantIntent.SONG_METADATA
                || intent == AssistantIntent.GENRE_QUERY || intent == AssistantIntent.LIBRARY_QUERY) {
            return factualThreshold;
        }
        return defaultThreshold;
    }

    private double retrievalQuality(Map<RetrievalSource, Double> sourceScores) {
        double quality = 0;
        for (Map.Entry<RetrievalSource, Double> entry : sourceScores.entrySet()) {
            double sourceQuality;
            if (entry.getKey() == RetrievalSource.SQL_EXACT) {
                sourceQuality = normalizeSql(entry.getValue());
            } else if (entry.getKey() == RetrievalSource.KEYWORD) {
                sourceQuality = clamp(entry.getValue() / 100.0);
            } else {
                sourceQuality = clamp(entry.getValue());
            }
            quality = Math.max(quality, sourceQuality);
        }
        return quality;
    }

    private double normalizeSql(double score) {
        if (score >= 1000) return 1.0;
        if (score >= 800) return 0.9;
        if (score >= 600) return 0.75;
        return clamp(score / 800.0);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
