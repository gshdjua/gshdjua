import unittest
from unittest.mock import patch

from langchain_core.messages import AIMessage, HumanMessage

from app.contracts import AgentOptions
from app.graph import agent_graph, call_model, execute_tools, prepare_strategy
from app.strategies import DirectStrategy, StrategyRouter
from app.main import strategy_preview
from app.contracts import StrategyPreviewRequest
from app.tools.models import ToolExecutionResult


class StrategyRouterTest(unittest.TestCase):
    def setUp(self):
        self.router = StrategyRouter()

    def test_auto_routes_single_step_requests_to_direct(self):
        favorite = self.router.select("auto", "我收藏了什么？")
        recommendation = self.router.select("auto", "推荐三首动漫歌曲")

        self.assertEqual("direct", favorite.selected)
        self.assertEqual("direct", recommendation.selected)
        self.assertEqual(1, favorite.max_tool_rounds)

    def test_auto_routes_multi_intent_and_complex_requests_to_react(self):
        multi_intent = self.router.select("auto", "根据我的收藏推荐适合雨夜听的歌曲")
        comparison = self.router.select("auto", "比较这两首歌的类型和出处")
        follow_up = self.router.select("auto", "换一批")

        self.assertEqual("react", multi_intent.selected)
        self.assertEqual("multiple_tool_intents", multi_intent.reason)
        self.assertEqual("react", comparison.selected)
        self.assertEqual(2, comparison.max_tool_rounds)
        self.assertEqual("react", follow_up.selected)

    def test_explicit_strategy_is_respected_but_prepared_evidence_stays_direct(self):
        self.assertEqual("direct", self.router.select("direct", "复杂问题").selected)
        self.assertEqual("react", self.router.select("react", "简单问题").selected)
        prepared = self.router.select("react", "复杂问题", tools_enabled=False)

        self.assertEqual("direct", prepared.selected)
        self.assertEqual("prepared_evidence", prepared.reason)

    def test_low_budget_downgrades_react_and_high_budget_expands_limits(self):
        low = self.router.select("react", "比较这些歌曲", cost_budget="low")
        high = self.router.select("react", "比较这些歌曲", cost_budget="high")

        self.assertEqual("direct", low.selected)
        self.assertEqual("budget_limited", low.reason)
        self.assertEqual(1, low.budget.max_model_calls)
        self.assertEqual("react", high.selected)
        self.assertEqual(3, high.budget.max_tool_rounds)
        self.assertEqual(6, high.budget.max_tool_calls)

    def test_contract_rejects_unknown_cost_budget(self):
        with self.assertRaises(ValueError):
            AgentOptions(costBudget="unlimited")

    def test_contract_rejects_unknown_strategy(self):
        with self.assertRaises(ValueError):
            AgentOptions(strategy="tree")

    def test_strategy_preview_is_side_effect_free_and_exposes_direct_plan(self):
        preview = strategy_preview(StrategyPreviewRequest(message="我收藏了哪些动漫歌曲？"))

        self.assertEqual("direct", preview.selectedStrategy)
        self.assertEqual("favorite_search", preview.plannedTool)
        self.assertEqual(1, preview.maxToolCalls)

    def test_strategy_preview_leaves_react_tool_choice_to_model(self):
        preview = strategy_preview(StrategyPreviewRequest(
            message="结合我的收藏推荐适合雨夜听的歌曲"
        ))

        self.assertEqual("react", preview.selectedStrategy)
        self.assertEqual("", preview.plannedTool)


class DirectStrategyTest(unittest.TestCase):
    def setUp(self):
        self.strategy = DirectStrategy()

    def test_direct_plans_recommendation_without_model_tool_selection(self):
        plan = self.strategy.plan("请推荐3首动漫歌曲")

        self.assertEqual("recommend_songs", plan.tool)
        self.assertEqual(3, plan.arguments["limit"])
        self.assertEqual([], plan.arguments["exclude_audio_ids"])

    def test_direct_plans_generic_and_filtered_favorite_queries(self):
        generic = self.strategy.plan("我收藏了什么？")
        filtered = self.strategy.plan("我收藏了哪些动漫歌曲？")
        counted = self.strategy.plan("我的收藏里动漫类型的歌曲有几首")

        self.assertEqual("favorite_search", generic.tool)
        self.assertIsNone(generic.arguments["query"])
        self.assertEqual(10, generic.arguments["limit"])
        self.assertEqual("动漫", filtered.arguments["query"])
        self.assertEqual("favorite_search", counted.tool)
        self.assertEqual("动漫", counted.arguments["query"])

    def test_direct_uses_vector_for_single_mood_search(self):
        plan = self.strategy.plan("歌库里有没有适合雨夜听的歌曲")

        self.assertEqual("vector_search", plan.tool)


