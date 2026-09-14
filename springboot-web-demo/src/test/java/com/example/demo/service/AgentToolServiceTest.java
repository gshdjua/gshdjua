package com.example.demo.service;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.retrieval.RetrievalResult;
import com.example.demo.service.retrieval.RetrievalSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void songSearchReturnsOnlySafeStructuredMatches() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        AgentToolService service = new AgentToolService();
        ReflectionTestUtils.setField(service, "audioMapper", audioMapper);

        Audio anime = audio(1, "Flower Dance", "DJ Okawari", "动漫,轻音乐", 8);
        anime.setSavePath("private/local/path.ogg");
        Audio jazz = audio(2, "Autumn Leaves", "Bill Evans", "爵士", 3);
        when(audioMapper.selectAll()).thenReturn(Arrays.asList(jazz, anime));

        Map<String, Object> result = service.searchSongs("请推荐动漫歌曲", 5);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertEquals(1, items.size());
        assertEquals("Flower Dance", items.get(0).get("songName"));
        assertFalse(items.get(0).containsKey("savePath"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void favoriteSearchUsesServerUserAndFiltersSafely() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        AgentToolService service = new AgentToolService();
        ReflectionTestUtils.setField(service, "audioMapper", audioMapper);
        Audio anime = audio(1, "Flower Dance", "DJ Okawari", "动漫,轻音乐", 8);
        anime.setSavePath("private/local/path.ogg");
        Audio jazz = audio(2, "Autumn Leaves", "Bill Evans", "爵士", 3);
        when(audioMapper.selectUserCollects(7)).thenReturn(Arrays.asList(jazz, anime));

        Map<String, Object> result = service.searchFavorites(7, "动漫", 5);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertEquals(1, items.size());
        assertEquals("Flower Dance", items.get(0).get("songName"));
        assertFalse(items.get(0).containsKey("savePath"));
        verify(audioMapper).selectUserCollects(7);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recommendSongsReusesExistingBusinessRecommendationAndExclusions() {
        MusicLibraryAgent musicLibraryAgent = mock(MusicLibraryAgent.class);
        AgentToolService service = new AgentToolService();
        ReflectionTestUtils.setField(service, "musicLibraryAgent", musicLibraryAgent);
        Audio recommended = audio(3, "Anime Song", "Singer", "动漫", 4);
        LinkedHashSet<Integer> excluded = new LinkedHashSet<>(Collections.singletonList(1));
        MusicLibraryAgent.RecommendationOutcome outcome = new MusicLibraryAgent.RecommendationOutcome(
                3, 1, 0, "INSUFFICIENT_MATCHES", Collections.singletonList(recommended));
        when(musicLibraryAgent.getRecommendationOutcome("轻快动漫歌曲", 7, 3, excluded))
                .thenReturn(outcome);

        Map<String, Object> result = service.recommendSongs(7, "轻快动漫歌曲", 3,
                Collections.singletonList(1));
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertEquals(1, items.size());
        assertEquals(3, items.get(0).get("id"));
        verify(musicLibraryAgent).getRecommendationOutcome("轻快动漫歌曲", 7, 3, excluded);
    }

    @Test
    @SuppressWarnings("unchecked")
    void vectorSearchKeepsSemanticEvidenceButReturnsSafeSongFields() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        VectorRagClient vectorRagClient = mock(VectorRagClient.class);
        AgentToolService service = new AgentToolService();
        ReflectionTestUtils.setField(service, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(service, "vectorRagClient", vectorRagClient);
        Audio song = audio(4, "Rain", "Singer", "轻音乐", 2);
        song.setSavePath("private/rain.ogg");
        RetrievalResult match = new RetrievalResult(4, RetrievalSource.VECTOR, 0.82, "适合雨夜的舒缓音乐");
        when(vectorRagClient.searchResults("适合雨夜", 3, Collections.singletonList(4)))
                .thenReturn(Collections.singletonList(match));
        when(audioMapper.selectById(4)).thenReturn(song);

        Map<String, Object> result = service.vectorSearch("适合雨夜", 3, Collections.singletonList(4));
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertEquals(0.82, items.get(0).get("semanticScore"));
        assertEquals("适合雨夜的舒缓音乐", items.get(0).get("evidence"));
        assertFalse(items.get(0).containsKey("savePath"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void songDetailSupportsIdAndDoesNotExposeStoragePath() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        AgentToolService service = new AgentToolService();
        ReflectionTestUtils.setField(service, "audioMapper", audioMapper);
        Audio song = audio(5, "RAGE OF DUST", "SPYAIR", "动漫,摇滚", 9);
        song.setSavePath("private/rage.ogg");
        when(audioMapper.selectById(5)).thenReturn(song);

        Map<String, Object> result = service.songDetail(5, "");
        Map<String, Object> item = (Map<String, Object>) result.get("item");

        assertEquals(true, result.get("found"));
        assertEquals("RAGE OF DUST", item.get("songName"));
        assertFalse(item.containsKey("savePath"));

        when(audioMapper.selectAll()).thenReturn(Collections.singletonList(song));
        Map<String, Object> byName = service.songDetail(null, "RAGE OF DUST - SPYAIR");
        assertEquals(true, byName.get("found"));
    }

    private Audio audio(int id, String songName, String singer, String genre, int collectCount) {
        Audio audio = new Audio();
        audio.setId(id);
        audio.setSongName(songName);
        audio.setSinger(singer);
        audio.setGenre(genre);
        audio.setCollectCount(collectCount);
        return audio;
    }
}
