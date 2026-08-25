package com.example.demo.service.retrieval;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.AssistantQueryUnderstandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrictEntityRetrieverTest {

    private StrictEntityRetriever retriever;

    @BeforeEach
    void setUp() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        when(audioMapper.selectAll()).thenReturn(Arrays.asList(
                audio(1, "Good knows", "平野绫", "动漫", "动画《凉宫春日的忧郁》插曲", 1),
                audio(2, "前前前世", "Ayasa", "动漫", null, 0)
        ));
        retriever = new StrictEntityRetriever();
        ReflectionTestUtils.setField(retriever, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(retriever, "queryUnderstandingService", new AssistantQueryUnderstandingService());
    }

    @Test
    void returnsEmptyWhenExplicitEntityDoesNotExist() {
        List<RetrievalResult> results = retriever.retrieve(
                new StructuredEntityQuery(true, EntityType.SINGER, "周杰伦"), 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void onlyReturnsSongsMatchingRequestedSource() {
        List<RetrievalResult> results = retriever.retrieve(
                new StructuredEntityQuery(true, EntityType.SOURCE, "凉宫春日的忧郁"), 5);

        assertEquals(1, results.size());
        assertEquals(1, results.get(0).getAudioId());
        assertEquals("STRICT_ENTITY_SOURCE", results.get(0).getFusionProfile());
    }

    @Test
    void normalizesAnimeGenreSynonymsBeforeStrictMatching() {
        List<RetrievalResult> results = retriever.retrieve(
                new StructuredEntityQuery(true, EntityType.GENRE, "动画"), 5);

        assertEquals(2, results.size());
    }

    @Test
    void filtersExactCollectionCountIndependentlyOfSemanticRanking() {
        List<RetrievalResult> results = retriever.retrieve(
                new StructuredEntityQuery(true, EntityType.COLLECTION_COUNT, "0"), 3);

        assertEquals(1, results.size());
        assertEquals(2, results.get(0).getAudioId());
    }

    private Audio audio(int id, String songName, String singer, String genre, String source, int collectCount) {
        Audio audio = new Audio();
        audio.setId(id);
        audio.setSongName(songName);
        audio.setSinger(singer);
        audio.setGenre(genre);
        audio.setSource(source);
        audio.setCollectCount(collectCount);
        return audio;
    }
}
