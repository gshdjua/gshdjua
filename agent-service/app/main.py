import logging
import time
from types import SimpleNamespace
from typing import List

from fastapi import FastAPI, HTTPException
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from .config import api_key, base_url, default_model
from .audit import audit_repository
from .contracts import (
    AgentChatRequest,
    AgentChatResponse,
    AgentMessage,
    ExecutionBudgetReport,
    ExecutionAuditRecord,
    MemoryCaptureRequest,
    MemoryRecord,
    MemorySettingsUpdate,
    MemoryUpdate,
    StrategyPreviewRequest,
    StrategyPreviewResponse,
    TokenUsage,
    ToolExecutionAudit,
)
from .graph import agent_graph
from .memory import memory_repository
from .strategies import strategy_router
from .tools import tool_registry
from .tools.models import ToolCatalogResponse


app = FastAPI(title="MusicHub Agent Service", version="0.1.0")
LOGGER = logging.getLogger(__name__)


def should_enable_tools(messages: List[AgentMessage]) -> bool:
    return not any("本地歌库提供的最小歌曲元数据：" in message.content for message in messages)


def to_langchain_message(role: str, content: str):
    if role == "system":
        return SystemMessage(content=content)
    if role == "assistant":
        return AIMessage(content=content)
    return HumanMessage(content=content)


def save_failed_audit(payload: AgentChatRequest, model_name: str, trace_id: str,
                      started: float, state: dict, error_code: str) -> None:
    audit_repository.save({
        "traceId": trace_id,
        "requestId": payload.requestId,
        "provider": payload.options.provider,
        "model": model_name,
        "promptVersion": payload.metadata.get("promptVersion", "none"),
        "requestedStrategy": payload.options.strategy,
        "selectedStrategy": state.get("selected_strategy", ""),
        "strategyReason": state.get("strategy_reason", ""),
        "costBudget": state.get("cost_budget", payload.options.costBudget),
        "modelCalls": state.get("model_calls", 0),
        "toolCalls": state.get("tool_calls", 0),
        "toolRounds": state.get("tool_rounds", 0),
        "toolExecutions": state.get("tool_executions", []),
        "inputTokens": state.get("input_tokens", 0),
        "outputTokens": state.get("output_tokens", 0),
        "totalTokens": state.get("total_tokens", 0),
        "latencyMs": round((time.perf_counter() - started) * 1000),
        "budgetExceeded": state.get("budget_exhausted", False),
        "stopReason": state.get("budget_stop_reason", ""),
        "finishReason": "error",
        "status": "error",
        "errorCode": error_code,
    })


@app.get("/health")
def health() -> dict:
    return {
        "status": "ready",
        "configured": bool(api_key()),
        "provider": "deepseek",
        "model": default_model(),
        "baseUrl": base_url(),
        "protocolVersion": "1.0",
        "memoryStore": "mysql",
        "memoryAvailable": memory_repository.available(),
        "auditAvailable": audit_repository.available(),
        "tools": [item.name for item in tool_registry.descriptors()],
        "strategies": ["auto", "direct", "react"],
        "defaultStrategy": "auto",
        "costBudgets": ["low", "standard", "high"],
        "defaultCostBudget": "standard",
    }


@app.get("/v1/tools", response_model=ToolCatalogResponse)
def tools() -> ToolCatalogResponse:
    return ToolCatalogResponse(tools=tool_registry.descriptors())


@app.post("/v1/strategy/preview", response_model=StrategyPreviewResponse)
def strategy_preview(payload: StrategyPreviewRequest) -> StrategyPreviewResponse:
    """Preview routing without calling an LLM, executing tools, or persisting user data."""
    decision = strategy_router.select(
        payload.strategy, payload.message, tools_enabled=True, cost_budget=payload.costBudget
    )
    plan = strategy_router.direct.plan(payload.message) if decision.selected == "direct" else None
    budget = decision.budget
    return StrategyPreviewResponse(
        selectedStrategy=decision.selected,
        strategyReason=decision.reason,
        costBudget=budget.level,
        plannedTool=plan.tool if plan else "",
        maxModelCalls=budget.max_model_calls,
        maxToolCalls=budget.max_tool_calls,
        maxToolRounds=budget.max_tool_rounds,
        maxTotalTokens=budget.max_total_tokens,
        maxExecutionMs=budget.max_execution_ms,
    )


