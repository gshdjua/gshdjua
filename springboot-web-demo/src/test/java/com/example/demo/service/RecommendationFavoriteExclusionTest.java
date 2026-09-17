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

    @Test
    void relaxedAnimeCandidatesRequireAnimeProvenanceButNotAMoodTag() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        AssistantQueryUnderstandingService understanding = new AssistantQueryUnderstandingService();
        VectorRagClient vectorRagClient = mock(VectorRagClient.class);
        MusicRagRetriever keywordRetriever = mock(MusicRagRetriever.class);
        MusicLibraryAgent agent = new MusicLibraryAgent();
        ReflectionTestUtils.setField(agent, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(agent, "queryUnderstandingService", understanding);
        ReflectionTestUtils.setField(agent, "vectorRagClient", vectorRagClient);
        ReflectionTestUtils.setField(agent, "musicRagRetriever", keywordRetriever);

        Audio flowerDance = audio(1, "Flower Dance", "DJ Okawari", "动漫,轻音乐");
        flowerDance.setIntroduction("钢琴旋律舒缓，但本地未填写动画出处");
        Audio energeticAnime = audio(2, "激昂动画歌曲", "歌手A", "动漫");
        energeticAnime.setSource("TV动画《作品A》");
        energeticAnime.setIntroduction("激烈的摇滚和热血节奏");
        Audio relaxedNonAnime = audio(3, "普通钢琴曲", "歌手B", "轻音乐");
        relaxedNonAnime.setIntroduction("轻快舒缓的钢琴曲");
        Audio relaxedAnime = audio(4, "轻快动画歌曲", "歌手C", "动漫");
        relaxedAnime.setSource("TV动画《作品B》");
        relaxedAnime.setIntroduction("旋律轻快，适合轻松时听");
        String question = "推荐三首轻松的动漫歌曲";
        when(audioMapper.selectAll()).thenReturn(java.util.Arrays.asList(flowerDance, energeticAnime, relaxedNonAnime, relaxedAnime));
        when(audioMapper.selectUserCollects(10)).thenReturn(Collections.emptyList());
        when(vectorRagClient.search(understanding.normalize(question), 20)).thenReturn(Collections.singletonList(flowerDance));
        when(keywordRetriever.retrieve(question, null, 20)).thenReturn(Collections.emptyList());

        assertEquals(Collections.singletonList("动漫"), understanding.requestedGenres(understanding.normalize(question)));
        MusicLibraryAgent.RecommendationOutcome outcome = agent.getRecommendationOutcome(
                understanding.normalize(question), 10, 3, Collections.emptySet());
        assertEquals(java.util.Arrays.asList(4, 2), outcome.getSongs().stream().map(Audio::getId)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(2, outcome.getAvailableCount());
        assertTrue(agent.formatRecommendationReply(question, outcome).contains("只有 2 首"));
    }

    @Test
    void recommendationOutcomeExplainsFavoriteShortfallAndReturnsEveryAvailableCandidate() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        AssistantQueryUnderstandingService understanding = new AssistantQueryUnderstandingService();
        VectorRagClient vectorRagClient = mock(VectorRagClient.class);
        MusicRagRetriever keywordRetriever = mock(MusicRagRetriever.class);
        MusicLibraryAgent agent = new MusicLibraryAgent();
        ReflectionTestUtils.setField(agent, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(agent, "queryUnderstandingService", understanding);
        ReflectionTestUtils.setField(agent, "vectorRagClient", vectorRagClient);
        ReflectionTestUtils.setField(agent, "musicRagRetriever", keywordRetriever);

        Audio favorite1 = audio(1, "收藏1", "歌手", "动漫");
        Audio favorite2 = audio(2, "收藏2", "歌手", "动漫");
        Audio favorite3 = audio(3, "收藏3", "歌手", "动漫");
        Audio candidate1 = audio(4, "候选1", "歌手", "动漫");
        Audio candidate2 = audio(5, "候选2", "歌手", "动漫");
        Audio candidate3 = audio(6, "候选3", "歌手", "动漫");
        Audio candidate4 = audio(7, "候选4", "歌手", "动漫");
        String question = "请给我推荐5首动漫类型的歌曲";
        when(audioMapper.selectAll()).thenReturn(java.util.Arrays.asList(
                favorite1, favorite2, favorite3, candidate1, candidate2, candidate3, candidate4));
        when(audioMapper.selectUserCollects(10)).thenReturn(java.util.Arrays.asList(favorite1, favorite2, favorite3));
        when(vectorRagClient.search(understanding.normalize(question), 20)).thenReturn(Collections.emptyList());
        when(keywordRetriever.retrieve(question, null, 20)).thenReturn(Collections.emptyList());

        MusicLibraryAgent.RecommendationOutcome outcome = agent.getRecommendationOutcome(
                question, 10, 5, Collections.emptySet());
        String reply = agent.formatRecommendationReply(question, outcome);

        assertEquals(5, outcome.getRequestedCount());
        assertEquals(4, outcome.getSongs().size());
        assertEquals(3, outcome.getFavoriteExcludedCount());
        assertEquals("FAVORITES_EXCLUDED", outcome.getShortfallReason());
        assertTrue(reply.contains("3 首已在你的收藏中"));
        assertTrue(reply.contains("目前只有 4 首可推荐"));
    }

    @Test
    void favoriteGenreQueryFiltersCurrentUsersFavoritesAndExplainsTruncatedDisplay() {
        AudioMapper audioMapper = mock(AudioMapper.class);
        MusicLibraryAgent agent = new MusicLibraryAgent();
        ReflectionTestUtils.setField(agent, "audioMapper", audioMapper);
        ReflectionTestUtils.setField(agent, "queryUnderstandingService", new AssistantQueryUnderstandingService());

        List<Audio> favorites = new java.util.ArrayList<>();
        for (int index = 1; index <= 7; index++) {
            favorites.add(audio(index, "动漫收藏" + index, "歌手" + index, "动漫"));
        }
        favorites.add(audio(8, "摇滚收藏", "摇滚歌手", "摇滚"));
        when(audioMapper.selectAll()).thenReturn(favorites);
        when(audioMapper.selectUserCollects(10)).thenReturn(favorites);

        String reply = agent.reply("我的收藏里动漫类型的歌曲有几首", 10, AssistantIntent.FAVORITES);

        assertTrue(reply.contains("你的收藏中同时属于“动漫”类型的歌曲有 7 首"));
        assertTrue(reply.contains("以下展示前 6 首"));
        assertFalse(reply.contains("摇滚收藏"));
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
