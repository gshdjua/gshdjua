package com.example.demo.controller;

import com.example.demo.service.ConversationReplayService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/conversation-replays")
public class ConversationReplayController {
    private final ConversationReplayService service;

    public ConversationReplayController(ConversationReplayService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public Map<String, Object> conversations(@RequestParam(defaultValue = "100") int limit) {
        return run(() -> service.listConversations(limit));
    }

    @GetMapping("/snapshots")
    public Map<String, Object> snapshots(@RequestParam(defaultValue = "100") int limit) {
        return run(() -> service.listSnapshots(limit));
    }

    @PostMapping("/snapshots")
    public Map<String, Object> create(@RequestBody Map<String, Object> payload) {
        return run(() -> service.createSnapshot(longValue(payload.get("conversationId"))));
    }

    @GetMapping("/snapshots/{snapshotId}/export")
    public ResponseEntity<byte[]> export(@PathVariable long snapshotId) {
        byte[] content = service.exportSnapshot(snapshotId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=musichub-session-snapshot-" + snapshotId + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(content);
    }

    @PostMapping("/snapshots/{snapshotId}/replay")
    public Map<String, Object> replay(@PathVariable long snapshotId,
                                      @RequestBody(required = false) Map<String, Object> payload) {
        String modelId = payload == null ? "" : String.valueOf(payload.getOrDefault("modelId", ""));
        return run(() -> service.replay(snapshotId, modelId));
    }

    @GetMapping("/snapshots/{snapshotId}/runs")
    public Map<String, Object> runs(@PathVariable long snapshotId,
                                    @RequestParam(defaultValue = "20") int limit) {
        return run(() -> service.listReplays(snapshotId, limit));
    }

    @PostMapping("/snapshots/{snapshotId}/experiments")
    public Map<String, Object> createExperiment(@PathVariable long snapshotId,
                                                @RequestBody Map<String, Object> payload) {
        return run(() -> service.createExperiment(snapshotId, text(payload.get("name")),
                strings(payload.get("modelIds")), integers(payload.get("promptVersions")),
                strings(payload.get("strategies"))));
    }

    @GetMapping("/snapshots/{snapshotId}/experiments")
    public Map<String, Object> experiments(@PathVariable long snapshotId) {
        return run(() -> service.listExperiments(snapshotId));
    }

    @GetMapping("/experiments/{experimentId}/runs")
    public Map<String, Object> experimentRuns(@PathVariable long experimentId) {
        return run(() -> service.listExperimentRuns(experimentId));
    }

    @GetMapping("/experiments/{experimentId}/export")
    public ResponseEntity<byte[]> exportExperiment(@PathVariable long experimentId) {
        byte[] content = service.exportExperiment(experimentId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=musichub-snapshot-comparison-" + experimentId + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(content);
    }

    private long longValue(Object value) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception exception) { throw new IllegalArgumentException("会话编号不正确"); }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(text(item));
        return result;
    }

    private List<Integer> integers(Object value) {
        List<Integer> result = new ArrayList<>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                try { result.add(Integer.parseInt(String.valueOf(item))); }
                catch (Exception ignored) { /* Service validates the final selection. */ }
            }
        }
        return result;
    }

    private Map<String, Object> run(Action action) {
        try { return result(200, "success", action.get()); }
        catch (IllegalArgumentException exception) { return result(400, exception.getMessage(), null); }
        catch (RuntimeException exception) { return result(500, "会话快照操作失败", null); }
    }

    private Map<String, Object> result(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("msg", message);
        response.put("data", data);
        return response;
    }

    private interface Action { Object get(); }
}