@app.post("/v1/chat", response_model=AgentChatResponse)
def chat(payload: AgentChatRequest) -> AgentChatResponse:
    if payload.protocolVersion != "1.0":
        raise HTTPException(status_code=400, detail="Unsupported protocol version")
    started = time.perf_counter()
    model_name = payload.options.model or default_model()
    trace_id = payload.metadata.get("traceId", payload.requestId)
    state = {}
    try:
        system_messages = [item for item in payload.messages if item.role == "system"]
        conversation_messages = [item for item in payload.messages if item.role != "system"]
        current_message = conversation_messages[-1]
        incoming_history = conversation_messages[:-1]
        raw_user_message = payload.metadata.get("userMessage", current_message.content)
        evaluation_only = payload.metadata.get("evaluationMode") == "agent_native"
        memory = (
            memory_repository.load(payload.conversationId, payload.userId, raw_user_message)
            if not evaluation_only
            else SimpleNamespace(summary="", long_term_memories=[], recent_messages=[])
        )
        memory_notes = []
        if memory.summary:
            memory_notes.append("此前对话摘要：" + memory.summary)
        if memory.long_term_memories:
            memory_notes.append("用户长期偏好：" + "；".join(memory.long_term_memories))
        effective_messages = list(system_messages)
        effective_messages.append(AgentMessage(
            role="system",
            content=(
                "需要确认 MusicHub 本地歌库内容时使用 song_search，查询单曲事实使用 song_detail；"
                "查询当前用户收藏使用 favorite_search，获取推荐候选使用 recommend_songs，"
                "只有模糊听感需要补充语义候选时才使用 vector_search。"
                "这些工具返回的是只读结构化数据；只能把工具实际返回的歌曲说成本地已收录。"
                "工具失败或没有结果时，应明确说明本地歌库未找到，不得编造。"
            ),
        ))
        if memory_notes:
            effective_messages.append(AgentMessage(role="system", content="\n".join(memory_notes)))
        effective_messages.extend(memory.recent_messages or incoming_history[-6:])
        effective_messages.append(current_message)
        state = {
                "messages": [to_langchain_message(item.role, item.content) for item in effective_messages],
                "provider": payload.options.provider,
                "model": model_name,
                "temperature": payload.options.temperature,
                "strategy": payload.options.strategy,
                "cost_budget": payload.options.costBudget,
                "user_message": raw_user_message,
                "request_id": payload.requestId,
                "trace_id": payload.metadata.get("traceId", payload.requestId),
                "user_id": payload.userId,
                "tool_rounds": 0,
                "tool_calls": 0,
                "tool_executions": [],
                "model_calls": 0,
                "input_tokens": 0,
                "output_tokens": 0,
                "total_tokens": 0,
                "execution_started_at": time.monotonic(),
                "budget_exhausted": False,
                "budget_stop_reason": "",
                "tools_enabled": should_enable_tools(payload.messages),
            }
        state = agent_graph.invoke(state)
        response = state["response"]
        if not evaluation_only:
            memory_repository.save(
                payload.conversationId,
                payload.userId,
                memory.summary,
                memory.recent_messages,
                incoming_history,
                raw_user_message,
                str(response.content),
            )
            memory_repository.capture_preferences(
                payload.userId,
                payload.conversationId,
                payload.requestId,
                raw_user_message,
            )
        LOGGER.info(
            "Agent budget usage: level=%s modelCalls=%s toolCalls=%s toolRounds=%s "
            "totalTokens=%s elapsedMs=%s exceeded=%s stopReason=%s traceId=%s",
            state["cost_budget"],
            state.get("model_calls", 0),
            state.get("tool_calls", 0),
            state.get("tool_rounds", 0),
            state.get("total_tokens", 0),
            round((time.perf_counter() - started) * 1000),
            state.get("budget_exhausted", False),
            state.get("budget_stop_reason", ""),
            payload.metadata.get("traceId", payload.requestId),
        )
        elapsed = round((time.perf_counter() - started) * 1000)
        finish_reason = (
            "budget"
            if state.get("budget_exhausted", False)
            else str(response.response_metadata.get("finish_reason", "stop"))
        )
        result = AgentChatResponse(
            requestId=payload.requestId,
            traceId=trace_id,
            answer=str(response.content),
            provider=payload.options.provider,
            model=model_name,
            strategy=state["selected_strategy"],
            strategyReason=state["strategy_reason"],
            usage=TokenUsage(
                inputTokens=state.get("input_tokens", 0),
                outputTokens=state.get("output_tokens", 0),
                totalTokens=state.get("total_tokens", 0),
            ),
            budget=ExecutionBudgetReport(
                level=state["cost_budget"],
                modelCalls=state.get("model_calls", 0),
                toolCalls=state.get("tool_calls", 0),
                toolRounds=state.get("tool_rounds", 0),
                maxModelCalls=state["max_model_calls"],
                maxToolCalls=state["max_tool_calls"],
                maxToolRounds=state["max_tool_rounds"],
                maxTotalTokens=state["max_total_tokens"],
                maxExecutionMs=state["max_execution_ms"],
                elapsedMs=elapsed,
                exceeded=state.get("budget_exhausted", False),
                stopReason=state.get("budget_stop_reason", ""),
            ),
            toolExecutions=[ToolExecutionAudit(**item) for item in state.get("tool_executions", [])],
            finishReason=finish_reason,
            latencyMs=elapsed,
        )
        audit_repository.save({
            "traceId": trace_id,
            "requestId": payload.requestId,
            "provider": payload.options.provider,
            "model": model_name,
            "promptVersion": payload.metadata.get("promptVersion", "none"),
            "requestedStrategy": payload.options.strategy,
            "selectedStrategy": state["selected_strategy"],
            "strategyReason": state["strategy_reason"],
            "costBudget": state["cost_budget"],
            "modelCalls": state.get("model_calls", 0),
            "toolCalls": state.get("tool_calls", 0),
            "toolRounds": state.get("tool_rounds", 0),
            "toolExecutions": state.get("tool_executions", []),
            "inputTokens": state.get("input_tokens", 0),
            "outputTokens": state.get("output_tokens", 0),
            "totalTokens": state.get("total_tokens", 0),
            "latencyMs": elapsed,
            "budgetExceeded": state.get("budget_exhausted", False),
            "stopReason": state.get("budget_stop_reason", ""),
            "finishReason": finish_reason,
            "status": "budget" if state.get("budget_exhausted", False) else "success",
            "errorCode": "",
        })
        return result
    except ValueError as error:
        save_failed_audit(payload, model_name, trace_id, started, state, "INVALID_REQUEST")
        raise HTTPException(status_code=400, detail=str(error)) from error
    except Exception as error:
        save_failed_audit(payload, model_name, trace_id, started, state, "MODEL_INVOCATION_FAILED")
        raise HTTPException(status_code=502, detail="Model invocation failed: " + str(error)) from error


