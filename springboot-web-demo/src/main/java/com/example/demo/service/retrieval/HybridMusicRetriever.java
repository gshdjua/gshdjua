package com.example.demo.service.retrieval;

import com.example.demo.service.AssistantIntent;
import com.example.demo.service.AssistantQueryUnderstandingService;
import com.example.demo.service.MusicRagRetriever;
import com.example.demo.service.VectorRagClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HybridMusicRetriever {

    @Autowired
    private SqlExactRetriever sqlExactRetriever;

    @Autowired
    private MusicRagRetriever keywordRetriever;

    @Autowired
    private VectorRagClient vectorRetriever;

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    @Autowired
    private MetadataReranker metadataReranker;

    @Autowired
    private RetrievalConfidenceEvaluator confidenceEvaluator;

    @Autowired
    private EntityQueryParser entityQueryParser;

    @Autowired
    private StrictEntityRetriever strictEntityRetriever;

    @Value("${retrieval.fusion.rrf-k:60.0}")
    private double rrfK;

    @Value("${retrieval.fusion.sql-weight:3.0}")
    private double sqlWeight;

    @Value("${retrieval.fusion.vector-weight:1.5}")
    private double vectorWeight;

    @Value("${retrieval.fusion.keyword-weight:1.0}")
    private double keywordWeight;

    @Value("${retrieval.fusion.factual.sql-weight:4.0}")
    private double factualSqlWeight;

    @Value("${retrieval.fusion.factual.vector-weight:1.0}")
    private double factualVectorWeight;

    @Value("${retrieval.fusion.factual.keyword-weight:1.5}")
    private double factualKeywordWeight;

    @Value("${retrieval.fusion.recommendation.sql-weight:1.5}")
    private double recommendationSqlWeight;

    @Value("${retrieval.fusion.recommendation.vector-weight:3.0}")
    private double recommendationVectorWeight;

    @Value("${retrieval.fusion.recommendation.keyword-weight:2.0}")
    private double recommendationKeywordWeight;

    public List<RetrievalResult> retrieve(String question, List<Map<String, String>> history, int topK) {
        return retrieve(question, history, Collections.emptySet(), topK);
    }

    public List<RetrievalResult> retrieve(String question, List<Map<String, String>> history,
                                          Set<Integer> excludedAudioIds, int topK) {
        if (question == null || question.trim().isEmpty() || topK < 1) return Collections.emptyList();
        StructuredEntityQuery entityQuery = entityQueryParser.parse(question);
        if (entityQuery.isStrict()) {
            if (!entityQuery.hasEntity()) return Collections.emptyList();
            List<RetrievalResult> strictResults = strictEntityRetriever.retrieve(entityQuery, topK);
            for (int index = 0; index < strictResults.size(); index++) {
                strictResults.get(index).setCitationId("S" + (index + 1));
            }
            return strictResults;
        }
        int candidateLimit = Math.min(20, Math.max(10, topK * 3));
        Set<Integer> excluded = excludedAudioIds == null ? Collections.emptySet() : excludedAudioIds;
        Map<Integer, RetrievalResult> merged = new LinkedHashMap<>();
        FusionWeights weights = selectWeights(question);

        merge(merged, sqlExactRetriever.retrieve(question, candidateLimit), weights.sqlWeight, weights.profile, excluded);
        merge(merged, vectorRetriever.searchResults(question, candidateLimit), weights.vectorWeight, weights.profile, excluded);
        merge(merged, keywordRetriever.retrieveResults(question, history, candidateLimit), weights.keywordWeight, weights.profile, excluded);

        List<RetrievalResult> results = new ArrayList<>(merged.values());
        metadataReranker.rerank(question, results);
        results = confidenceEvaluator.filterAccepted(question, results);
        results.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed()
                .thenComparing(RetrievalResult::getAudioId));
        List<RetrievalResult> limitedResults = new ArrayList<>(results.subList(0, Math.min(topK, results.size())));
        for (int index = 0; index < limitedResults.size(); index++) {
            limitedResults.get(index).setCitationId("S" + (index + 1));
        }
        return limitedResults;
    }

    private void merge(Map<Integer, RetrievalResult> merged, List<RetrievalResult> sourceResults,
                       double sourceWeight, String fusionProfile, Set<Integer> excludedAudioIds) {
        for (int index = 0; index < sourceResults.size(); index++) {
            RetrievalResult sourceResult = sourceResults.get(index);
            if (sourceResult.getAudioId() == null || excludedAudioIds.contains(sourceResult.getAudioId())) continue;
            double contribution = sourceWeight / (rrfK + index + 1);
            RetrievalResult fused = merged.get(sourceResult.getAudioId());
            if (fused == null) {
                fused = new RetrievalResult();
                fused.setAudioId(sourceResult.getAudioId());
                fused.setSource(sourceResult.getSource());
                fused.setEvidence(sourceResult.getEvidence());
                fused.setFusionProfile(fusionProfile);
                merged.put(sourceResult.getAudioId(), fused);
            } else if (priority(sourceResult.getSource()) > priority(fused.getSource())) {
                fused.setSource(sourceResult.getSource());
                fused.setEvidence(sourceResult.getEvidence());
            }
            fused.addSource(sourceResult.getSource(), sourceResult.getScore());
            fused.addFusionContribution(sourceResult.getSource(), contribution);
            fused.setScore(fused.getScore() + contribution);
        }
    }

    private FusionWeights selectWeights(String question) {
        AssistantIntent intent = queryUnderstandingService.classify(question);
        if (intent == AssistantIntent.RECOMMENDATION) {
            return new FusionWeights("RECOMMENDATION", recommendationSqlWeight,
                    recommendationVectorWeight, recommendationKeywordWeight);
        }
        if (intent == AssistantIntent.SOURCE_QUERY || intent == AssistantIntent.SONG_METADATA
                || intent == AssistantIntent.GENRE_QUERY || intent == AssistantIntent.LIBRARY_QUERY) {
            return new FusionWeights("FACTUAL", factualSqlWeight, factualVectorWeight, factualKeywordWeight);
        }
        return new FusionWeights("DEFAULT", sqlWeight, vectorWeight, keywordWeight);
    }

    private int priority(RetrievalSource source) {
        if (source == RetrievalSource.SQL_EXACT) return 3;
        if (source == RetrievalSource.VECTOR) return 2;
        return 1;
    }

    private static class FusionWeights {
        private final String profile;
        private final double sqlWeight;
        private final double vectorWeight;
        private final double keywordWeight;

        private FusionWeights(String profile, double sqlWeight, double vectorWeight, double keywordWeight) {
            this.profile = profile;
            this.sqlWeight = sqlWeight;
            this.vectorWeight = vectorWeight;
            this.keywordWeight = keywordWeight;
        }
    }
}
