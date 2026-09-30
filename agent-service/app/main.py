import logging
import json
import time
from queue import Queue
from threading import Event, Lock, Thread
from types import SimpleNamespace
from typing import Iterator, List, Tuple

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from .audit import audit_repository
from .config import default_provider, tool_parallel_max_workers
from .contracts import (
    AgentChatRequest,
    AgentChatResponse,
    AgentStep,
    AgentMessage,
    ExecutionBudgetReport,
    ExecutionAuditRecord,
    MemoryCaptureRequest,
    MemoryRecord,
    MemorySettingsUpdate,
    MemoryUpdate,
    ProviderHealthRequest,
    ProviderHealthResponse,
    StrategyPreviewRequest,
    StrategyPreviewResponse,
    TokenUsage,
    ToolExecutionAudit,
)
from .graph import agent_graph, reset_model_stream_sink, set_model_stream_sink
from .memory import memory_repository
from .providers import classify_provider_error, llm_provider_registry
from .strategies import strategy_router
from .tools import tool_registry
from .tools.models import ToolCatalogResponse


app = FastAPI(title="MusicHub Agent Service", version="0.1.0")
LOGGER = logging.getLogger(__name__)
ACTIVE_STREAMS: dict[str, Event] = {}
ACTIVE_STREAMS_LOCK = Lock()


class AgentStreamCancelled(Exception):
    pass


def sse_event(event: str, data: dict) -> str:
    """Encode one SSE event without allowing payload newlines to break framing."""
    return "event: " + event + "\ndata: " + json.dumps(data, ensure_ascii=False) + "\n\n"


def answer_chunks(answer: str, size: int = 24) -> Iterator[str]:
    safe_size = max(1, min(200, size))
    for offset in range(0, len(answer), safe_size):
        yield answer[offset:offset + safe_size]


def has_prepared_evidence(messages: List[AgentMessage]) -> bool:
    return any("本地歌库提供的最小歌曲元数据：" in message.content for message in messages)


def should_enable_tools(
    messages: List[AgentMessage],
    user_message: str = "",
    requested_strategy: str = "auto",
    cost_budget: str = "standard",
) -> bool:
    """Keep simple prepared-evidence requests fast, but supplement complex ones.

    Java-side evidence is intentionally sufficient for a single-step lookup.  It
    is only a starting point for a composite request (for example favourites +
    mood + recommendation + comparison), where the strategy router should still
    be allowed to invoke the missing read-only tools.
    """
    if not has_prepared_evidence(messages):
        return True
    decision = strategy_router.select(
        requested_strategy,
        user_message,
        tools_enabled=True,
        cost_budget=cost_budget,
    )
    return decision.selected == "react"


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
        "agentSteps": state.get("agent_steps", []),
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
    active_provider = llm_provider_registry.get(default_provider())
    provider_status = active_provider.public_status()
    return {
        "status": "ready",
        "configured": active_provider.is_configured(),
        "provider": active_provider.name,
        "model": active_provider.default_model(),
        "baseUrl": provider_status.get("baseUrl", ""),
        "providers": llm_provider_registry.statuses(),
        "protocolVersion": "1.0",
        "memoryStore": "mysql",
        "memoryAvailable": memory_repository.available(),
        "auditAvailable": audit_repository.available(),
        "tools": [item.name for item in tool_registry.descriptors()],
        "strategies": ["auto", "direct", "react"],
        "defaultStrategy": "auto",
        "costBudgets": ["low", "standard", "high"],
        "defaultCostBudget": "standard",
        "orchestrationModes": ["single", "multi"],
        "defaultOrchestration": "multi",
        "multiAgentRoles": ["retrieval_agent", "tool_agent", "candidate_agent", "fact_check_agent", "recovery_agent", "answer_agent"],
        "parallelToolWorkers": tool_parallel_max_workers(),
    }


@app.get("/v1/tools", response_model=ToolCatalogResponse)
def tools() -> ToolCatalogResponse:
    return ToolCatalogResponse(tools=tool_registry.descriptors())