@app.get("/v1/audit/traces/{trace_id}", response_model=ExecutionAuditRecord)
def execution_audit(trace_id: str) -> ExecutionAuditRecord:
    if not trace_id or len(trace_id) > 191:
        raise HTTPException(status_code=400, detail="Invalid traceId")
    try:
        record = audit_repository.get(trace_id)
        if record is None:
            raise HTTPException(status_code=404, detail="Execution audit not found")
        return ExecutionAuditRecord(**record)
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(status_code=503, detail="Execution audit store unavailable") from error


@app.get("/v1/memory/users/{user_id}/settings")
def memory_settings(user_id: str) -> dict:
    return memory_repository.settings(user_id)


@app.post("/v1/memory/users/{user_id}/capture")
def capture_memory(user_id: str, payload: MemoryCaptureRequest) -> dict:
    try:
        return memory_repository.capture_preferences(
            user_id, payload.conversationId, payload.requestId, payload.message
        )
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error


@app.put("/v1/memory/users/{user_id}/settings")
def update_memory_settings(user_id: str, payload: MemorySettingsUpdate) -> dict:
    try:
        return memory_repository.set_enabled(user_id, payload.enabled)
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error


@app.get("/v1/memory/users/{user_id}/memories", response_model=list[MemoryRecord])
def memories(user_id: str) -> list[MemoryRecord]:
    try:
        return [MemoryRecord(**item) for item in memory_repository.list_memories(user_id)]
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error


@app.put("/v1/memory/users/{user_id}/memories/{memory_id}", response_model=MemoryRecord)
def update_memory(user_id: str, memory_id: int, payload: MemoryUpdate) -> MemoryRecord:
    try:
        updated = memory_repository.update_memory(user_id, memory_id, payload.content)
        if updated is None:
            raise HTTPException(status_code=404, detail="Memory not found")
        return MemoryRecord(**updated)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error


@app.post("/v1/memory/users/{user_id}/memories/{memory_id}/reactivate", response_model=MemoryRecord)
def reactivate_memory(user_id: str, memory_id: int) -> MemoryRecord:
    try:
        reactivated = memory_repository.reactivate_memory(user_id, memory_id)
        if reactivated is None:
            raise HTTPException(status_code=404, detail="Memory not found")
        return MemoryRecord(**reactivated)
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error


@app.delete("/v1/memory/users/{user_id}/memories/{memory_id}")
def delete_memory(user_id: str, memory_id: int) -> dict:
    try:
        if not memory_repository.delete_memory(user_id, memory_id):
            raise HTTPException(status_code=404, detail="Memory not found")
        return {"deleted": True}
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error


@app.delete("/v1/memory/users/{user_id}/memories")
def clear_memories(user_id: str) -> dict:
    try:
        return {"deletedCount": memory_repository.clear_memories(user_id)}
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error


@app.delete("/v1/memory/users/{user_id}/conversation-state/{conversation_id}")
def delete_conversation_state(user_id: str, conversation_id: str) -> dict:
    try:
        return {"deleted": memory_repository.delete_conversation_state(conversation_id, user_id)}
    except Exception as error:
        raise HTTPException(status_code=503, detail="Memory store unavailable") from error
