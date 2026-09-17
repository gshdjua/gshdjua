package com.example.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantQueryUnderstandingServiceTest {

    private final AssistantQueryUnderstandingService service = new AssistantQueryUnderstandingService();

    @Test
    void classifiesSongIntroductionAsMetadataQuery() {
        assertEquals(AssistantIntent.SONG_METADATA, service.classify("介绍一下Good knows这首歌"));
        assertEquals(AssistantIntent.SONG_METADATA, service.classify("介绍这首音乐的背景"));
        assertEquals(AssistantIntent.SONG_METADATA, service.classify("Good knows这首歌有什么特点？"));
    }

    @Test
    void doesNotTreatUnrelatedIntroductionAsSongMetadata() {
        assertEquals(AssistantIntent.GENERAL, service.classify("介绍一下MusicHub"));
    }

    @Test
    void separatesGenreConstraintsFromMoodWords() {
        assertEquals(AssistantIntent.RECOMMENDATION, service.classify("请推荐几首轻音乐歌曲"));
        assertEquals(AssistantIntent.RECOMMENDATION, service.classify("推荐动漫类型的轻音乐歌曲"));
        assertEquals(AssistantIntent.RECOMMENDATION, service.classify("咱们歌库里有轻松的音乐吗？"));
        assertEquals(AssistantIntent.RECOMMENDATION, service.classify("想听点舒缓的歌"));
        assertEquals(AssistantIntent.GENERAL, service.classify("我喜欢轻松的音乐"));
        assertEquals(java.util.Arrays.asList("轻音乐", "动漫"), service.requestedGenres("推荐动漫类型的轻音乐歌曲"));
        assertTrue(service.requestedGenres("请推荐一些轻松的歌曲").isEmpty());
        String normalized = service.normalize("推荐三首轻松的动漫歌曲");
        assertEquals("推荐三首轻松的动画歌曲", normalized);
        assertEquals(java.util.Collections.singletonList("动漫"), service.requestedGenres(normalized));
    }

    @Test
    void personalFavoriteScopeTakesPriorityOverGenreQuery() {
        assertEquals(AssistantIntent.FAVORITES, service.classify("我的收藏里动漫类型的歌曲有几首"));
        assertEquals(AssistantIntent.FAVORITES, service.classify("我收藏的摇滚歌曲有哪些"));
        assertEquals(AssistantIntent.GENRE_QUERY, service.classify("歌库中动漫类型的歌曲有几首"));
    }
}
