package com.example.demo.controller;

import com.example.demo.service.PromptVersionService;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/admin/prompts")
public class PromptVersionController {
    private final PromptVersionService service;

    public PromptVersionController(PromptVersionService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return run(service::list);
    }

    @PostMapping
    public Map<String, Object> createDraft(@RequestBody Map<String, Object> payload) {
        return run(() -> service.createDraft(text(payload.get("template"))));
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateDraft(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        return run(() -> service.updateDraft(id, text(payload.get("template"))));
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable long id) {
        return run(() -> service.publish(id));
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable long id) {
        return run(() -> service.disable(id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        return run(() -> service.delete(id));
    }

    @PostMapping("/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable long id) {
        return run(() -> service.rollback(id));
    }

    private Map<String, Object> run(Supplier<?> action) {
        try {
            return result(200, "success", action.get());
        } catch (IllegalArgumentException exception) {
            return result(400, exception.getMessage(), null);
        } catch (DataAccessException exception) {
            return result(500, "Prompt 数据库操作失败", null);
        }
    }

    private Map<String, Object> result(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("msg", message);
        response.put("data", data);
        return response;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
