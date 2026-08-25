package com.example.demo.service.retrieval;

import com.example.demo.service.AssistantQueryUnderstandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalConfidenceEvaluatorTest {

    private RetrievalConfidenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RetrievalConfidenceEvaluator();
        ReflectionTestUtils.setField(evaluator, "queryUnderstandingService", new AssistantQueryUnderstandingService());
        ReflectionTestUtils.setField(evaluator, "enabled", true);
        ReflectionTestUtils.setField(evaluator, "retrievalWeight", 0.45);
        ReflectionTestUtils.setField(evaluator, "metadataWeight", 0.40);
        ReflectionTestUtils.setField(evaluator, "corroborationWeight", 0.15);
        ReflectionTestUtils.setField(evaluator, "factualThreshold", 0.42);
        ReflectionTestUtils.setField(evaluator, "recommendationThreshold", 0.30);
        ReflectionTestUtils.setField(evaluator, "defaultThreshold", 0.36);
    }

    @Test
    void acceptsExactFactualEvidence() {
        RetrievalResult result = new RetrievalResult(1, RetrievalSource.SQL_EXACT, 1008, "Good knows");
        result.addSource(RetrievalSource.KEYWORD, 100);
        result.setMetadataScore(0.75);
        result.setCorroborationScore(2.0 / 3.0);

        List<RetrievalResult> accepted = evaluator.filterAccepted("Good knows的出处是什么？",
                Collections.singletonList(result));

        assertEquals(1, accepted.size());
        assertTrue(result.isConfidenceAccepted());
        assertTrue(result.getConfidenceScore() >= result.getConfidenceThreshold());
    }

    @Test
    void rejectsWeakSingleVectorCandidate() {
        RetrievalResult result = new RetrievalResult(4, RetrievalSource.VECTOR, 0.18, "weak match");
        result.setMetadataScore(0.05);
        result.setCorroborationScore(1.0 / 3.0);

        List<RetrievalResult> accepted = evaluator.filterAccepted("推荐一首完全未知风格的歌曲",
                Collections.singletonList(result));

        assertTrue(accepted.isEmpty());
        assertFalse(result.isConfidenceAccepted());
        assertTrue(result.getConfidenceScore() < result.getConfidenceThreshold());
    }
}
