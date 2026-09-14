import logging
from typing import Annotated, Any, Dict, List, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages

from .providers import create_deepseek_model
from .strategies import strategy_router
from .tools import ToolContext, tool_registry


LOGGER = logging.getLogger(__name__)


class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]
    provider: str
    model: str
    temperature: float
    strategy: str
    selected_strategy: str
    strategy_reason: str
    max_tool_rounds: int
    response: AIMessage
    request_id: str
    trace_id: str
    user_id: str | None
    tool_rounds: int
    tools_enabled: bool
    user_message: str


def prepare_strategy(state: AgentState) -> Dict[str, Any]:
    decision = strategy_router.select(
        state.get("strategy", "auto"),
        state.get("user_message", ""),
        state.get("tools_enabled", True),
    )
    update: Dict[str, Any] = {
        "selected_strategy": decision.selected,
        "strategy_reason": decision.reason,
        "max_tool_rounds": decision.max_tool_rounds,
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
        "Agent strategy decision: requested=%s selected=%s reason=%s traceId=%s",
        state.get("strategy", "auto"),
        decision.selected,
        decision.reason,
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
    model = create_deepseek_model(state.get("model"), state["temperature"])
    if (
        state.get("selected_strategy") == "react"
        and state.get("tools_enabled", True)
        and state.get("tool_rounds", 0) < state.get("max_tool_rounds", 2)
    ):
        model = model.bind_tools(tool_registry.model_tool_schemas())
    response = model.invoke(state["messages"])
    return {"messages": [response], "response": response}


def execute_tools(state: AgentState) -> Dict[str, Any]:
    context = ToolContext(
        request_id=state["request_id"],
        trace_id=state["trace_id"],
        user_id=state.get("user_id"),
    )
    messages = []
    for call in state["response"].tool_calls:
        result = tool_registry.invoke(call["name"], call.get("args") or {}, context)
        messages.append(
            ToolMessage(
                content=result.model_dump_json(exclude_none=True),
                tool_call_id=call["id"],
                name=call["name"],
            )
        )
    return {"messages": messages, "tool_rounds": state.get("tool_rounds", 0) + 1}


def route_after_model(state: AgentState) -> str:
    if (
        state.get("selected_strategy") == "react"
        and state["response"].tool_calls
        and state.get("tool_rounds", 0) < state.get("max_tool_rounds", 2)
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
