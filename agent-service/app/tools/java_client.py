import json
import socket
from typing import Any, Dict
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from ..config import java_tool_base_url, tool_api_key, tool_http_timeout_seconds
from .exceptions import ToolInvocationError
from .models import ToolContext


class JavaToolClient:
    def execute(
        self,
        tool: str,
        arguments: Dict[str, Any],
        context: ToolContext,
        timeout_seconds: float | None = None,
    ) -> Any:
        payload = {
            "protocolVersion": "1.0",
            "requestId": context.request_id,
            "traceId": context.trace_id,
            "userId": context.user_id,
            "tool": tool,
            "arguments": arguments,
        }
        headers = {"Content-Type": "application/json; charset=UTF-8"}
        if tool_api_key():
            headers["X-Agent-Tool-Key"] = tool_api_key()
        request = Request(
            java_tool_base_url() + "/internal/agent/tools/execute",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with urlopen(request, timeout=timeout_seconds or tool_http_timeout_seconds()) as response:
                result = json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            retryable = error.code in (408, 425, 429) or error.code >= 500
            code = "TOOL_UNAVAILABLE" if retryable else "TOOL_HTTP_ERROR"
            raise ToolInvocationError(code, "Java 工具服务请求失败", retryable) from error
        except (socket.timeout, TimeoutError) as error:
            raise ToolInvocationError("TOOL_TIMEOUT", "Java 工具服务响应超时", True) from error
        except URLError as error:
            raise ToolInvocationError("TOOL_UNAVAILABLE", "Java 工具服务暂时不可用", True) from error
        except (json.JSONDecodeError, UnicodeDecodeError, AttributeError) as error:
            raise ToolInvocationError("INVALID_TOOL_RESPONSE", "Java 工具服务返回格式无效", False) from error
        if not isinstance(result, dict):
            raise ToolInvocationError("INVALID_TOOL_RESPONSE", "Java 工具服务返回格式无效", False)
        if not result.get("success"):
            error = result.get("error") or {}
            if not isinstance(error, dict):
                raise ToolInvocationError(
                    "INVALID_TOOL_RESPONSE", "Java 工具服务返回格式无效", False
                )
            raise ToolInvocationError(
                str(error.get("code") or "TOOL_FAILED"),
                str(error.get("message") or "Java 工具执行失败"),
                error.get("retryable") is True,
            )
        return result.get("data")


java_tool_client = JavaToolClient()
