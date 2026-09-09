import time

from fastapi import FastAPI, HTTPException
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from .config import api_key, base_url, default_model
from .contracts import AgentChatRequest, AgentChatResponse, AgentMessage, TokenUsage
from .graph import agent_graph
from .memory import memory_repository


app = FastAPI(title="MusicHub Agent Service", version="0.1.0")


def to_langchain_message(role: str, content: str):
    if role == "system":
        return SystemMessage(content=content)
    if role == "assistant":
        return AIMessage(content=content)
    return HumanMessage(content=content)


def extract_usage(message: AIMessage) -> TokenUsage:
    usage = message.usage_metadata or {}
    response_usage = message.response_metadata.get("token_usage", {})
    input_tokens = int(usage.get("input_tokens", response_usage.get("prompt_tokens", 0)) or 0)
    output_tokens = int(usage.get("output_tokens", response_usage.get("completion_tokens", 0)) or 0)
    total_tokens = int(usage.get("total_tokens", response_usage.get("total_tokens", 0)) or 0)
    return TokenUsage(
        inputTokens=input_tokens,
        outputTokens=output_tokens,
        totalTokens=total_tokens or input_tokens + output_tokens,
    )


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
    }


@app.post("/v1/chat", response_model=AgentChatResponse)
def chat(payload: AgentChatRequest) -> AgentChatResponse:
    if payload.protocolVersion != "1.0":
        raise HTTPException(status_code=400, detail="Unsupported protocol version")
    started = time.perf_counter()
    model_name = payload.options.model or default_model()
    try:
        memory = memory_repository.load(payload.conversationId, payload.userId)
        system_messages = [item for item in payload.messages if item.role == "system"]
        conversation_messages = [item for item in payload.messages if item.role != "system"]
        current_message = conversation_messages[-1]
        incoming_history = conversation_messages[:-1]
        memory_notes = []
        if memory.summary:
            memory_notes.append("此前对话摘要：" + memory.summary)
        if memory.long_term_memories:
            memory_notes.append("用户长期偏好：" + "；".join(memory.long_term_memories))
        effective_messages = list(system_messages)
        if memory_notes:
            effective_messages.append(AgentMessage(role="system", content="\n".join(memory_notes)))
        effective_messages.extend(memory.recent_messages or incoming_history[-6:])
        effective_messages.append(current_message)
        state = agent_graph.invoke(
            {
                "messages": [to_langchain_message(item.role, item.content) for item in effective_messages],
                "provider": payload.options.provider,
                "model": model_name,
                "temperature": payload.options.temperature,
                "strategy": payload.options.strategy,
            }
        )
        response = state["response"]
        raw_user_message = payload.metadata.get("userMessage", current_message.content)
        memory_repository.save(
            payload.conversationId,
            payload.userId,
            memory.summary,
            memory.recent_messages,
            incoming_history,
            raw_user_message,
            str(response.content),
        )
        return AgentChatResponse(
            requestId=payload.requestId,
            answer=str(response.content),
            provider=payload.options.provider,
            model=model_name,
            strategy=payload.options.strategy,
            usage=extract_usage(response),
            finishReason=str(response.response_metadata.get("finish_reason", "stop")),
            latencyMs=round((time.perf_counter() - started) * 1000),
        )
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    except Exception as error:
        raise HTTPException(status_code=502, detail="Model invocation failed: " + str(error)) from error
