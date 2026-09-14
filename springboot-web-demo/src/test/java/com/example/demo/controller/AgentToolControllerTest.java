package com.example.demo.controller;

import com.example.demo.service.AgentToolService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolControllerTest {

    @Test
    void executesVersionedAuthorizedReadOnlyToolRequest() {
        AgentToolService service = mock(AgentToolService.class);
        AgentToolController controller = new AgentToolController();
        ReflectionTestUtils.setField(controller, "agentToolService", service);
        ReflectionTestUtils.setField(controller, "apiKey", "tool-secret");
        when(service.searchSongs("动漫", 3)).thenReturn(Collections.singletonMap("items", Collections.emptyList()));

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "动漫");
        arguments.put("limit", 3);
        Map<String, Object> request = request(arguments);

        Map<String, Object> response = controller.execute(request, "tool-secret");

        assertEquals(true, response.get("success"));
        assertEquals(true, response.get("readOnly"));
        assertEquals("request-1", response.get("requestId"));
        verify(service).searchSongs("动漫", 3);
    }

    @Test
    void rejectsInvalidServiceKeyBeforeToolExecution() {
        AgentToolService service = mock(AgentToolService.class);
        AgentToolController controller = new AgentToolController();
        ReflectionTestUtils.setField(controller, "agentToolService", service);
        ReflectionTestUtils.setField(controller, "apiKey", "tool-secret");

        Map<String, Object> response = controller.execute(request(Collections.singletonMap("query", "动漫")), "wrong");

        assertEquals(false, response.get("success"));
        Map<?, ?> error = (Map<?, ?>) response.get("error");
        assertEquals("FORBIDDEN", error.get("code"));
    }

    @Test
    void routesNewReadOnlyToolsAndRequiresServerUserContext() {
        AgentToolService service = mock(AgentToolService.class);
        AgentToolController controller = new AgentToolController();
        ReflectionTestUtils.setField(controller, "agentToolService", service);
        ReflectionTestUtils.setField(controller, "apiKey", "tool-secret");
        when(service.searchFavorites(7, "动漫", 3)).thenReturn(Collections.emptyMap());
        when(service.recommendSongs(7, "轻快动漫歌曲", 3, Collections.singletonList(1)))
                .thenReturn(Collections.emptyMap());
        when(service.vectorSearch("适合雨夜", 3, Arrays.asList(2, 3))).thenReturn(Collections.emptyMap());
        when(service.songDetail(2, "")).thenReturn(Collections.singletonMap("found", true));

        Map<String, Object> favoriteArgs = new HashMap<>();
        favoriteArgs.put("query", "动漫");
        favoriteArgs.put("limit", 3);
        Map<String, Object> favoriteRequest = request("favorite_search", favoriteArgs);
        favoriteRequest.put("userId", "7");
        assertEquals(true, controller.execute(favoriteRequest, "tool-secret").get("success"));

        Map<String, Object> recommendArgs = new HashMap<>();
        recommendArgs.put("query", "轻快动漫歌曲");
        recommendArgs.put("limit", 3);
        recommendArgs.put("exclude_audio_ids", Collections.singletonList(1));
        Map<String, Object> recommendRequest = request("recommend_songs", recommendArgs);
        recommendRequest.put("userId", "7");
        assertEquals(true, controller.execute(recommendRequest, "tool-secret").get("success"));

        Map<String, Object> vectorArgs = new HashMap<>();
        vectorArgs.put("query", "适合雨夜");
        vectorArgs.put("limit", 3);
        vectorArgs.put("audio_ids", Arrays.asList(2, 3));
        assertEquals(true, controller.execute(request("vector_search", vectorArgs), "tool-secret").get("success"));

        Map<String, Object> detailArgs = Collections.singletonMap("audio_id", 2);
        assertEquals(true, controller.execute(request("song_detail", detailArgs), "tool-secret").get("success"));

        verify(service).searchFavorites(7, "动漫", 3);
        verify(service).recommendSongs(7, "轻快动漫歌曲", 3, Collections.singletonList(1));
        verify(service).vectorSearch("适合雨夜", 3, Arrays.asList(2, 3));
        verify(service).songDetail(2, "");

        Map<String, Object> forgedOnlyInArguments = new HashMap<>();
        forgedOnlyInArguments.put("query", "动漫");
        forgedOnlyInArguments.put("userId", "99");
        Map<String, Object> rejected = controller.execute(
                request("favorite_search", forgedOnlyInArguments), "tool-secret");
        assertEquals(false, rejected.get("success"));
        assertEquals("INVALID_ARGUMENTS", ((Map<?, ?>) rejected.get("error")).get("code"));
    }

    private Map<String, Object> request(Map<String, Object> arguments) {
        return request("song_search", arguments);
    }

    private Map<String, Object> request(String tool, Map<String, Object> arguments) {
        Map<String, Object> request = new HashMap<>();
        request.put("protocolVersion", "1.0");
        request.put("requestId", "request-1");
        request.put("traceId", "trace-1");
        request.put("tool", tool);
        request.put("arguments", arguments);
        return request;
    }
}
