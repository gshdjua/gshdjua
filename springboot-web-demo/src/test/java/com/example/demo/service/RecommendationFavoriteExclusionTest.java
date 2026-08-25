package com.example.demo.service;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationFavoriteExclusionTest {

    @Test
    void personalizedRecommendationUsesFavoritesAsPreferenceButNotAsCandidates() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        PersonalizedRecommendationService service = new PersonalizedRecommendationService();
        ReflectionTestUtils.setField(service, "audioMapper", audioMapper);

        Audio favorite = audio(1, "已收藏歌曲", "歌手A", "动漫");
        Audio candidate = audio(2, "未收藏歌曲", "歌手B", "动漫");
        when(audioMapper.selectUserCollects(10)).thenReturn(Collections.singletonList(favorite));
        when(audioMapper.selectUserGenrePlayCounts(10)).thenReturn(Collections.emptyList());
        when(audioMapper.selectRecommendedForUser(10)).thenReturn(Collections.singletonList(candidate));

        List<Audio> recommendations = service.recommend(10, 3);

        assertEquals(Collections.singletonList(2), recommendations.stream().map(Audio::getId).collect(java.util.stream.Collectors.toList()));
        verify(audioMapper).selectRecommendedForUser(10);
    }

    @Test
    void agentRemovesFavoritesEvenWhenUpstreamRecommendationContainsThem() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        PersonalizedRecommendationService recommendationService = mock(PersonalizedRecommendationService.class);
        AssistantQueryUnderstandingService queryUnderstandingService = mock(AssistantQueryUnderstandingService.class);
        MusicLibraryAgent agent = new MusicLibraryAgent();
        ReflectionTestUtils.setField(agent, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(agent, "personalizedRecommendationService", recommendationService);
        ReflectionTestUtils.setField(agent, "queryUnderstandingService", queryUnderstandingService);

        Audio favorite = audio(1, "已收藏歌曲", "歌手A", "动漫");
        Audio candidate = audio(2, "未收藏歌曲", "歌手B", "动漫");
        when(audioMapper.selectAll()).thenReturn(java.util.Arrays.asList(favorite, candidate));
        when(audioMapper.selectUserCollects(10)).thenReturn(Collections.singletonList(favorite));
        when(recommendationService.recommend(10, 3)).thenReturn(java.util.Arrays.asList(favorite, candidate));
        when(queryUnderstandingService.classify("推荐热门歌曲")).thenReturn(AssistantIntent.RECOMMENDATION);
        when(queryUnderstandingService.normalize("推荐热门歌曲")).thenReturn("推荐热门歌曲");

        List<Audio> recommendations = agent.getRecommendationsForQuery("推荐热门歌曲", 10, 3);

        assertEquals(1, recommendations.size());
        assertEquals(Integer.valueOf(2), recommendations.get(0).getId());
        assertFalse(recommendations.stream().anyMatch(audio -> audio.getId().equals(favorite.getId())));
    }

    private Audio audio(int id, String songName, String singer, String genre) {
        Audio audio = new Audio();
        audio.setId(id);
        audio.setSongName(songName);
        audio.setSinger(singer);
        audio.setGenre(genre);
        audio.setCollectCount(0);
        return audio;
    }
}
