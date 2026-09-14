from typing import Annotated, Any, Dict, List, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages

from .providers import create_deepseek_model
from .tools import ToolContext, tool_registry


MAX_TOOL_ROUNDS = 2


class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]
    provider: str
    model: str
    temperature: float
    strategy: str
    response: AIMessage
    request_id: str
    trace_id: str
    user_id: str | None
    tool_rounds: int
    tools_enabled: bool


def call_model(state: AgentState) -> Dict[str, Any]:
    if state["provider"] != "deepseek":
        raise ValueError("Unsupported provider: " + state["provider"])
    model = create_deepseek_model(state.get("model"), state["temperature"])
    if state.get("tools_enabled", True) and state.get("tool_rounds", 0) < MAX_TOOL_ROUNDS:
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
    if state["response"].tool_calls and state.get("tool_rounds", 0) < MAX_TOOL_ROUNDS:
        return "execute_tools"
    return END


builder = StateGraph(AgentState)
builder.add_node("call_model", call_model)
builder.add_node("execute_tools", execute_tools)
builder.add_edge(START, "call_model")
builder.add_conditional_edges("call_model", route_after_model, {"execute_tools": "execute_tools", END: END})
builder.add_edge("execute_tools", "call_model")
agent_graph = builder.compile()
