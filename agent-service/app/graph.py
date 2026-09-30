import logging
import operator
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextvars import ContextVar, Token
from typing import Annotated, Any, Callable, Dict, List, Optional, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, SystemMessage, ToolMessage, message_chunk_to_message
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages

from .config import tool_parallel_max_workers
from .providers import LlmModelRequest, llm_provider_registry
from .strategies import strategy_router
from .tools import ToolContext, tool_registry
from .tools.models import ToolError, ToolExecutionResult


LOGGER = logging.getLogger(__name__)
MODEL_STREAM_SINK: ContextVar[Optional[Callable[[str], None]]] = ContextVar(
    "model_stream_sink", default=None
)


def set_model_stream_sink(sink: Callable[[str], None]) -> Token:
    return MODEL_STREAM_SINK.set(sink)


def reset_model_stream_sink(token: Token) -> None:
    MODEL_STREAM_SINK.reset(token)


class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]
    provider: str
    model: str
    temperature: float
    strategy: str
    selected_strategy: str
    strategy_reason: str
    cost_budget: str
    max_tool_rounds: int
    max_tool_calls: int
    max_model_calls: int
    max_total_tokens: int
    max_execution_ms: int
    response: AIMessage
    request_id: str
    trace_id: str
    user_id: str | None
    tool_rounds: int
    tool_calls: int
    tool_executions: Annotated[List[Dict[str, Any]], operator.add]
    model_calls: int
    input_tokens: int
    output_tokens: int
    total_tokens: int
    execution_started_at: float
    budget_exhausted: bool
    budget_stop_reason: str
    tools_enabled: bool
    user_message: str
    orchestration_mode: str
    draft_response: AIMessage
    agent_steps: Annotated[List[Dict[str, Any]], operator.add]


def message_usage(message: AIMessage) -> Dict[str, int]:
    usage = message.usage_metadata or {}
    response_usage = message.response_metadata.get("token_usage", {})
    input_tokens = int(usage.get("input_tokens", response_usage.get("prompt_tokens", 0)) or 0)
    output_tokens = int(usage.get("output_tokens", response_usage.get("completion_tokens", 0)) or 0)
    total_tokens = int(usage.get("total_tokens", response_usage.get("total_tokens", 0)) or 0)
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens or input_tokens + output_tokens,
    }


def elapsed_ms(state: AgentState) -> int:
    started = state.get("execution_started_at", time.monotonic())
    return max(0, round((time.monotonic() - started) * 1000))


def budget_error(call: Dict[str, Any], state: AgentState, reason: str) -> ToolExecutionResult:
    message = "本次请求已达到工具调用预算" if reason == "tool_call_limit" else "本次请求已达到执行时间预算"
    return ToolExecutionResult(
        requestId=state["request_id"],
        traceId=state["trace_id"],
        tool=call["name"],
        success=False,
        readOnly=tool_registry.is_read_only(call["name"]),
        error=ToolError(code="BUDGET_EXCEEDED", message=message, retryable=False),
    )


def prepare_strategy(state: AgentState) -> Dict[str, Any]:
    decision = strategy_router.select(
        state.get("strategy", "auto"),
        state.get("user_message", ""),
        state.get("tools_enabled", True),
        state.get("cost_budget", "standard"),
    )
    budget = decision.budget
    multi_agent_calls = (
        max(2, budget.max_model_calls)
        if state.get("orchestration_mode", "single") == "multi" and budget.level != "low"
        else budget.max_model_calls
    )
    update: Dict[str, Any] = {
        "selected_strategy": decision.selected,
        "strategy_reason": decision.reason,
        "cost_budget": budget.level,
        "max_tool_rounds": budget.max_tool_rounds,
        "max_tool_calls": budget.max_tool_calls,
        "max_model_calls": multi_agent_calls,
        "max_total_tokens": budget.max_total_tokens,
        "max_execution_ms": budget.max_execution_ms,
        "execution_started_at": state.get("execution_started_at", time.monotonic()),
        "budget_exhausted": state.get("budget_exhausted", False),
        "budget_stop_reason": state.get("budget_stop_reason", ""),
    }
    if state.get("orchestration_mode", "single") == "multi" and not state.get("tools_enabled", True):
        update["agent_steps"] = [{
            "agent": "retrieval_agent",
            "status": "completed",
            "durationMs": 0,
            "summary": "prepared_evidence",
        }]
    if decision.selected == "direct" and state.get("tools_enabled", True):
        plan = strategy_router.direct.plan(state.get("user_message", ""))
        if plan is not None:
            response = AIMessage(
                content="",
                tool_calls=[{
                    "name": plan.tool,
                    "args": plan.arguments,
                    "id": "direct-" + state["request_id"],
                    "type": "tool_call",
                }],
            )
            update.update({"messages": [response], "response": response})
    LOGGER.info(
        "Agent strategy decision: requested=%s selected=%s reason=%s budget=%s traceId=%s",
        state.get("strategy", "auto"),
        decision.selected,
        decision.reason,
        budget.level,
        state["trace_id"],
    )
    return update


