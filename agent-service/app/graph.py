import logging
import operator
import time
from typing import Annotated, Any, Dict, List, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages

from .providers import create_deepseek_model
from .strategies import strategy_router
from .tools import ToolContext, tool_registry
from .tools.models import ToolError, ToolExecutionResult


LOGGER = logging.getLogger(__name__)


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
    update: Dict[str, Any] = {
        "selected_strategy": decision.selected,
        "strategy_reason": decision.reason,
        "cost_budget": budget.level,
        "max_tool_rounds": budget.max_tool_rounds,
        "max_tool_calls": budget.max_tool_calls,
        "max_model_calls": budget.max_model_calls,
        "max_total_tokens": budget.max_total_tokens,
        "max_execution_ms": budget.max_execution_ms,
        "execution_started_at": state.get("execution_started_at", time.monotonic()),
        "budget_exhausted": state.get("budget_exhausted", False),
        "budget_stop_reason": state.get("budget_stop_reason", ""),
    }
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
    if state["provider"] != "deepseek":
        raise ValueError("Unsupported provider: " + state["provider"])
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
    model = create_deepseek_model(
        state.get("model"),
        state["temperature"],
        timeout_seconds=max(0.25, remaining_ms / 1000.0),
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
    response = model.invoke(state["messages"])
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
    return {
        "messages": [response],
        "response": response,
        "model_calls": state.get("model_calls", 0) + 1,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
        "budget_exhausted": bool(stop_reason),
        "budget_stop_reason": stop_reason,
    }


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
    messages = []
    tool_executions = []
    tool_calls = state.get("tool_calls", 0)
    budget_exhausted = state.get("budget_exhausted", False)
    stop_reason = state.get("budget_stop_reason", "")
    for call in state["response"].tool_calls:
        if tool_calls >= state.get("max_tool_calls", 4):
            stop_reason = "tool_call_limit"
            budget_exhausted = True
            result = budget_error(call, state, stop_reason)
        elif elapsed_ms(state) >= state.get("max_execution_ms", 25000):
            stop_reason = "time_limit"
            budget_exhausted = True
            result = budget_error(call, state, stop_reason)
        else:
            result = tool_registry.invoke(call["name"], call.get("args") or {}, context)
            tool_calls += 1
            if elapsed_ms(state) >= state.get("max_execution_ms", 25000):
                stop_reason = "time_limit"
                budget_exhausted = True
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
    return {
        "messages": messages,
        "tool_executions": tool_executions,
        "tool_rounds": state.get("tool_rounds", 0) + 1,
        "tool_calls": tool_calls,
        "budget_exhausted": budget_exhausted,
        "budget_stop_reason": stop_reason,
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
    return END


builder = StateGraph(AgentState)
builder.add_node("prepare_strategy", prepare_strategy)
builder.add_node("call_model", call_model)
builder.add_node("execute_tools", execute_tools)
builder.add_edge(START, "prepare_strategy")
builder.add_conditional_edges(
    "prepare_strategy",
    route_after_prepare,
    {"execute_tools": "execute_tools", "call_model": "call_model"},
)
builder.add_conditional_edges("call_model", route_after_model, {"execute_tools": "execute_tools", END: END})
builder.add_edge("execute_tools", "call_model")
agent_graph = builder.compile()
