import time
import unittest
from unittest.mock import patch

from langchain_core.messages import AIMessage
from pydantic import BaseModel, Field

from app.graph import execute_tools
from app.contracts import AgentMessage
from app.main import should_enable_tools
from app.tools.catalog import tool_registry
from app.tools.models import ToolContext, ToolExecutionResult
from app.tools.registry import ToolDefinition, ToolRegistry


class ExampleArgs(BaseModel):
    query: str = Field(min_length=1)


class ToolRegistryTest(unittest.TestCase):
    def setUp(self):
        self.context = ToolContext(request_id="request-1", trace_id="trace-1", user_id="7")

    def test_catalog_exposes_typed_read_only_song_search(self):
        descriptors = tool_registry.descriptors()

        self.assertEqual(
            ["song_search", "favorite_search", "recommend_songs", "vector_search", "song_detail"],
            [item.name for item in descriptors],
        )
        self.assertTrue(all(item.readOnly for item in descriptors))
        self.assertTrue(all("userId" not in item.parameters["properties"] for item in descriptors))
        self.assertIn("exclude_audio_ids", descriptors[2].parameters["properties"])
        self.assertIn("audio_ids", descriptors[3].parameters["properties"])
        self.assertIn("audio_id", descriptors[4].parameters["properties"])

    def test_invocation_validates_arguments_and_returns_standard_result(self):
        registry = ToolRegistry()
        registry.register(ToolDefinition(
            name="example",
            description="test",
            args_model=ExampleArgs,
            handler=lambda arguments, context: {"value": arguments.query, "user": context.user_id},
        ))

        success = registry.invoke("example", {"query": "动漫"}, self.context)
        invalid = registry.invoke("example", {"query": ""}, self.context)

        self.assertTrue(success.success)
        self.assertEqual({"value": "动漫", "user": "7"}, success.data)
        self.assertEqual("trace-1", success.traceId)
        self.assertFalse(invalid.success)
        self.assertEqual("INVALID_ARGUMENTS", invalid.error.code)

        unexpected = tool_registry.invoke(
            "song_search",
            {"query": "动漫", "userId": "model-supplied-user"},
            self.context,
        )
        self.assertFalse(unexpected.success)
        self.assertEqual("INVALID_ARGUMENTS", unexpected.error.code)

    def test_duplicate_and_unknown_tools_are_rejected(self):
        registry = ToolRegistry()
        definition = ToolDefinition("example", "test", ExampleArgs, lambda arguments, context: {})
        registry.register(definition)

        with self.assertRaises(ValueError):
            registry.register(definition)
        missing = registry.invoke("missing", {}, self.context)
        self.assertEqual("TOOL_NOT_FOUND", missing.error.code)

    def test_timeout_is_returned_as_retryable_error(self):
        registry = ToolRegistry()
        registry.register(ToolDefinition(
            name="slow",
            description="test",
            args_model=ExampleArgs,
            handler=lambda arguments, context: time.sleep(0.05),
            timeout_seconds=0.005,
        ))

        result = registry.invoke("slow", {"query": "动漫"}, self.context)

        self.assertFalse(result.success)
        self.assertEqual("TOOL_TIMEOUT", result.error.code)
        self.assertTrue(result.error.retryable)

    def test_graph_executes_model_tool_call_with_server_owned_context(self):
        response = AIMessage(content="", tool_calls=[{
            "name": "song_search",
            "args": {"query": "动漫", "limit": 3},
            "id": "call-1",
            "type": "tool_call",
        }])
        tool_result = ToolExecutionResult(
            requestId="request-1",
            traceId="trace-1",
            tool="song_search",
            success=True,
            readOnly=True,
            data={"items": []},
        )
        state = {
            "response": response,
            "request_id": "request-1",
            "trace_id": "trace-1",
            "user_id": "7",
            "tool_rounds": 0,
        }

        with patch("app.graph.tool_registry.invoke", return_value=tool_result) as invoke:
            update = execute_tools(state)

        self.assertEqual(1, update["tool_rounds"])
        self.assertEqual("call-1", update["messages"][0].tool_call_id)
        invoke.assert_called_once()
        context = invoke.call_args.args[2]
        self.assertEqual("7", context.user_id)

    def test_all_read_only_catalog_tools_forward_validated_arguments(self):
        calls = [
            ("favorite_search", {"query": "动漫", "limit": 3}),
            ("recommend_songs", {"query": "推荐轻快动漫歌曲", "limit": 3, "exclude_audio_ids": [1]}),
            ("vector_search", {"query": "适合雨夜", "limit": 3, "audio_ids": [2, 3]}),
            ("song_detail", {"audio_id": 2}),
        ]
        with patch("app.tools.catalog.java_tool_client.execute", return_value={"items": []}) as execute:
            for name, arguments in calls:
                result = tool_registry.invoke(name, arguments, self.context)
                self.assertTrue(result.success, name)

        self.assertEqual(4, execute.call_count)
        self.assertTrue(all(call.args[2].user_id == "7" for call in execute.call_args_list))

        invalid_detail = tool_registry.invoke("song_detail", {}, self.context)
        self.assertFalse(invalid_detail.success)
        self.assertEqual("INVALID_ARGUMENTS", invalid_detail.error.code)

    def test_prepared_java_evidence_disables_duplicate_tool_lookup(self):
        prepared = [AgentMessage(
            role="user",
            content="用户问题：推荐动漫歌曲\n\n本地歌库提供的最小歌曲元数据：\n本地歌曲证据",
        )]
        direct = [AgentMessage(role="user", content="推荐动漫歌曲")]

        self.assertFalse(should_enable_tools(prepared))
        self.assertTrue(should_enable_tools(direct))


if __name__ == "__main__":
    unittest.main()