def route_after_prepare(state: AgentState) -> str:
    response = state.get("response")
    if response is not None and response.tool_calls:
        return "execute_tools"
    return "call_model"


def call_model(state: AgentState) -> Dict[str, Any]:
    if state.get("model_calls", 0) >= state.get("max_model_calls", 1):
        response = AIMessage(content="本次请求已达到模型调用预算，请缩小问题范围后重试。")
        return {
            "messages": [response],
            "response": response,
            "budget_exhausted": True,
            "budget_stop_reason": "model_call_limit",
        }
    remaining_ms = state.get("max_execution_ms", 25000) - elapsed_ms(state)
    if remaining_ms <= 0:
        response = AIMessage(content="本次请求已达到执行时间预算，请缩小问题范围后重试。")
        return {
            "messages": [response],
            "response": response,
            "budget_exhausted": True,
            "budget_stop_reason": "time_limit",
        }
    call_started = time.monotonic()
    model = llm_provider_registry.create_chat_model(
        state["provider"],
        LlmModelRequest(
            model=state.get("model"),
            temperature=state["temperature"],
            timeout_seconds=max(0.25, remaining_ms / 1000.0),
        ),
    )
    tools_bound = False
    if (
        state.get("selected_strategy") == "react"
        and state.get("tools_enabled", True)
        and state.get("tool_rounds", 0) < state.get("max_tool_rounds", 2)
        and state.get("tool_calls", 0) < state.get("max_tool_calls", 4)
        and state.get("model_calls", 0) < state.get("max_model_calls", 3) - 1
        and not state.get("budget_exhausted", False)
        and remaining_ms > 0
    ):
        model = model.bind_tools(tool_registry.model_tool_schemas())
        tools_bound = True
    stream_sink = MODEL_STREAM_SINK.get()
    # The candidate is internal only when another model call remains for review.
    # Under the low-cost one-call budget it is already the final answer, so keep
    # streaming it to the client instead of delaying all output until `done`.
    is_multi = state.get("orchestration_mode", "single") == "multi"
    is_multi_draft = (
        is_multi
        and state.get("model_calls", 0) + 1 < state.get("max_model_calls", 1)
    )
    if stream_sink is None or is_multi_draft:
        response = model.invoke(state["messages"])
    else:
        aggregate = None
        for chunk in model.stream(state["messages"]):
            aggregate = chunk if aggregate is None else aggregate + chunk
            if isinstance(chunk.content, str) and chunk.content:
                stream_sink(chunk.content)
        if aggregate is None:
            response = AIMessage(content="")
        else:
            response = message_chunk_to_message(aggregate)
    usage = message_usage(response)
    input_tokens = state.get("input_tokens", 0) + usage["input_tokens"]
    output_tokens = state.get("output_tokens", 0) + usage["output_tokens"]
    total_tokens = state.get("total_tokens", 0) + usage["total_tokens"]
    stop_reason = state.get("budget_stop_reason", "") if state.get("budget_exhausted", False) else ""
    if total_tokens >= state.get("max_total_tokens", 8000):
        stop_reason = "token_limit"
    elif elapsed_ms(state) >= state.get("max_execution_ms", 25000):
        stop_reason = "time_limit"
    elif response.tool_calls and not tools_bound:
        stop_reason = "model_call_limit"
    if stop_reason and response.tool_calls:
        response = AIMessage(content="本次请求已达到执行预算，无法继续调用工具。请缩小问题范围后重试。")
    update = {
        "messages": [response],
        "response": response,
        "model_calls": state.get("model_calls", 0) + 1,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
        "budget_exhausted": bool(stop_reason),
        "budget_stop_reason": stop_reason,
    }
    if is_multi and not response.tool_calls:
        update["draft_response"] = response
        steps = []
        if not any(item.get("agent") == "retrieval_agent" for item in state.get("agent_steps", [])):
            steps.append({
                "agent": "retrieval_agent",
                "status": "skipped",
                "durationMs": 0,
                "summary": "no_retrieval_required",
            })
        steps.append({
            "agent": "candidate_agent",
            "status": "completed",
            "durationMs": max(0, round((time.monotonic() - call_started) * 1000)),
            "inputTokens": usage["input_tokens"],
            "outputTokens": usage["output_tokens"],
            "summary": "candidate_answer_created",
        })
        update["agent_steps"] = steps
    return update


