import json
from typing import Any, Dict
from urllib.request import Request, urlopen

from ..config import java_tool_base_url, tool_api_key, tool_http_timeout_seconds
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
        with urlopen(request, timeout=timeout_seconds or tool_http_timeout_seconds()) as response:
            result = json.loads(response.read().decode("utf-8"))
        if not result.get("success"):
            error = result.get("error") or {}
            raise RuntimeError(str(error.get("message") or "Java tool execution failed"))
        return result.get("data")


java_tool_client = JavaToolClient()
