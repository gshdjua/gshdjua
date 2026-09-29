import json
import time
from unittest.mock import patch

from langchain_core.messages import AIMessageChunk, HumanMessage

from app.main import answer_chunks, sse_event
from app.graph import (
    call_model,
    llm_provider_registry,
    reset_model_stream_sink,
    set_model_stream_sink,
)


def test_answer_chunks_reconstruct_unicode_answer():
    answer = "你好，MusicHub。" * 20
    chunks = list(answer_chunks(answer, 7))

    assert "".join(chunks) == answer
    assert all(1 <= len(chunk) <= 7 for chunk in chunks)


def test_sse_event_keeps_json_payload_inside_one_frame():
    frame = sse_event("delta", {"content": "第一行\n第二行"})
    lines = frame.rstrip("\n").split("\n")

    assert lines[0] == "event: delta"
    assert len(lines) == 2
    assert json.loads(lines[1][len("data: "):])["content"] == "第一行\n第二行"


def test_graph_forwards_real_model_chunks_and_preserves_usage():
    class FakeStreamingModel:
        def stream(self, messages):
            yield AIMessageChunk(content="流式")
            yield AIMessageChunk(
                content="回答",
                usage_metadata={"input_tokens": 3, "output_tokens": 2, "total_tokens": 5},
            )

    state = {
        "messages": [HumanMessage(content="测试")],
        "provider": "deepseek",
        "model": "fake",
        "temperature": 0.0,
        "selected_strategy": "direct",
        "tools_enabled": False,
        "model_calls": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "total_tokens": 0,
        "max_model_calls": 2,
        "max_total_tokens": 100,
        "max_execution_ms": 5000,
        "execution_started_at": time.monotonic(),
        "budget_exhausted": False,
        "budget_stop_reason": "",
        "tool_rounds": 0,
        "tool_calls": 0,
    }
    chunks = []
    token = set_model_stream_sink(chunks.append)
    try:
        with patch.object(
            llm_provider_registry, "create_chat_model", return_value=FakeStreamingModel()
        ):
            result = call_model(state)
    finally:
        reset_model_stream_sink(token)

    assert "".join(chunks) == "流式回答"
    assert result["response"].content == "流式回答"
    assert result["total_tokens"] == 5
