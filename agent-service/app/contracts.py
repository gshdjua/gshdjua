from datetime import datetime
from typing import Dict, List, Literal, Optional

from pydantic import BaseModel, Field


class AgentMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=20000)


class AgentOptions(BaseModel):
    provider: str = "deepseek"
    model: Optional[str] = None
    temperature: float = Field(default=0.4, ge=0.0, le=2.0)
    strategy: str = "direct"


class AgentChatRequest(BaseModel):
    protocolVersion: str = "1.0"
    requestId: str = Field(min_length=1, max_length=100)
    conversationId: Optional[str] = None
    userId: Optional[str] = None
    messages: List[AgentMessage] = Field(min_length=1, max_length=30)
    options: AgentOptions = Field(default_factory=AgentOptions)
    metadata: Dict[str, str] = Field(default_factory=dict)


class TokenUsage(BaseModel):
    inputTokens: int = 0
    outputTokens: int = 0
    totalTokens: int = 0


class AgentChatResponse(BaseModel):
    protocolVersion: str = "1.0"
    requestId: str
    answer: str
    provider: str
    model: str
    strategy: str
    usage: TokenUsage
    finishReason: str = "stop"
    latencyMs: int


class MemorySettingsUpdate(BaseModel):
    enabled: bool


class MemoryCaptureRequest(BaseModel):
    requestId: str = Field(min_length=1, max_length=100)
    conversationId: Optional[str] = None
    message: str = Field(min_length=1, max_length=2000)


class MemoryUpdate(BaseModel):
    content: str = Field(min_length=1, max_length=500)


class MemoryRecord(BaseModel):
    id: int
    memoryType: str
    content: str
    importance: float
    confidence: float
    sourceConversationId: Optional[str] = None
    createdAt: Optional[datetime] = None
    updatedAt: Optional[datetime] = None