def execute_tools(state: AgentState) -> Dict[str, Any]:
    execution_started_at = state.get("execution_started_at", time.monotonic())
    context = ToolContext(
        request_id=state["request_id"],
        trace_id=state["trace_id"],
        user_id=state.get("user_id"),
        deadline_monotonic=(
            execution_started_at + state.get("max_execution_ms", 25000) / 1000.0
        ),
    )
    round_started = time.monotonic()
    calls = list(state["response"].tool_calls)
    results: List[Optional[ToolExecutionResult]] = [None] * len(calls)
    scheduled = []
    tool_calls = state.get("tool_calls", 0)
    budget_exhausted = state.get("budget_exhausted", False)
    stop_reason = state.get("budget_stop_reason", "")
    for index, call in enumerate(calls):
        if tool_calls >= state.get("max_tool_calls", 4):
            stop_reason = "tool_call_limit"
            budget_exhausted = True
            results[index] = budget_error(call, state, stop_reason)
        elif elapsed_ms(state) >= state.get("max_execution_ms", 25000):
            stop_reason = "time_limit"
            budget_exhausted = True
            results[index] = budget_error(call, state, stop_reason)
        else:
            scheduled.append((index, call))
            tool_calls += 1

    if scheduled:
        workers = min(tool_parallel_max_workers(), len(scheduled))
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="agent-branch") as executor:
            futures = {
                executor.submit(
                    tool_registry.invoke, call["name"], call.get("args") or {}, context
                ): (index, call)
                for index, call in scheduled
            }
            for future in as_completed(futures):
                index, call = futures[future]
                try:
                    results[index] = future.result()
                except Exception as error:
                    LOGGER.warning(
                        "Parallel agent branch failed: tool=%s errorType=%s traceId=%s",
                        call["name"], type(error).__name__, state["trace_id"],
                    )
                    results[index] = ToolExecutionResult(
                        requestId=state["request_id"],
                        traceId=state["trace_id"],
                        tool=call["name"],
                        success=False,
                        readOnly=tool_registry.is_read_only(call["name"]),
                        attempts=1,
                        durationMs=max(0, round((time.monotonic() - round_started) * 1000)),
                        error=ToolError(
                            code="TOOL_FAILED", message="工具分支执行失败", retryable=False
                        ),
                    )

    if scheduled and elapsed_ms(state) >= state.get("max_execution_ms", 25000):
        stop_reason = "time_limit"
        budget_exhausted = True

    messages = []
    tool_executions = []
    for call, result in zip(calls, results):
        assert result is not None
        messages.append(
            ToolMessage(
                content=result.model_dump_json(exclude_none=True),
                tool_call_id=call["id"],
                name=call["name"],
            )
        )
        tool_executions.append({
            "tool": result.tool,
            "success": result.success,
            "attempts": result.attempts,
            "durationMs": result.durationMs,
            "errorCode": result.error.code if result.error else "",
        })
    update = {
        "messages": messages,
        "tool_executions": tool_executions,
        "tool_rounds": state.get("tool_rounds", 0) + 1,
        "tool_calls": tool_calls,
        "budget_exhausted": budget_exhausted,
        "budget_stop_reason": stop_reason,
    }
    if state.get("orchestration_mode", "single") == "multi":
        failed = [item for item in tool_executions if not item["success"]]
        succeeded = [item for item in tool_executions if item["success"]]
        round_duration = max(0, round((time.monotonic() - round_started) * 1000))
        steps = [{
            "agent": "retrieval_agent",
            "status": "failed" if failed and not succeeded else "completed",
            "durationMs": round_duration,
            "summary": f"parallel={len(scheduled)};success={len(succeeded)};failed={len(failed)}",
            "errorCode": failed[0].get("errorCode", "") if failed else "",
        }]
        steps.extend({
            "agent": "tool_agent:" + item["tool"],
            "status": "completed" if item["success"] else "failed",
            "durationMs": item["durationMs"],
            "summary": f"attempts={item['attempts']}",
            "errorCode": item["errorCode"],
        } for item in tool_executions)
        retried = [item for item in tool_executions if item["success"] and item["attempts"] > 1]
        if failed or retried:
            if failed and succeeded:
                recovery_summary = "partial_results_used"
            elif failed:
                recovery_summary = "all_tools_failed_safe_continuation"
            else:
                recovery_summary = "tool_retry_succeeded"
            steps.append({
                "agent": "recovery_agent",
                "status": "completed",
                "durationMs": 0,
                "summary": recovery_summary,
                "errorCode": failed[0].get("errorCode", "") if failed else "",
            })
        update["agent_steps"] = steps
    return update


