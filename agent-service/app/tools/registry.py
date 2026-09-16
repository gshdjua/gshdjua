import time
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional, Type

from pydantic import BaseModel

from .executor import ToolExecutor
from .models import ToolContext, ToolDescriptor, ToolError, ToolExecutionResult


ToolHandler = Callable[[BaseModel, ToolContext], Any]


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    args_model: Type[BaseModel]
    handler: ToolHandler
    read_only: bool = True
    timeout_seconds: float = 3.0
    requires_user: bool = False
    retry_enabled: Optional[bool] = None


class ToolRegistry:
    def __init__(self, executor: ToolExecutor | None = None) -> None:
        self._tools: Dict[str, ToolDefinition] = {}
        self._executor = executor or ToolExecutor()

    def register(self, definition: ToolDefinition) -> None:
        if definition.name in self._tools:
            raise ValueError("Tool already registered: " + definition.name)
        self._tools[definition.name] = definition

    def descriptors(self) -> List[ToolDescriptor]:
        return [
            ToolDescriptor(
                name=item.name,
                description=item.description,
                readOnly=item.read_only,
                requiresAuthentication=item.requires_user,
                timeoutSeconds=item.timeout_seconds,
                parameters=item.args_model.model_json_schema(),
            )
            for item in self._tools.values()
        ]

    def model_tool_schemas(self) -> List[Dict[str, Any]]:
        return [
            {
                "type": "function",
                "function": {
                    "name": item.name,
                    "description": item.description,
                    "parameters": item.args_model.model_json_schema(),
                },
            }
            for item in self._tools.values()
        ]

    def is_read_only(self, name: str) -> bool:
        definition = self._tools.get(name)
        return definition.read_only if definition is not None else False

    def invoke(self, name: str, arguments: Dict[str, Any], context: ToolContext) -> ToolExecutionResult:
        definition = self._tools.get(name)
        if definition is None:
            started = time.perf_counter()
            return ToolExecutionResult(
                requestId=context.request_id,
                traceId=context.trace_id,
                tool=name,
                success=False,
                readOnly=False,
                error=ToolError(code="TOOL_NOT_FOUND", message="未注册的工具", retryable=False),
                durationMs=round((time.perf_counter() - started) * 1000),
            )
        return self._executor.execute(definition, arguments, context)
