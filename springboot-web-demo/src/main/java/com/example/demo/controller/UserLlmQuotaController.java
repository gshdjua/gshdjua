package com.example.demo.controller;

import com.example.demo.service.UserLlmQuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Privacy-safe administrative view of per-user model limits and today's aggregate usage. */
@RestController
@RequestMapping("/api/admin/llm-quotas")
public class UserLlmQuotaController {
    private final UserLlmQuotaService service;

    public UserLlmQuotaController(UserLlmQuotaService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("defaults", service.defaults());
        data.put("users", service.listUserUsage());
        return result(200, "success", data);
    }

    @PutMapping("/{userId}")
    public Map<String, Object> update(@PathVariable int userId,
                                      @RequestBody Map<String, Object> payload) {
        try {
            Map<String, Object> updated = service.updateQuota(userId,
                    integer(payload.get("requestsPerMinute")),
                    integer(payload.get("concurrentStreams")),
                    integer(payload.get("dailyTokenLimit")),
                    decimal(payload.get("dailyCostLimit")));
            return result(200, "费用保护配置已更新", updated);
        } catch (IllegalArgumentException exception) {
            return result(400, exception.getMessage(), null);
        }
    }

    private int integer(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception exception) { throw new IllegalArgumentException("限额必须填写有效数字"); }
    }

    private double decimal(Object value) {
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception exception) { throw new IllegalArgumentException("费用上限必须填写有效数字"); }
    }

    private Map<String, Object> result(int code, String message, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("msg", message);
        result.put("data", data);
        return result;
    }
}
