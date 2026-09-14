import unittest
from unittest.mock import patch

from langchain_core.messages import AIMessage, HumanMessage

from app.contracts import AgentOptions
from app.graph import agent_graph, call_model, prepare_strategy
from app.strategies import DirectStrategy, StrategyRouter
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

    def test_contract_rejects_unknown_strategy(self):
        with self.assertRaises(ValueError):
            AgentOptions(strategy="tree")


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

        self.assertEqual("favorite_search", generic.tool)
        self.assertIsNone(generic.arguments["query"])
        self.assertEqual(10, generic.arguments["limit"])
        self.assertEqual("动漫", filtered.arguments["query"])

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


if __name__ == "__main__":
    unittest.main()