def review_and_answer(state: AgentState) -> Dict[str, Any]:
    """Validate the candidate against available evidence and emit only the corrected final answer."""
    draft = state.get("draft_response") or state.get("response")
    if draft is None:
        return {"agent_steps": [{
            "agent": "fact_check_agent", "status": "skipped", "summary": "missing_candidate"
        }]}
    if (
        state.get("model_calls", 0) >= state.get("max_model_calls", 1)
        or state.get("budget_exhausted", False)
        or elapsed_ms(state) >= state.get("max_execution_ms", 25000)
    ):
        return {
            "response": draft,
            "agent_steps": [
                {"agent": "fact_check_agent", "status": "skipped", "summary": "budget_limited"},
                {"agent": "answer_agent", "status": "completed", "summary": "candidate_used_as_final"},
            ],
        }
    started = time.monotonic()
    remaining_ms = state.get("max_execution_ms", 25000) - elapsed_ms(state)
    try:
        model = llm_provider_registry.create_chat_model(
            state["provider"],
            LlmModelRequest(
                model=state.get("model"),
                temperature=0.1,
                timeout_seconds=max(0.25, remaining_ms / 1000.0),
            ),
        )
        review_instruction = SystemMessage(content=(
            "你现在同时承担事实校验 Agent 和最终回答 Agent。先在内部逐项核对候选回答："
            "所有关于本地歌库、歌曲、歌手、类型、出处、收藏和推荐的事实必须能由当前对话中的"
            "结构化工具结果或‘本地歌库提供的最小歌曲元数据’支持。删除或改正无证据、矛盾、"
            "重复和越权内容；证据不足必须明确说明。校验数量时，count/returnedCount 仅代表本次返回条数，"
            "availableCount 代表筛选后可推荐数，只有 catalogMatchCount 才代表明确类型范围的歌库总数；"
            "不得把候选条数表述为歌库总数。最后只输出修正后的自然中文回答，"
            "不要输出校验过程、评分、JSON、思维链或‘候选回答’字样。"
        ))
        review_messages = [review_instruction] + list(state["messages"])
        stream_sink = MODEL_STREAM_SINK.get()
        if stream_sink is None:
            response = model.invoke(review_messages)
        else:
            aggregate = None
            for chunk in model.stream(review_messages):
                aggregate = chunk if aggregate is None else aggregate + chunk
                if isinstance(chunk.content, str) and chunk.content:
                    stream_sink(chunk.content)
            response = AIMessage(content="") if aggregate is None else message_chunk_to_message(aggregate)
    except Exception as error:
        duration = max(0, round((time.monotonic() - started) * 1000))
        LOGGER.warning(
            "Fact-check agent failed; preserving candidate: errorType=%s traceId=%s",
            type(error).__name__, state["trace_id"],
        )
        return {
            "response": draft,
            "model_calls": state.get("model_calls", 0) + 1,
            "agent_steps": [
                {
                    "agent": "fact_check_agent", "status": "failed", "durationMs": duration,
                    "summary": "candidate_preserved", "errorCode": "REVIEW_FAILED",
                },
                {
                    "agent": "recovery_agent", "status": "completed", "durationMs": 0,
                    "summary": "candidate_used_after_review_failure",
                },
                {
                    "agent": "answer_agent", "status": "completed", "durationMs": 0,
                    "summary": "candidate_used_as_final",
                },
            ],
        }
    usage = message_usage(response)
    review_status = "completed"
    review_error = ""
    if not str(response.content or "").strip():
        response = draft
        review_status = "failed"
        review_error = "EMPTY_RESPONSE"
    total_tokens = state.get("total_tokens", 0) + usage["total_tokens"]
    stop_reason = state.get("budget_stop_reason", "")
    if total_tokens >= state.get("max_total_tokens", 8000):
        stop_reason = "token_limit"
    duration = max(0, round((time.monotonic() - started) * 1000))
    return {
        "messages": [response],
        "response": response,
        "model_calls": state.get("model_calls", 0) + 1,
        "input_tokens": state.get("input_tokens", 0) + usage["input_tokens"],
        "output_tokens": state.get("output_tokens", 0) + usage["output_tokens"],
        "total_tokens": total_tokens,
        "budget_exhausted": bool(stop_reason),
        "budget_stop_reason": stop_reason,
        "agent_steps": ([
            {
                "agent": "fact_check_agent", "status": review_status, "durationMs": duration,
                "inputTokens": usage["input_tokens"], "outputTokens": 0,
                "summary": "evidence_checked" if not review_error else "candidate_preserved",
                "errorCode": review_error,
            },
        ] + ([{
                "agent": "recovery_agent", "status": "completed", "durationMs": 0,
                "summary": "candidate_used_after_empty_review",
            }] if review_error else []) + [
            {
                "agent": "answer_agent", "status": "completed", "durationMs": duration,
                "inputTokens": 0, "outputTokens": usage["output_tokens"],
                "summary": "final_answer_created" if not review_error else "candidate_used_as_final",
            },
        ]),
    }


