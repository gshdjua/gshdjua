import logging
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Type

from pydantic import BaseModel, ValidationError

from .models import ToolContext, ToolDescriptor, ToolError, ToolExecutionResult


LOGGER = logging.getLogger(__name__)
ToolHandler = Callable[[BaseModel, ToolContext], Any]


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    args_model: Type[BaseModel]
    handler: ToolHandler
    read_only: bool = True
    timeout_seconds: float = 3.0


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: Dict[str, ToolDefinition] = {}

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

    def invoke(self, name: str, arguments: Dict[str, Any], context: ToolContext) -> ToolExecutionResult:
        started = time.perf_counter()
        definition = self._tools.get(name)
        if definition is None:
            return self._error(name, context, False, "TOOL_NOT_FOUND", "未注册的工具", False, started)
        try:
            validated = definition.args_model.model_validate(arguments)
        except ValidationError as error:
            message = "; ".join(item["msg"] for item in error.errors())
            return self._error(
                name, context, definition.read_only, "INVALID_ARGUMENTS", message, False, started
            )

        executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="agent-tool")
        future = executor.submit(definition.handler, validated, context)
        try:
            data = future.result(timeout=definition.timeout_seconds)
            result = ToolExecutionResult(
                requestId=context.request_id,
                traceId=context.trace_id,
                tool=name,
                success=True,
                readOnly=definition.read_only,
                data=data,
                durationMs=round((time.perf_counter() - started) * 1000),
            )
        except TimeoutError:
            future.cancel()
            result = self._error(
                name, context, definition.read_only, "TOOL_TIMEOUT", "工具调用超时", True, started
            )
        except Exception as error:
            result = self._error(
                name, context, definition.read_only, "TOOL_FAILED", str(error) or "工具调用失败", True, started
            )
        finally:
            executor.shutdown(wait=False, cancel_futures=True)
        LOGGER.info(
            "Agent tool decision: tool=%s success=%s durationMs=%s traceId=%s",
            name,
            result.success,
            result.durationMs,
            context.trace_id,
        )
        return result

    @staticmethod
    def _error(
        name: str,
        context: ToolContext,
        read_only: bool,
        code: str,
        message: str,
        retryable: bool,
        started: float,
    ) -> ToolExecutionResult:
        return ToolExecutionResult(
            requestId=context.request_id,
            traceId=context.trace_id,
            tool=name,
            success=False,
            readOnly=read_only,
            error=ToolError(code=code, message=message, retryable=retryable),
            durationMs=round((time.perf_counter() - started) * 1000),
        )
