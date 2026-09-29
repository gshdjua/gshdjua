package com.example.demo.controller;

import com.example.demo.service.LlmModelCatalogService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/llm-models")
public class LlmModelController {
    private final LlmModelCatalogService catalog;
    private final com.example.demo.service.AgentServiceClient agentServiceClient;
    public LlmModelController(LlmModelCatalogService catalog, com.example.demo.service.AgentServiceClient agentServiceClient) {
        this.catalog = catalog;
        this.agentServiceClient = agentServiceClient;
    }

    @GetMapping
    public Map<String,Object> list() { return response(200,"success",catalog.listAll()); }

    @PostMapping
    public Map<String,Object> create(@RequestBody Map<String,Object> payload) {
        try { return response(200,"模型已保存",catalog.save(null,payload)); }
        catch(IllegalArgumentException exception){ return response(400,exception.getMessage(),null); }
    }

    @PutMapping("/{id}")
    public Map<String,Object> update(@PathVariable String id,@RequestBody Map<String,Object> payload) {
        try { return response(200,"模型已更新",catalog.save(id,payload)); }
        catch(IllegalArgumentException exception){ return response(400,exception.getMessage(),null); }
    }

    @DeleteMapping("/{id}")
    public Map<String,Object> disable(@PathVariable String id) {
        try { catalog.disable(id); return response(200,"模型已停用",null); }
        catch(IllegalArgumentException exception){ return response(400,exception.getMessage(),null); }
    }

    @PostMapping("/{id}/test")
    public Map<String,Object> test(@PathVariable String id) {
        try {
            LlmModelCatalogService.ModelConfig model = catalog.resolve(id);
            Map<String,Object> health = agentServiceClient.testModel(model.getProvider(), model.getModel());
            catalog.recordHealth(id, health);
            return response(200, Boolean.TRUE.equals(health.get("available")) ? "模型连接可用" : "模型连接失败", health);
        } catch(IllegalArgumentException exception) {
            return response(400, exception.getMessage(), null);
        }
    }

    private Map<String,Object> response(int code,String msg,Object data){Map<String,Object> result=new LinkedHashMap<>();result.put("code",code);result.put("msg",msg);result.put("data",data);return result;}
}