def route_after_model(state: AgentState) -> str:
    if (
        state.get("selected_strategy") == "react"
        and state["response"].tool_calls
        and state.get("tool_rounds", 0) < state.get("max_tool_rounds", 2)
        and state.get("tool_calls", 0) < state.get("max_tool_calls", 4)
        and state.get("model_calls", 0) < state.get("max_model_calls", 3)
        and not state.get("budget_exhausted", False)
    ):
        return "execute_tools"
    if (
        state.get("orchestration_mode", "single") == "multi"
        and state.get("draft_response") is not None
    ):
        return "review_and_answer"
    return END


builder = StateGraph(AgentState)
builder.add_node("prepare_strategy", prepare_strategy)
builder.add_node("call_model", call_model)
builder.add_node("execute_tools", execute_tools)
builder.add_node("review_and_answer", review_and_answer)
builder.add_edge(START, "prepare_strategy")
builder.add_conditional_edges(
    "prepare_strategy",
    route_after_prepare,
    {"execute_tools": "execute_tools", "call_model": "call_model"},
)
builder.add_conditional_edges("call_model", route_after_model, {
    "execute_tools": "execute_tools", "review_and_answer": "review_and_answer", END: END
})
builder.add_edge("execute_tools", "call_model")
builder.add_edge("review_and_answer", END)
agent_graph = builder.compile()