@app.post("/v1/providers/health", response_model=ProviderHealthResponse)
def provider_health(payload: ProviderHealthRequest) -> ProviderHealthResponse:
    try:
        return ProviderHealthResponse(**llm_provider_registry.check_health(
            payload.provider, payload.model, payload.timeoutSeconds
        ))
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


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
    model_name = payload.options.model or ""
    trace_id = payload.metadata.get("traceId", payload.requestId)
    state = {}
    try:
        provider = llm_provider_registry.get(payload.options.provider)
        model_name = payload.options.model or provider.default_model()
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
        tools_enabled = should_enable_tools(
            payload.messages,
            raw_user_message,
            payload.options.strategy,
            payload.options.costBudget,
        )
        effective_messages = list(system_messages)
        effective_messages.append(AgentMessage(
            role="system",
            content=(
                "需要确认 MusicHub 本地歌库内容时使用 song_search，查询单曲事实使用 song_detail；"
                "查询当前用户收藏使用 favorite_search，获取推荐候选使用 recommend_songs，"
                "只有模糊听感需要补充语义候选时才使用 vector_search。"
                "这些工具返回的是只读结构化数据；只能把工具实际返回的歌曲说成本地已收录。"
                "工具失败或没有结果时，应明确说明本地歌库未找到，不得编造。"
                "数量字段必须严格区分：count/returnedCount 是本次返回条数，availableCount 是筛选后可推荐数，"
                "catalogMatchCount 才是歌库中满足明确类型条件的总数；绝不能把本次返回条数说成歌库总数。"
            ),
        ))
        if tools_enabled and has_prepared_evidence(payload.messages):
            effective_messages.append(AgentMessage(
                role="system",
                content=(
                    "当前请求中的本地歌曲元数据只是 Java 端提供的初始候选，并不代表复合检索已经完成。"
                    "请先识别用户尚未满足的条件，并仅调用需要补充的只读工具；涉及用户收藏、模糊场景、"
                    "候选推荐和事实比较时，可分别使用 favorite_search、vector_search、recommend_songs 和 song_detail。"
                    "合并已有证据与工具结果后再回答，不要因为初始候选存在就跳过缺失条件。"
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
                "tools_enabled": tools_enabled,
                "orchestration_mode": payload.options.orchestration,
                "agent_steps": [],
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
            agentSteps=[AgentStep(**item) for item in state.get("agent_steps", [])],
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
            "agentSteps": state.get("agent_steps", []),
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
        LOGGER.warning(
            "Agent request rejected: provider=%s model=%s traceId=%s error=%s",
            payload.options.provider,
            model_name,
            trace_id,
            error,
        )
        save_failed_audit(payload, model_name, trace_id, started, state, "INVALID_REQUEST")
        raise HTTPException(status_code=400, detail=str(error)) from error
    except AgentStreamCancelled as error:
        LOGGER.info("Agent stream cancelled: traceId=%s", trace_id)
        save_failed_audit(payload, model_name, trace_id, started, state, "CLIENT_CANCELLED")
        raise HTTPException(status_code=499, detail={
            "code": "CLIENT_CANCELLED",
            "message": "用户已停止生成",
        }) from error
    except Exception as error:
        error_code, safe_message = classify_provider_error(error)
        LOGGER.exception(
            "Agent model invocation failed: provider=%s model=%s traceId=%s",
            payload.options.provider,
            model_name,
            trace_id,
        )
        save_failed_audit(payload, model_name, trace_id, started, state, error_code)
        raise HTTPException(status_code=502, detail={
            "code": error_code,
            "message": safe_message,
        }) from error


@app.post("/v1/chat/stream")
def chat_stream(payload: AgentChatRequest) -> StreamingResponse:
    """SSE variant of chat. The final `done` event contains the complete v1 response."""
    def generate() -> Iterator[str]:
        trace_id = payload.metadata.get("traceId", payload.requestId)
        yield sse_event("start", {
            "requestId": payload.requestId,
            "traceId": trace_id,
            "provider": payload.options.provider,
            "model": payload.options.model or "",
        })
        events: Queue[Tuple[str, dict]] = Queue()
        cancelled = Event()
        with ACTIVE_STREAMS_LOCK:
            ACTIVE_STREAMS[payload.requestId] = cancelled

        def forward_delta(content: str) -> None:
            if cancelled.is_set():
                raise AgentStreamCancelled()
            events.put(("delta", {"content": content}))

        def run_chat() -> None:
            token = set_model_stream_sink(forward_delta)
            try:
                result = chat(payload)
                events.put(("done", result.model_dump()))
            except HTTPException as error:
                detail = error.detail
                if isinstance(detail, dict):
                    code = str(detail.get("code", "AGENT_STREAM_FAILED"))
                    message = str(detail.get("message", "Agent Service 调用失败"))
                else:
                    code = "INVALID_REQUEST" if error.status_code < 500 else "AGENT_STREAM_FAILED"
                    message = str(detail)
                events.put(("error", {"code": code, "message": message}))
            except Exception:
                LOGGER.exception("Unexpected Agent SSE failure: traceId=%s", trace_id)
                events.put(("error", {
                    "code": "AGENT_STREAM_FAILED",
                    "message": "Agent Service 流式调用失败",
                }))
            finally:
                reset_model_stream_sink(token)
                with ACTIVE_STREAMS_LOCK:
                    ACTIVE_STREAMS.pop(payload.requestId, None)

        Thread(target=run_chat, name="agent-sse-" + payload.requestId, daemon=True).start()
        while True:
            event, data = events.get()
            yield sse_event(event, data)
            if event in ("done", "error"):
                break

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


@app.delete("/v1/chat/stream/{request_id}")
def cancel_chat_stream(request_id: str) -> dict:
    if not request_id or len(request_id) > 100:
        raise HTTPException(status_code=400, detail="Invalid requestId")
    with ACTIVE_STREAMS_LOCK:
        cancelled = ACTIVE_STREAMS.get(request_id)
        if cancelled is not None:
            cancelled.set()
    return {"cancelled": cancelled is not None, "requestId": request_id}


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
