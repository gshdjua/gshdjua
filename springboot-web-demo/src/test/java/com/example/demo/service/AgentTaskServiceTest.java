package com.example.demo.service;

import com.example.demo.entity.AgentTaskPlan;
import com.example.demo.entity.Audio;
import com.example.demo.entity.UserPlaylist;
import com.example.demo.mapper.AgentTaskMapper;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.PlaylistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {
    private AgentTaskService service;
    private AgentTaskMapper taskMapper;
    private PlaylistMapper playlistMapper;
    private AudioMapper audioMapper;

    @BeforeEach
    void setUp() {
        service = new AgentTaskService();
        taskMapper = mock(AgentTaskMapper.class);
        playlistMapper = mock(PlaylistMapper.class);
        audioMapper = mock(AudioMapper.class);
        ReflectionTestUtils.setField(service, "agentTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "playlistMapper", playlistMapper);
        ReflectionTestUtils.setField(service, "audioMapper", audioMapper);
    }

    @Test
    void createsPendingPlanFromPlaylistRequest() {
        doAnswer(invocation -> {
            ((AgentTaskPlan) invocation.getArgument(0)).setId(41L);
            return null;
        }).when(taskMapper).insert(any(AgentTaskPlan.class));

        AgentTaskPlan plan = service.createPlaylistPlan(7, 9L, 11L,
                "根据我的收藏创建一个“雨夜动漫”的歌单，加入三首歌",
                Arrays.asList(audio(1, "A"), audio(2, "B"), audio(3, "C"), audio(4, "D")));

        assertNotNull(plan);
        assertEquals("雨夜动漫", plan.getPlaylistName());
        assertEquals("pending", plan.getStatus());
        assertEquals(3, plan.getSongs().size());
        assertNotNull(plan.getConfirmationToken());
        verify(taskMapper).insertSong(41L, 1, 0);
        verify(taskMapper).insertSong(41L, 2, 1);
        verify(taskMapper).insertSong(41L, 3, 2);
    }

    @Test
    void ignoresNormalRecommendationQuestions() {
        AgentTaskPlan plan = service.createPlaylistPlan(7, 9L, 11L,
                "推荐三首适合雨夜的动漫歌曲", Collections.singletonList(audio(1, "A")));
        assertEquals(null, plan);
        verify(taskMapper, never()).insert(any(AgentTaskPlan.class));
    }

    @Test
    void confirmedPlanExecutesOnlyOnce() {
        AgentTaskPlan executing = plan("executing");
        AgentTaskPlan succeeded = plan("succeeded");
        succeeded.setResultPlaylistId(88);
        when(taskMapper.claim(41L, 7, "once")).thenReturn(1);
        when(taskMapper.selectOwned(41L, 7)).thenReturn(executing, succeeded);
        when(taskMapper.selectSongs(41L)).thenReturn(Arrays.asList(audio(1, "A"), audio(2, "B")));
        when(audioMapper.selectById(anyInt())).thenAnswer(invocation -> audio(invocation.getArgument(0), "song"));
        doAnswer(invocation -> {
            ((UserPlaylist) invocation.getArgument(0)).setId(88);
            return null;
        }).when(playlistMapper).insert(any(UserPlaylist.class));
        when(taskMapper.markSucceeded(eq(41L), eq(7), eq(88), anyString())).thenReturn(1);

        AgentTaskPlan result = service.confirm(41L, 7, "once");

        assertEquals("succeeded", result.getStatus());
        assertEquals(88, result.getResultPlaylistId());
        ArgumentCaptor<UserPlaylist> playlist = ArgumentCaptor.forClass(UserPlaylist.class);
        verify(playlistMapper).insert(playlist.capture());
        assertEquals("雨夜动漫", playlist.getValue().getName());
        verify(playlistMapper).insertSong(88, 1, 1);
        verify(playlistMapper).insertSong(88, 2, 2);
    }

    @Test
    void createsAndExecutesFavoritePlan() {
        Audio song = audio(5, "前前前世");
        when(audioMapper.selectAll()).thenReturn(Collections.singletonList(song));
        doAnswer(invocation -> {
            ((AgentTaskPlan) invocation.getArgument(0)).setId(51L);
            return null;
        }).when(taskMapper).insert(any(AgentTaskPlan.class));

        AgentTaskPlan pending = service.createPlan(7, 9L, 12L,
                "帮我收藏《前前前世》", Collections.emptyList(), null);

        assertNotNull(pending);
        assertEquals("favorite_songs", pending.getActionType());
        assertEquals(1, pending.getSongs().size());

        AgentTaskPlan executing = plan("executing");
        executing.setActionType("favorite_songs");
        AgentTaskPlan succeeded = plan("succeeded");
        succeeded.setActionType("favorite_songs");
        succeeded.setResultMessage("已收藏 1 首歌曲");
        when(taskMapper.claim(51L, 7, "confirm")).thenReturn(1);
        when(taskMapper.selectOwned(51L, 7)).thenReturn(executing, succeeded);
        when(taskMapper.selectSongs(51L)).thenReturn(Collections.singletonList(song));
        when(audioMapper.selectById(5)).thenReturn(song);
        when(audioMapper.isCollected(7, 5)).thenReturn(0);
        when(taskMapper.markSucceeded(eq(51L), eq(7), isNull(), anyString())).thenReturn(1);

        AgentTaskPlan result = service.confirm(51L, 7, "confirm");

        assertEquals("succeeded", result.getStatus());
        verify(audioMapper).addCollect(7, 5);
        verify(audioMapper).incrementCollect(5);
    }

    @Test
    void createsPlanForOwnedPlaylistAndAddsSongAfterConfirmation() {
        Audio song = audio(5, "前前前世");
        UserPlaylist playlist = new UserPlaylist();
        playlist.setId(18);
        playlist.setUserId(7);
        playlist.setName("雨夜动漫");
        when(playlistMapper.selectByUserId(7)).thenReturn(Collections.singletonList(playlist));
        when(audioMapper.selectAll()).thenReturn(Collections.singletonList(song));
        doAnswer(invocation -> {
            ((AgentTaskPlan) invocation.getArgument(0)).setId(52L);
            return null;
        }).when(taskMapper).insert(any(AgentTaskPlan.class));

        AgentTaskPlan pending = service.createPlan(7, 9L, 13L,
                "把《前前前世》加入“雨夜动漫”歌单", Collections.emptyList(), null);

        assertNotNull(pending);
        assertEquals("add_to_playlist", pending.getActionType());
        assertEquals(18, pending.getTargetPlaylistId());

        AgentTaskPlan executing = plan("executing");
        executing.setActionType("add_to_playlist");
        executing.setTargetPlaylistId(18);
        AgentTaskPlan succeeded = plan("succeeded");
        succeeded.setActionType("add_to_playlist");
        succeeded.setTargetPlaylistId(18);
        succeeded.setResultPlaylistId(18);
        when(taskMapper.claim(52L, 7, "confirm")).thenReturn(1);
        when(taskMapper.selectOwned(52L, 7)).thenReturn(executing, succeeded);
        when(taskMapper.selectSongs(52L)).thenReturn(Collections.singletonList(song));
        when(audioMapper.selectById(5)).thenReturn(song);
        when(playlistMapper.selectByIdAndUserId(18, 7)).thenReturn(playlist);
        when(playlistMapper.countSong(18, 5)).thenReturn(0);
        when(playlistMapper.selectMaxSortOrder(18)).thenReturn(2);
        when(taskMapper.markSucceeded(eq(52L), eq(7), eq(18), anyString())).thenReturn(1);

        service.confirm(52L, 7, "confirm");

        verify(playlistMapper).insertSong(18, 5, 3);
        verify(playlistMapper).touch(18);
    }

    @Test
    void recognizesOnlyExplicitWriteIntent() {
        assertTrue(AgentTaskService.isPlaylistCreationRequest("新建一个动漫歌单"));
        assertFalse(AgentTaskService.isPlaylistCreationRequest("推荐动漫歌曲"));
        assertEquals("夜晚", AgentTaskService.playlistName("创建一个“夜晚”的歌单"));
        assertEquals(3, AgentTaskService.requestedSongCount("加入3首", 8));
        assertTrue(AgentTaskService.isFavoriteRequest("收藏前前前世"));
        assertTrue(AgentTaskService.isUnfavoriteRequest("取消收藏前前前世"));
        assertFalse(AgentTaskService.isFavoriteRequest("我的收藏里有什么"));
        assertEquals("夜雨", AgentTaskService.renamedPlaylistName("把“夜晚”歌单重命名为“夜雨”"));
        assertTrue(AgentTaskService.isDeletePlaylistRequest("删除雨夜动漫歌单"));
        assertTrue(AgentTaskService.isRemoveFromPlaylistRequest("把前前前世移出雨夜动漫歌单"));
        assertFalse(AgentTaskService.isDeletePlaylistRequest("从雨夜动漫歌单删除前前前世"));
    }

    @Test
    void cancelsPendingPlanAndReturnsCancelledState() {
        AgentTaskPlan cancelled = plan("cancelled");
        when(taskMapper.cancel(41L, 7)).thenReturn(1);
        when(taskMapper.selectOwned(41L, 7)).thenReturn(cancelled);
        when(taskMapper.selectSongs(41L)).thenReturn(Collections.emptyList());

        AgentTaskPlan result = service.cancel(41L, 7);

        assertEquals("cancelled", result.getStatus());
        verify(taskMapper).cancel(41L, 7);
    }

    private AgentTaskPlan plan(String status) {
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setId(41L);
        plan.setUserId(7);
        plan.setPlaylistName("雨夜动漫");
        plan.setActionType("create_playlist");
        plan.setStatus(status);
        return plan;
    }

    private Audio audio(int id, String name) {
        Audio audio = new Audio();
        audio.setId(id);
        audio.setSongName(name);
        audio.setSinger("singer");
        return audio;
    }
}
