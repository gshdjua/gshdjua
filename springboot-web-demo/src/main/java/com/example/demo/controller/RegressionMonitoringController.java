package com.example.demo.controller;

import com.example.demo.service.RegressionMonitoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/regression-monitoring")
public class RegressionMonitoringController {
    private final RegressionMonitoringService service;

    public RegressionMonitoringController(RegressionMonitoringService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> status() { return response(200, "success", service.status()); }

    @PostMapping("/runs")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> payload) {
        int topK = integer(payload == null ? null : payload.get("topK"), 5);
        return response(202, "回归评测已开始", service.trigger("manual", topK));
    }

    @PostMapping("/index/rebuild")
    public Map<String, Object> rebuild(@RequestBody(required = false) Map<String, Object> payload) {
        String reason = payload == null ? "manual-admin" : String.valueOf(payload.getOrDefault("reason", "manual-admin"));
        return response(202, "索引重建已提交", service.rebuildIndex(reason));
    }

    @PutMapping("/alerts/{alertId}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable long alertId) {
        boolean updated = service.acknowledge(alertId);
        return response(updated ? 200 : 404, updated ? "告警已确认" : "告警不存在或已确认", updated);
    }

    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code); result.put("msg", message); result.put("data", data);
        return result;
    }
}
