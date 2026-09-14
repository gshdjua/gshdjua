import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from dataclasses import dataclass
from enum import Enum
from typing import TYPE_CHECKING, Any, Callable, Dict, Optional

from pydantic import ValidationError

from .exceptions import ToolInvocationError
from .models import ToolContext, ToolError, ToolExecutionResult

if TYPE_CHECKING:
    from .registry import ToolDefinition


LOGGER = logging.getLogger(__name__)


class CircuitState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass
class _Circuit:
    failures: int = 0
    state: CircuitState = CircuitState.CLOSED
    opened_at: float = 0.0
    half_open_in_flight: bool = False


class CircuitBreaker:
    def __init__(
        self,
        failure_threshold: int = 3,
        recovery_seconds: float = 30.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if failure_threshold < 1:
            raise ValueError("failure_threshold must be at least 1")
        if recovery_seconds < 0:
            raise ValueError("recovery_seconds cannot be negative")
        self._failure_threshold = failure_threshold
        self._recovery_seconds = recovery_seconds
        self._clock = clock
        self._circuits: Dict[str, _Circuit] = {}
        self._lock = threading.Lock()

    def allow(self, tool: str) -> bool:
        with self._lock:
            circuit = self._circuits.setdefault(tool, _Circuit())
            if circuit.state == CircuitState.CLOSED:
                return True
            if circuit.state == CircuitState.OPEN:
                if self._clock() - circuit.opened_at < self._recovery_seconds:
                    return False
                circuit.state = CircuitState.HALF_OPEN
            if circuit.half_open_in_flight:
                return False
            circuit.half_open_in_flight = True
            return True

    def record_success(self, tool: str) -> None:
        with self._lock:
            self._circuits[tool] = _Circuit()

    def record_failure(self, tool: str) -> None:
        with self._lock:
            circuit = self._circuits.setdefault(tool, _Circuit())
            circuit.half_open_in_flight = False
            circuit.failures += 1
            if circuit.state == CircuitState.HALF_OPEN or circuit.failures >= self._failure_threshold:
                circuit.state = CircuitState.OPEN
                circuit.opened_at = self._clock()

    def release_probe(self, tool: str) -> None:
        with self._lock:
            circuit = self._circuits.get(tool)
            if circuit is not None:
                circuit.half_open_in_flight = False

    def state(self, tool: str) -> CircuitState:
        with self._lock:
            return self._circuits.get(tool, _Circuit()).state


class ToolPermissionChecker:
    @staticmethod
    def authorize(definition: "ToolDefinition", context: ToolContext) -> Optional[ToolError]:
        if definition.requires_user and not (context.user_id or "").strip():
            return ToolError(code="UNAUTHORIZED", message="该工具需要登录用户上下文", retryable=False)
        return None


class ToolExecutor:
    """Runs every registered tool through one governed execution pipeline."""

    def __init__(
        self,
        max_attempts: int = 2,
        retry_backoff_seconds: float = 0.1,
        circuit_breaker: Optional[CircuitBreaker] = None,
        permission_checker: Optional[ToolPermissionChecker] = None,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        if retry_backoff_seconds < 0:
            raise ValueError("retry_backoff_seconds cannot be negative")
        self._max_attempts = max_attempts
        self._retry_backoff_seconds = retry_backoff_seconds
        self._circuit_breaker = circuit_breaker or CircuitBreaker()
        self._permission_checker = permission_checker or ToolPermissionChecker()

    def execute(
        self,
        definition: "ToolDefinition",
        arguments: Dict[str, Any],
        context: ToolContext,
    ) -> ToolExecutionResult:
        started = time.perf_counter()
        try:
            validated = definition.args_model.model_validate(arguments)
        except ValidationError as error:
            message = "; ".join(item["msg"] for item in error.errors())
            return self._error(definition, context, "INVALID_ARGUMENTS", message, False, started, 0)

        permission_error = self._permission_checker.authorize(definition, context)
        if permission_error is not None:
            return self._error(
                definition,
                context,
                permission_error.code,
                permission_error.message,
                permission_error.retryable,
                started,
                0,
            )

        if not self._circuit_breaker.allow(definition.name):
            return self._error(
                definition,
                context,
                "CIRCUIT_OPEN",
                "工具服务暂时不可用，请稍后重试",
                True,
                started,
                0,
            )

        retry_enabled = (
            definition.read_only if definition.retry_enabled is None else definition.retry_enabled
        )
        attempts = self._max_attempts if retry_enabled else 1
        last_error: Optional[ToolInvocationError] = None
        for attempt in range(1, attempts + 1):
            try:
                data = self._invoke_once(definition, validated, context)
                self._circuit_breaker.record_success(definition.name)
                return self._success(definition, context, data, started, attempt)
            except FutureTimeoutError:
                last_error = ToolInvocationError("TOOL_TIMEOUT", "工具调用超时", True)
            except ToolInvocationError as error:
                last_error = error
            except Exception:
                LOGGER.exception(
                    "Unexpected tool failure: tool=%s traceId=%s", definition.name, context.trace_id
                )
                last_error = ToolInvocationError("TOOL_FAILED", "工具执行失败", False)

            if not last_error.retryable or attempt >= attempts:
                break
            if self._retry_backoff_seconds:
                time.sleep(self._retry_backoff_seconds * (2 ** (attempt - 1)))

        if last_error is not None and last_error.retryable:
            self._circuit_breaker.record_failure(definition.name)
        else:
            self._circuit_breaker.release_probe(definition.name)
        assert last_error is not None
        return self._error(
            definition,
            context,
            last_error.code,
            last_error.message,
            last_error.retryable,
            started,
            attempt,
        )

    @staticmethod
    def _invoke_once(definition: "ToolDefinition", validated, context: ToolContext) -> Any:
        executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="agent-tool")
        future = executor.submit(definition.handler, validated, context)
        try:
            return future.result(timeout=definition.timeout_seconds)
        finally:
            executor.shutdown(wait=False, cancel_futures=True)

    @staticmethod
    def _success(
        definition: "ToolDefinition",
        context: ToolContext,
        data: Any,
        started: float,
        attempts: int,
    ) -> ToolExecutionResult:
        result = ToolExecutionResult(
            requestId=context.request_id,
            traceId=context.trace_id,
            tool=definition.name,
            success=True,
            readOnly=definition.read_only,
            data=data,
            attempts=attempts,
            durationMs=round((time.perf_counter() - started) * 1000),
        )
        ToolExecutor._audit(result)
        return result

    @staticmethod
    def _error(
        definition: "ToolDefinition",
        context: ToolContext,
        code: str,
        message: str,
        retryable: bool,
        started: float,
        attempts: int,
    ) -> ToolExecutionResult:
        result = ToolExecutionResult(
            requestId=context.request_id,
            traceId=context.trace_id,
            tool=definition.name,
            success=False,
            readOnly=definition.read_only,
            error=ToolError(code=code, message=message, retryable=retryable),
            attempts=attempts,
            durationMs=round((time.perf_counter() - started) * 1000),
        )
        ToolExecutor._audit(result)
        return result

    @staticmethod
    def _audit(result: ToolExecutionResult) -> None:
        LOGGER.info(
            "Agent tool execution: tool=%s success=%s attempts=%s durationMs=%s errorCode=%s traceId=%s",
            result.tool,
            result.success,
            result.attempts,
            result.durationMs,
            result.error.code if result.error else "",
            result.traceId,
        )
