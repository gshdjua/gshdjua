package com.example.demo.service.retrieval;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.AssistantQueryUnderstandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataRerankerTest {

    @Mock
    private AudioMapper audioMapper;

    private MetadataReranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new MetadataReranker();
        ReflectionTestUtils.setField(reranker, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(reranker, "queryUnderstandingService", new AssistantQueryUnderstandingService());
        ReflectionTestUtils.setField(reranker, "enabled", true);
        ReflectionTestUtils.setField(reranker, "fusionWeight", 0.55);
        ReflectionTestUtils.setField(reranker, "metadataWeight", 0.35);
        ReflectionTestUtils.setField(reranker, "corroborationWeight", 0.10);
    }

    @Test
    void sourceQueryPromotesExactSourceAndPenalizesMissingSource() {
        Audio matching = audio(1, "Good knows", "平野绫", "动漫", "动画《凉宫春日的忧郁》插曲", "校园演唱会曲目");
        Audio missing = audio(2, "前前前世", "Ayasa", "动漫", "", "");
        when(audioMapper.selectById(1)).thenReturn(matching);
        when(audioMapper.selectById(2)).thenReturn(missing);

        RetrievalResult matchingResult = result(1, 0.08);
        RetrievalResult missingResult = result(2, 0.08);
        List<RetrievalResult> results = new ArrayList<>(Arrays.asList(missingResult, matchingResult));

        reranker.rerank("《凉宫春日的忧郁》这部动画有哪些歌曲？", results);

        assertTrue(matchingResult.getRerankScore() > missingResult.getRerankScore());
        assertTrue(matchingResult.getRerankReasons().contains("出处匹配"));
        assertTrue(missingResult.getRerankReasons().contains("出处缺失降权"));
    }

    @Test
    void recommendationPromotesIntroductionSemanticMatch() {
        Audio matching = audio(7, "チカっとチカ千花っ", "小原好美", "动漫",
                "动画《辉夜大小姐想让我告白》插曲", "千花书记可爱的舞蹈，被称为书记舞");
        Audio unrelated = audio(4, "RAGE OF DUST", "SPYAIR", "动漫", "", "");
        when(audioMapper.selectById(7)).thenReturn(matching);
        when(audioMapper.selectById(4)).thenReturn(unrelated);

        RetrievalResult matchingResult = result(7, 0.08);
        RetrievalResult unrelatedResult = result(4, 0.08);
        List<RetrievalResult> results = new ArrayList<>(Arrays.asList(unrelatedResult, matchingResult));

        reranker.rerank("推荐一首和书记舞相关的动漫歌曲", results);

        assertTrue(matchingResult.getRerankScore() > unrelatedResult.getRerankScore());
        assertTrue(matchingResult.getRerankReasons().contains("简介语义匹配"));
    }

    private RetrievalResult result(int audioId, double score) {
        RetrievalResult result = new RetrievalResult(audioId, RetrievalSource.KEYWORD, score, "test");
        result.setScore(score);
        return result;
    }

    private Audio audio(int id, String songName, String singer, String genre, String source, String introduction) {
        Audio audio = new Audio();
        audio.setId(id);
        audio.setSongName(songName);
        audio.setSinger(singer);
        audio.setGenre(genre);
        audio.setSource(source);
        audio.setIntroduction(introduction);
        return audio;
    }
}
