import re
from dataclasses import dataclass
from typing import Any, Dict, Optional


@dataclass(frozen=True)
class DirectToolPlan:
    tool: str
    arguments: Dict[str, Any]


@dataclass(frozen=True)
class StrategyDecision:
    selected: str
    reason: str
    max_tool_rounds: int


class DirectStrategy:
    name = "direct"
    max_tool_rounds = 1

    _RECOMMEND_WORDS = ("推荐", "安利", "听什么", "听啥", "来几首", "来点", "换一批", "recommend")
    _FAVORITE_WORDS = ("收藏", "favorite")
    _MOOD_WORDS = (
        "轻快", "轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "悲伤", "安静",
        "雨夜", "通勤", "学习", "睡前", "氛围", "感觉", "风格", "类似", "相似",
    )
    _DETAIL_WORDS = (
        "谁唱", "歌手", "类型", "曲风", "出处", "简介", "背景", "发行", "发布",
        "哪年", "年份", "详情", "介绍",
    )
    _SEARCH_WORDS = ("歌库", "有没有", "有哪些", "有什么", "查找", "查询", "搜索", "收录")
    _CHINESE_COUNTS = {
        "一": 1, "两": 2, "二": 2, "三": 3, "四": 4, "五": 5,
        "六": 6, "七": 7, "八": 8, "九": 9, "十": 10,
    }

    def plan(self, message: str) -> Optional[DirectToolPlan]:
        normalized = (message or "").strip().lower()
        if not normalized:
            return None
        limit = self._requested_count(normalized)
        if self._contains(normalized, self._RECOMMEND_WORDS):
            return DirectToolPlan(
                "recommend_songs",
                {"query": message, "limit": limit, "exclude_audio_ids": []},
            )
        if self._contains(normalized, self._FAVORITE_WORDS):
            return DirectToolPlan(
                "favorite_search",
                {
                    "query": self._favorite_filter(normalized),
                    "limit": self._requested_count(normalized, 10),
                },
            )
        if self._contains(normalized, self._MOOD_WORDS) and self._contains(
            normalized, self._SEARCH_WORDS + self._RECOMMEND_WORDS
        ):
            return DirectToolPlan("vector_search", {"query": message, "limit": limit})
        if self._contains(normalized, self._DETAIL_WORDS):
            return DirectToolPlan("song_detail", {"query": message})
        if self._contains(normalized, self._SEARCH_WORDS):
            return DirectToolPlan("song_search", {"query": message, "limit": limit})
        return None

    @classmethod
    def _requested_count(cls, message: str, default: int = 5) -> int:
        digit_match = re.search(r"(?<!\d)(\d{1,2})\s*首", message)
        if digit_match:
            return max(1, min(20, int(digit_match.group(1))))
        for word, value in cls._CHINESE_COUNTS.items():
            if word + "首" in message:
                return value
        return default

    @staticmethod
    def _favorite_filter(message: str) -> Optional[str]:
        result = message
        for phrase in (
            "请帮我", "帮我", "请", "查看一下", "查询一下", "查看", "查询", "我的",
            "我收藏了", "我收藏的", "收藏了哪些", "收藏了什么", "收藏列表", "收藏",
            "有哪些", "有什么", "多少首", "哪些", "什么", "歌曲", "音乐", "首歌", "歌",
        ):
            result = result.replace(phrase, "")
        result = re.sub(r"[\s，。！？?,.!：:]+", "", result)
        return result or None

    @staticmethod
    def _contains(message: str, words) -> bool:
        return any(word in message for word in words)


class ReActStrategy:
    name = "react"
    max_tool_rounds = 2


class StrategyRouter:
    _COMPLEX_WORDS = (
        "比较", "对比", "综合", "分别", "先", "然后", "再根据", "同时", "并且",
        "为什么", "分析", "结合", "既要", "又要", "换一批", "还有别的", "再来",
    )
    _RECOMMEND_WORDS = DirectStrategy._RECOMMEND_WORDS
    _FAVORITE_WORDS = DirectStrategy._FAVORITE_WORDS
    _SEMANTIC_WORDS = DirectStrategy._MOOD_WORDS
    _FACT_WORDS = DirectStrategy._DETAIL_WORDS

    def __init__(self) -> None:
        self.direct = DirectStrategy()
        self.react = ReActStrategy()

    def select(self, requested: str, message: str, tools_enabled: bool = True) -> StrategyDecision:
        normalized_request = (requested or "auto").strip().lower()
        if normalized_request not in ("auto", "direct", "react"):
            raise ValueError("Unsupported strategy: " + normalized_request)
        if not tools_enabled:
            return StrategyDecision("direct", "prepared_evidence", self.direct.max_tool_rounds)
        if normalized_request == "direct":
            return StrategyDecision("direct", "requested_direct", self.direct.max_tool_rounds)
        if normalized_request == "react":
            return StrategyDecision("react", "requested_react", self.react.max_tool_rounds)

        normalized_message = (message or "").strip().lower()
        if DirectStrategy._contains(normalized_message, self._COMPLEX_WORDS):
            return StrategyDecision("react", "complex_request", self.react.max_tool_rounds)
        intent_groups = sum((
            DirectStrategy._contains(normalized_message, self._RECOMMEND_WORDS),
            DirectStrategy._contains(normalized_message, self._FAVORITE_WORDS),
            DirectStrategy._contains(normalized_message, self._SEMANTIC_WORDS),
            DirectStrategy._contains(normalized_message, self._FACT_WORDS),
        ))
        if intent_groups >= 2:
            return StrategyDecision("react", "multiple_tool_intents", self.react.max_tool_rounds)
        return StrategyDecision("direct", "single_step_request", self.direct.max_tool_rounds)


strategy_router = StrategyRouter()
