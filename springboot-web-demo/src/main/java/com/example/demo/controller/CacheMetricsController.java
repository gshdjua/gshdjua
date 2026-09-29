package com.example.demo.controller;

import com.example.demo.service.PromptVersionService;
import com.example.demo.service.VectorRagClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight operational view for the phase 7.3 in-process caches. */
@RestController
@RequestMapping("/api/admin/cache")
public class CacheMetricsController {
    private final PromptVersionService promptVersionService;
    private final VectorRagClient vectorRagClient;

    public CacheMetricsController(PromptVersionService promptVersionService, VectorRagClient vectorRagClient) {
        this.promptVersionService = promptVersionService;
        this.vectorRagClient = vectorRagClient;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("prompt", promptVersionService.cacheStats());
        data.put("retrieval", vectorRagClient.cacheStats());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("msg", "success");
        response.put("data", data);
        return response;
    }
}
