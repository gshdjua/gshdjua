from datetime import datetime
from typing import Dict, List, Literal, Optional

from pydantic import BaseModel, Field

from .config import default_provider


class AgentMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=20000)


class AgentOptions(BaseModel):
    provider: str = Field(default_factory=default_provider)
    model: Optional[str] = None
    temperature: float = Field(default=0.4, ge=0.0, le=2.0)
    strategy: Literal["auto", "direct", "react"] = "auto"
    costBudget: Literal["low", "standard", "high"] = "standard"


class AgentChatRequest(BaseModel):
    protocolVersion: str = "1.0"
    requestId: str = Field(min_length=1, max_length=100)
    conversationId: Optional[str] = None
    userId: Optional[str] = None
    messages: List[AgentMessage] = Field(min_length=1, max_length=30)
    options: AgentOptions = Field(default_factory=AgentOptions)
    metadata: Dict[str, str] = Field(default_factory=dict)


class StrategyPreviewRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)
    strategy: Literal["auto", "direct", "react"] = "auto"
    costBudget: Literal["low", "standard", "high"] = "standard"


class StrategyPreviewResponse(BaseModel):
    selectedStrategy: str
    strategyReason: str
    costBudget: str
    plannedTool: str = ""
    maxModelCalls: int
    maxToolCalls: int
    maxToolRounds: int
    maxTotalTokens: int
    maxExecutionMs: int


class TokenUsage(BaseModel):
    inputTokens: int = 0
    outputTokens: int = 0
    totalTokens: int = 0


class ExecutionBudgetReport(BaseModel):
    level: str
    modelCalls: int = 0
    toolCalls: int = 0
    toolRounds: int = 0
    maxModelCalls: int
    maxToolCalls: int
    maxToolRounds: int
    maxTotalTokens: int
    maxExecutionMs: int
    elapsedMs: int = 0
    exceeded: bool = False
    stopReason: str = ""


class ToolExecutionAudit(BaseModel):
    tool: str
    success: bool
    attempts: int = 0
    durationMs: int = 0
    errorCode: str = ""


class AgentChatResponse(BaseModel):
    protocolVersion: str = "1.0"
    requestId: str
    traceId: str
    answer: str
    provider: str
    model: str
    strategy: str
    strategyReason: str = ""
    usage: TokenUsage
    budget: ExecutionBudgetReport
    toolExecutions: List[ToolExecutionAudit] = Field(default_factory=list)
    finishReason: str = "stop"
    latencyMs: int


class ExecutionAuditRecord(BaseModel):
    traceId: str
    requestId: str
    provider: str
    model: str
    requestedStrategy: str
    selectedStrategy: str
    strategyReason: str = ""
    costBudget: str
    modelCalls: int = 0
    toolCalls: int = 0
    toolRounds: int = 0
    toolExecutions: List[ToolExecutionAudit] = Field(default_factory=list)
    inputTokens: int = 0
    outputTokens: int = 0
    totalTokens: int = 0
    latencyMs: int = 0
    budgetExceeded: bool = False
    stopReason: str = ""
    finishReason: str = ""
    status: str = "success"
    errorCode: str = ""
    createdAt: Optional[datetime] = None


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
    topic: str = ""
    status: str = "active"
    expiresAt: Optional[datetime] = None
    lastAccessedAt: Optional[datetime] = None
    accessCount: int = 0
    supersededBy: Optional[int] = None
