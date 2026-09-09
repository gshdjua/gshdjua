import time

from fastapi import FastAPI, HTTPException
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from .config import api_key, base_url, default_model
from .contracts import (
    AgentChatRequest,
    AgentChatResponse,
    AgentMessage,
    MemoryRecord,
    MemorySettingsUpdate,
    MemoryUpdate,
    TokenUsage,
)
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
        system_messages = [item for item in payload.messages if item.role == "system"]
        conversation_messages = [item for item in payload.messages if item.role != "system"]
        current_message = conversation_messages[-1]
        incoming_history = conversation_messages[:-1]
        raw_user_message = payload.metadata.get("userMessage", current_message.content)
        memory = memory_repository.load(payload.conversationId, payload.userId, raw_user_message)
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


@app.get("/v1/memory/users/{user_id}/settings")
def memory_settings(user_id: str) -> dict:
    return memory_repository.settings(user_id)


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
