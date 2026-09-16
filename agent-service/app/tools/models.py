from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator


class SongSearchArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=200, description="歌曲名、歌手、类型、出处或描述关键词")
    limit: int = Field(default=5, ge=1, le=20, description="最多返回的歌曲数量")


class FavoriteSearchArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    query: Optional[str] = Field(default=None, max_length=200, description="可选的歌名、歌手、类型或出处筛选词")
    limit: int = Field(default=10, ge=1, le=20, description="最多返回的收藏数量")


class RecommendSongsArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=500, description="用户当前的完整推荐需求")
    limit: int = Field(default=5, ge=1, le=20, description="最多返回的候选歌曲数量")
    exclude_audio_ids: List[int] = Field(
        default_factory=list,
        max_length=50,
        description="本轮必须排除的歌曲 ID，例如上一轮已经推荐的歌曲",
    )


class VectorSearchArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=500, description="用于语义检索的自然语言描述")
    limit: int = Field(default=5, ge=1, le=20, description="最多返回的语义候选数量")
    audio_ids: Optional[List[int]] = Field(
        default=None,
        max_length=100,
        description="可选的候选歌曲 ID 范围",
    )


class SongDetailArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    audio_id: Optional[int] = Field(default=None, ge=1, description="明确的歌曲 ID")
    query: Optional[str] = Field(default=None, max_length=200, description="明确的歌名或‘歌名 + 歌手’")

    @model_validator(mode="after")
    def require_identifier(self):
        if self.audio_id is None and not (self.query or "").strip():
            raise ValueError("audio_id 和 query 至少提供一个")
        return self


class ToolContext(BaseModel):
    request_id: str
    trace_id: str
    user_id: Optional[str] = None
    deadline_monotonic: Optional[float] = Field(default=None, exclude=True)


class ToolError(BaseModel):
    code: str
    message: str
    retryable: bool = False


class ToolExecutionResult(BaseModel):
    protocolVersion: str = "1.0"
    requestId: str
    traceId: str
    tool: str
    success: bool
    readOnly: bool
    data: Any = None
    error: Optional[ToolError] = None
    attempts: int = 0
    durationMs: int = 0


class ToolDescriptor(BaseModel):
    name: str
    description: str
    readOnly: bool
    requiresAuthentication: bool
    timeoutSeconds: float
    parameters: Dict[str, Any]


class ToolCatalogResponse(BaseModel):
    protocolVersion: str = "1.0"
    tools: List[ToolDescriptor]
