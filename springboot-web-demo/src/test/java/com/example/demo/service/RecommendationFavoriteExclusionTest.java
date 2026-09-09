package com.example.demo.service;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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

    @Test
    void moodRecommendationUsesMoodMetadataInsteadOfPersonalizedRanking() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        PersonalizedRecommendationService recommendationService = mock(PersonalizedRecommendationService.class);
        AssistantQueryUnderstandingService queryUnderstandingService = new AssistantQueryUnderstandingService();
        VectorRagClient vectorRagClient = mock(VectorRagClient.class);
        MusicRagRetriever musicRagRetriever = mock(MusicRagRetriever.class);
        MusicLibraryAgent agent = new MusicLibraryAgent();
        ReflectionTestUtils.setField(agent, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(agent, "personalizedRecommendationService", recommendationService);
        ReflectionTestUtils.setField(agent, "queryUnderstandingService", queryUnderstandingService);
        ReflectionTestUtils.setField(agent, "vectorRagClient", vectorRagClient);
        ReflectionTestUtils.setField(agent, "musicRagRetriever", musicRagRetriever);

        Audio energetic = audio(1, "激昂歌曲", "歌手A", "摇滚");
        energetic.setIntroduction("节奏激烈的热血歌曲");
        Audio relaxing = audio(2, "舒缓歌曲", "歌手B", "轻音乐");
        relaxing.setIntroduction("温柔舒缓的钢琴曲，适合放松");
        when(audioMapper.selectAll()).thenReturn(java.util.Arrays.asList(energetic, relaxing));
        when(audioMapper.selectUserCollects(10)).thenReturn(Collections.emptyList());
        when(vectorRagClient.search("请给我推荐一些轻松的歌曲", 20)).thenReturn(Collections.emptyList());
        when(musicRagRetriever.retrieve("请给我推荐一些轻松的歌曲", null, 20)).thenReturn(Collections.emptyList());
        when(vectorRagClient.search("咱们歌库里有轻松的音乐吗？", 20)).thenReturn(Collections.emptyList());
        when(musicRagRetriever.retrieve("咱们歌库里有轻松的音乐吗？", null, 20)).thenReturn(Collections.emptyList());

        List<Audio> recommendations = agent.getRecommendationsForQuery("请给我推荐一些轻松的歌曲", 10, 2);
        List<Audio> naturalQuestionRecommendations = agent.getRecommendationsForQuery("咱们歌库里有轻松的音乐吗？", 10, 2);
        String reply = agent.reply("请给我推荐一些轻松的歌曲", 10, AssistantIntent.RECOMMENDATION);

        assertEquals(Integer.valueOf(2), recommendations.get(0).getId());
        assertEquals(Collections.singletonList(2), naturalQuestionRecommendations.stream()
                .map(Audio::getId).collect(java.util.stream.Collectors.toList()));
        assertTrue(reply.contains("按“轻松”的听感"));
        assertFalse(reply.contains("收藏和播放偏好"));
        verify(recommendationService, never()).recommend(10, 2);
    }

    @Test
    void multiGenreRecommendationRequiresEveryRequestedGenre() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        PersonalizedRecommendationService recommendationService = mock(PersonalizedRecommendationService.class);
        AssistantQueryUnderstandingService queryUnderstandingService = new AssistantQueryUnderstandingService();
        VectorRagClient vectorRagClient = mock(VectorRagClient.class);
        MusicRagRetriever musicRagRetriever = mock(MusicRagRetriever.class);
        MusicLibraryAgent agent = new MusicLibraryAgent();
        ReflectionTestUtils.setField(agent, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(agent, "personalizedRecommendationService", recommendationService);
        ReflectionTestUtils.setField(agent, "queryUnderstandingService", queryUnderstandingService);
        ReflectionTestUtils.setField(agent, "vectorRagClient", vectorRagClient);
        ReflectionTestUtils.setField(agent, "musicRagRetriever", musicRagRetriever);

        Audio animeOnly = audio(1, "动漫歌曲", "歌手A", "动漫");
        Audio lightOnly = audio(2, "轻音乐歌曲", "歌手B", "轻音乐");
        Audio both = audio(3, "动漫轻音乐", "歌手C", "动漫,轻音乐");
        String question = "推荐动漫类型的轻音乐歌曲";
        when(audioMapper.selectAll()).thenReturn(java.util.Arrays.asList(animeOnly, lightOnly, both));
        when(audioMapper.selectUserCollects(10)).thenReturn(Collections.emptyList());
        when(vectorRagClient.search(queryUnderstandingService.normalize(question), 20)).thenReturn(Collections.emptyList());
        when(musicRagRetriever.retrieve(question, null, 20)).thenReturn(Collections.emptyList());

        List<Audio> recommendations = agent.getRecommendationsForQuery(question, 10, 5);

        assertEquals(Collections.singletonList(3), recommendations.stream().map(Audio::getId).collect(java.util.stream.Collectors.toList()));
        verify(recommendationService, never()).recommend(10, 5);
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