class StrategyGraphTest(unittest.TestCase):
    def test_prepare_strategy_builds_direct_tool_call(self):
        update = prepare_strategy({
            "strategy": "auto",
            "user_message": "我收藏了什么？",
            "tools_enabled": True,
            "request_id": "request-1",
            "trace_id": "trace-1",
        })

        self.assertEqual("direct", update["selected_strategy"])
        self.assertEqual("favorite_search", update["response"].tool_calls[0]["name"])

    def test_only_react_binds_model_tools(self):
        class FakeModel:
            def __init__(self):
                self.bound = False

            def bind_tools(self, schemas):
                self.bound = True
                return self

            def invoke(self, messages):
                return AIMessage(content="answer")

        base_state = {
            "provider": "deepseek",
            "model": "deepseek-chat",
            "temperature": 0.4,
            "tools_enabled": True,
            "tool_rounds": 0,
            "max_tool_rounds": 2,
            "messages": [HumanMessage(content="test")],
        }
        direct_model = FakeModel()
        react_model = FakeModel()

        with patch("app.graph.create_deepseek_model", return_value=direct_model):
            call_model({**base_state, "selected_strategy": "direct"})
        with patch("app.graph.create_deepseek_model", return_value=react_model):
            call_model({**base_state, "selected_strategy": "react"})

        self.assertFalse(direct_model.bound)
        self.assertTrue(react_model.bound)

    def test_direct_graph_executes_planned_tool_then_generates_answer(self):
        class FinalModel:
            def bind_tools(self, schemas):
                raise AssertionError("DirectStrategy must not ask the model to select a tool")

            def invoke(self, messages):
                return AIMessage(content="你收藏了 1 首动漫歌曲。")

        tool_result = ToolExecutionResult(
            requestId="request-1",
            traceId="trace-1",
            tool="favorite_search",
            success=True,
            readOnly=True,
            data={"count": 1, "items": [{"songName": "测试歌曲"}]},
        )
        state = {
            "messages": [HumanMessage(content="我收藏了什么？")],
            "provider": "deepseek",
            "model": "deepseek-chat",
            "temperature": 0.4,
            "strategy": "auto",
            "request_id": "request-1",
            "trace_id": "trace-1",
            "user_id": "7",
            "tool_rounds": 0,
            "tools_enabled": True,
            "user_message": "我收藏了什么？",
        }

        with patch("app.graph.create_deepseek_model", return_value=FinalModel()):
            with patch("app.graph.tool_registry.invoke", return_value=tool_result) as invoke:
                result = agent_graph.invoke(state)

        self.assertEqual("direct", result["selected_strategy"])
        self.assertEqual("你收藏了 1 首动漫歌曲。", result["response"].content)
        self.assertEqual(1, result["tool_rounds"])
        invoke.assert_called_once()

    def test_react_graph_lets_model_observe_tool_result(self):
        class ReactModel:
            def __init__(self):
                self.calls = 0
                self.bind_count = 0

            def bind_tools(self, schemas):
                self.bind_count += 1
                return self

            def invoke(self, messages):
                self.calls += 1
                if self.calls == 1:
                    return AIMessage(content="", tool_calls=[{
                        "name": "song_search",
                        "args": {"query": "动漫", "limit": 2},
                        "id": "react-call-1",
                        "type": "tool_call",
                    }])
                return AIMessage(content="综合工具结果后的回答")

        model = ReactModel()
        tool_result = ToolExecutionResult(
            requestId="request-2",
            traceId="trace-2",
            tool="song_search",
            success=True,
            readOnly=True,
            data={"count": 2, "items": []},
        )
        state = {
            "messages": [HumanMessage(content="比较动漫歌曲并分析它们的类型")],
            "provider": "deepseek",
            "model": "deepseek-chat",
            "temperature": 0.4,
            "strategy": "auto",
            "request_id": "request-2",
            "trace_id": "trace-2",
            "user_id": "7",
            "tool_rounds": 0,
            "tools_enabled": True,
            "user_message": "比较动漫歌曲并分析它们的类型",
        }

        with patch("app.graph.create_deepseek_model", return_value=model):
            with patch("app.graph.tool_registry.invoke", return_value=tool_result):
                result = agent_graph.invoke(state)

        self.assertEqual("react", result["selected_strategy"])
        self.assertEqual("综合工具结果后的回答", result["response"].content)
        self.assertEqual(1, result["tool_rounds"])
        self.assertEqual(2, model.calls)
        self.assertEqual(2, model.bind_count)

    def test_react_graph_aggregates_usage_across_model_calls(self):
        class UsageModel:
            def __init__(self):
                self.calls = 0

            def bind_tools(self, schemas):
                return self

            def invoke(self, messages):
                self.calls += 1
                if self.calls == 1:
                    return AIMessage(
                        content="",
                        tool_calls=[{
                            "name": "song_search",
                            "args": {"query": "动漫", "limit": 2},
                            "id": "usage-call-1",
                            "type": "tool_call",
                        }],
                        usage_metadata={"input_tokens": 10, "output_tokens": 5, "total_tokens": 15},
                    )
                return AIMessage(
                    content="回答",
                    usage_metadata={"input_tokens": 20, "output_tokens": 10, "total_tokens": 30},
                )

        model = UsageModel()
        tool_result = ToolExecutionResult(
            requestId="request-3",
            traceId="trace-3",
            tool="song_search",
            success=True,
            readOnly=True,
            data={"items": []},
        )
        state = {
            "messages": [HumanMessage(content="比较并分析动漫歌曲")],
            "provider": "deepseek",
            "model": "deepseek-chat",
            "temperature": 0.4,
            "strategy": "react",
            "cost_budget": "standard",
            "request_id": "request-3",
            "trace_id": "trace-3",
            "user_id": "7",
            "tool_rounds": 0,
            "tool_calls": 0,
            "model_calls": 0,
            "input_tokens": 0,
            "output_tokens": 0,
            "total_tokens": 0,
            "tools_enabled": True,
            "user_message": "比较并分析动漫歌曲",
        }

        with patch("app.graph.create_deepseek_model", return_value=model):
            with patch("app.graph.tool_registry.invoke", return_value=tool_result):
                result = agent_graph.invoke(state)

        self.assertEqual(30, result["input_tokens"])
        self.assertEqual(15, result["output_tokens"])
        self.assertEqual(45, result["total_tokens"])
        self.assertEqual(2, result["model_calls"])

    def test_tool_call_budget_returns_result_for_every_model_call(self):
        response = AIMessage(content="", tool_calls=[
            {"name": "song_search", "args": {"query": "a"}, "id": "call-1", "type": "tool_call"},
            {"name": "song_detail", "args": {"query": "b"}, "id": "call-2", "type": "tool_call"},
        ])
        state = {
            "response": response,
            "request_id": "request-4",
            "trace_id": "trace-4",
            "user_id": "7",
            "tool_rounds": 0,
            "tool_calls": 0,
            "max_tool_calls": 1,
            "max_execution_ms": 25000,
            "execution_started_at": 0,
        }
        tool_result = ToolExecutionResult(
            requestId="request-4",
            traceId="trace-4",
            tool="song_search",
            success=True,
            readOnly=True,
            data={"items": []},
        )

        with patch("app.graph.elapsed_ms", return_value=0):
            with patch("app.graph.tool_registry.invoke", return_value=tool_result) as invoke:
                update = execute_tools(state)

        self.assertEqual(2, len(update["messages"]))
        self.assertEqual(1, update["tool_calls"])
        self.assertTrue(update["budget_exhausted"])
        self.assertEqual("tool_call_limit", update["budget_stop_reason"])
        self.assertIn("BUDGET_EXCEEDED", update["messages"][1].content)
        invoke.assert_called_once()

    def test_time_budget_stops_before_another_model_call(self):
        state = {
            "provider": "deepseek",
            "model": "deepseek-chat",
            "temperature": 0.4,
            "messages": [HumanMessage(content="test")],
            "model_calls": 1,
            "max_model_calls": 3,
            "max_execution_ms": 100,
        }

        with patch("app.graph.elapsed_ms", return_value=100):
            with patch("app.graph.create_deepseek_model") as create_model:
                update = call_model(state)

        self.assertTrue(update["budget_exhausted"])
        self.assertEqual("time_limit", update["budget_stop_reason"])
        self.assertIn("执行时间预算", update["response"].content)
        create_model.assert_not_called()

    def test_token_budget_prevents_further_tool_execution(self):
        class TokenHeavyModel:
            def bind_tools(self, schemas):
                return self

            def invoke(self, messages):
                return AIMessage(
                    content="",
                    tool_calls=[{
                        "name": "song_search",
                        "args": {"query": "动漫"},
                        "id": "token-call-1",
                        "type": "tool_call",
                    }],
                    usage_metadata={"input_tokens": 6, "output_tokens": 4, "total_tokens": 10},
                )

        state = {
            "provider": "deepseek",
            "model": "deepseek-chat",
            "temperature": 0.4,
            "messages": [HumanMessage(content="test")],
            "selected_strategy": "react",
            "tools_enabled": True,
            "tool_rounds": 0,
            "tool_calls": 0,
            "model_calls": 0,
            "max_tool_rounds": 2,
            "max_tool_calls": 4,
            "max_model_calls": 3,
            "max_total_tokens": 10,
            "max_execution_ms": 25000,
            "input_tokens": 0,
            "output_tokens": 0,
            "total_tokens": 0,
        }

        with patch("app.graph.elapsed_ms", return_value=0):
            with patch("app.graph.create_deepseek_model", return_value=TokenHeavyModel()):
                update = call_model(state)

        self.assertTrue(update["budget_exhausted"])
        self.assertEqual("token_limit", update["budget_stop_reason"])
        self.assertEqual([], update["response"].tool_calls)
        self.assertEqual(10, update["total_tokens"])


if __name__ == "__main__":
    unittest.main()
