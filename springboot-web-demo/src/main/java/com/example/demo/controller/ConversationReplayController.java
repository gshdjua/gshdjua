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

    private long longValue(Object value) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception exception) { throw new IllegalArgumentException("会话编号不正确"); }
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
