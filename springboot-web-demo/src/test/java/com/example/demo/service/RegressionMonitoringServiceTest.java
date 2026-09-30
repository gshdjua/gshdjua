package com.example.demo.service;

import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.EvaluationDatasetService;
import com.example.demo.service.retrieval.HybridMusicRetriever;
import com.example.demo.service.retrieval.RetrievalResult;
import com.example.demo.service.retrieval.RetrievalSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegressionMonitoringServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EvaluationDatasetService dataset = mock(EvaluationDatasetService.class);
    private final HybridMusicRetriever retriever = mock(HybridMusicRetriever.class);
    private final AudioMapper audioMapper = mock(AudioMapper.class);
    private final VectorRagClient vectorRagClient = mock(VectorRagClient.class);
    private final RegressionMonitoringService service = new RegressionMonitoringService(
            jdbc, dataset, retriever, audioMapper, vectorRagClient);

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void evaluatesPositiveAndNegativeCasesWithRecallAndMrr() {
        when(retriever.retrieve(eq("查找目标歌曲"), anyList(), eq(5))).thenReturn(Arrays.asList(
                new RetrievalResult(1, RetrievalSource.KEYWORD, 1d, "first"),
                new RetrievalResult(3, RetrievalSource.VECTOR, .8d, "second")));
        when(retriever.retrieve(eq("不存在的歌曲"), anyList(), eq(5))).thenReturn(Collections.emptyList());

        RegressionMonitoringService.EvaluationSummary result = service.evaluate(Arrays.asList(
                evaluationCase("positive", "常规", "查找目标歌曲", Arrays.asList(1, 2)),
                evaluationCase("negative", "否定事实", "不存在的歌曲", Collections.emptyList())), 5);

        assertEquals(2, result.totalCases);
        assertEquals(2, result.passedCases);
        assertEquals(0, result.failureCount);
        assertEquals(1d, result.overallAccuracy);
        assertEquals(.5d, result.recallAtK);
        assertEquals(1d, result.mrr);
    }

    @Test
    void manualIndexRebuildDelegatesToVectorService() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", "queued");
        when(vectorRagClient.indexStatus()).thenReturn(status);

        assertEquals("queued", service.rebuildIndex("manual-test").get("state"));
        verify(vectorRagClient).rebuildAsync("manual-test");
    }

    private Map<String, Object> evaluationCase(String id, String category, String question, List<Integer> expected) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("category", category);
        item.put("question", question);
        item.put("expected_audio_ids", expected);
        item.put("evaluation_mode", "standard");
        return item;
    }
}
