from typing import Annotated, Any, Dict, List, TypedDict

from langchain_core.messages import AIMessage, BaseMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages

from .providers import create_deepseek_model


class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]
    provider: str
    model: str
    temperature: float
    strategy: str
    response: AIMessage


def call_model(state: AgentState) -> Dict[str, Any]:
    if state["provider"] != "deepseek":
        raise ValueError("Unsupported provider: " + state["provider"])
    model = create_deepseek_model(state.get("model"), state["temperature"])
    response = model.invoke(state["messages"])
    return {"messages": [response], "response": response}


builder = StateGraph(AgentState)
builder.add_node("call_model", call_model)
builder.add_edge(START, "call_model")
builder.add_edge("call_model", END)
agent_graph = builder.compile()

