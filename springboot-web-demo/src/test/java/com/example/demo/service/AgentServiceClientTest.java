package com.example.demo.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceClientTest {

    @Test
    void nativeEvaluationRequestEnablesToolsButDisablesMemoryPersistence() {
        JSONObject request = AgentServiceClient.buildNativeEvaluationRequest(
                "我收藏了哪些动漫歌曲？", 7, "auto", "standard");

        assertEquals("7", request.getString("userId"));
        assertFalse(request.containsKey("conversationId"));
        assertEquals("auto", request.getJSONObject("options").getString("strategy"));
        assertEquals("deepseek", request.getJSONObject("options").getString("provider"));
        assertEquals("agent_native", request.getJSONObject("metadata").getString("evaluationMode"));
        assertEquals(request.getString("requestId"), request.getJSONObject("metadata").getString("traceId"));
    }

    @Test
    void nativeEvaluationRequestAcceptsConfiguredProvider() {
        JSONObject request = AgentServiceClient.buildNativeEvaluationRequest(
                "测试", 7, "direct", "low", " QWEN ");

        assertEquals("qwen", request.getJSONObject("options").getString("provider"));
    }

    @Test
    void parsesStrategyBudgetAndPrivacySafeToolAudit() {
        String response = "{" +
                "\"answer\":\"回答\",\"provider\":\"qwen\",\"model\":\"qwen-plus\"," +
                "\"traceId\":\"trace-1\",\"strategy\":\"react\"," +
                "\"strategyReason\":\"multiple_tool_intents\",\"finishReason\":\"stop\",\"latencyMs\":123," +
                "\"usage\":{\"inputTokens\":100,\"outputTokens\":20,\"totalTokens\":120}," +
                "\"budget\":{\"level\":\"standard\",\"modelCalls\":2,\"toolCalls\":1,\"toolRounds\":1," +
                "\"exceeded\":false,\"stopReason\":\"\"}," +
                "\"toolExecutions\":[{\"tool\":\"favorite_search\",\"success\":true,\"attempts\":1," +
                "\"durationMs\":8,\"errorCode\":\"\",\"arguments\":{\"query\":\"private\"}," +
                "\"data\":{\"items\":[1]}}]}";

        AgentServiceClient.AgentResult result = AgentServiceClient.parseAgentResult(response);

        assertEquals("trace-1", result.getTraceId());
        assertEquals("react", result.getStrategy());
        assertEquals("qwen", result.getProvider());
        assertEquals("qwen-plus", result.getModel());
        assertEquals(2, result.getModelCalls());
        assertEquals(1, result.getToolCalls());
        assertEquals(120, result.getTotalTokens());
        assertFalse(result.isBudgetExceeded());
        Map<String, Object> tool = result.getToolExecutions().get(0);
        assertEquals("favorite_search", tool.get("tool"));
        assertTrue((Boolean) tool.get("success"));
        assertFalse(tool.containsKey("arguments"));
        assertFalse(tool.containsKey("data"));
    }
}
