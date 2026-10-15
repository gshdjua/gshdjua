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
        when(taskMapper.markSucceeded(41L, 7, 88)).thenReturn(1);

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
    void recognizesOnlyExplicitWriteIntent() {
        assertTrue(AgentTaskService.isPlaylistCreationRequest("新建一个动漫歌单"));
        assertFalse(AgentTaskService.isPlaylistCreationRequest("推荐动漫歌曲"));
        assertEquals("夜晚", AgentTaskService.playlistName("创建一个“夜晚”的歌单"));
        assertEquals(3, AgentTaskService.requestedSongCount("加入3首", 8));
    }

    private AgentTaskPlan plan(String status) {
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setId(41L);
        plan.setUserId(7);
        plan.setPlaylistName("雨夜动漫");
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
