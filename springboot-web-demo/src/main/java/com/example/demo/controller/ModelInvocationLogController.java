package com.example.demo.controller;

import com.example.demo.service.ModelInvocationLogService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/llm-invocations")
public class ModelInvocationLogController {
    private final ModelInvocationLogService service;

    public ModelInvocationLogController(ModelInvocationLogService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "7") int days,
                                    @RequestParam(defaultValue = "") String provider,
                                    @RequestParam(defaultValue = "") String model,
                                    @RequestParam(defaultValue = "all") String status,
                                    @RequestParam(defaultValue = "100") int limit) {
        try { return result(200, "success", service.search(days, provider, model, status, limit)); }
        catch (IllegalArgumentException exception) { return result(400, exception.getMessage(), null); }
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam(defaultValue = "7") int days,
                                      @RequestParam(defaultValue = "") String provider,
                                      @RequestParam(defaultValue = "") String model,
                                      @RequestParam(defaultValue = "all") String status) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deletedCount", service.delete(days, provider, model, status));
            return result(200, "调用记录已清除", data);
        } catch (IllegalArgumentException exception) { return result(400, exception.getMessage(), null); }
    }

    private Map<String, Object> result(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code); response.put("msg", message); response.put("data", data);
        return response;
    }
}
