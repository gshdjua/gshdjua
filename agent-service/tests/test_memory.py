import unittest
from unittest.mock import patch

from app.contracts import AgentMessage
from app.memory import ConversationMemoryRepository, MAX_RECENT_MESSAGES


class ConversationMemoryRepositoryTest(unittest.TestCase):
    def setUp(self):
        self.repository = ConversationMemoryRepository()

    def test_compresses_old_messages_and_keeps_recent_window(self):
        messages = [
            AgentMessage(role="user" if index % 2 == 0 else "assistant", content="消息" + str(index))
            for index in range(10)
        ]

        summary, recent = self.repository._compress("", messages)

        self.assertEqual(MAX_RECENT_MESSAGES, len(recent))
        self.assertIn("消息0", summary)
        self.assertEqual("消息4", recent[0].content)

    def test_merge_removes_history_already_in_stored_state(self):
        stored = [AgentMessage(role="user", content="你好")]
        incoming = [
            AgentMessage(role="user", content="你好"),
            AgentMessage(role="assistant", content="你好，有什么可以帮你？"),
        ]

        merged = self.repository._merge_messages(stored, incoming)

        self.assertEqual(2, len(merged))

    def test_load_restores_summary_and_recent_messages(self):
        cursor = FakeCursor(fetchone=(
            "用户喜欢动漫歌曲",
            '[{"role":"user","content":"继续推荐"}]',
        ))
        with patch.object(self.repository, "_ensure_schema"), \
                patch.object(self.repository, "_connect", return_value=FakeConnection(cursor)), \
                patch.object(self.repository, "_load_long_term", return_value=[]):
            memory = self.repository.load("conversation-1", "user-1")

        self.assertEqual("用户喜欢动漫歌曲", memory.summary)
        self.assertEqual(1, len(memory.recent_messages))
        self.assertEqual("继续推荐", memory.recent_messages[0].content)
        self.assertEqual(("conversation-1", "user-1", "user-1"), cursor.parameters)

    def test_save_upserts_state_with_user_scope(self):
        cursor = FakeCursor()
        incoming = [AgentMessage(role="user", content="我想听动漫歌曲")]
        with patch.object(self.repository, "_ensure_schema"), \
                patch.object(self.repository, "_connect", return_value=FakeConnection(cursor)), \
                patch.object(self.repository, "_save_preferences"):
            self.repository.save(
                "conversation-1",
                "user-1",
                "",
                [],
                incoming,
                "继续推荐",
                "可以试试这首歌",
            )

        self.assertIn("ON DUPLICATE KEY UPDATE", cursor.query)
        self.assertEqual("conversation-1", cursor.parameters[0])
        self.assertEqual("user-1", cursor.parameters[1])
        restored = self.repository._decode_messages(cursor.parameters[3])
        self.assertEqual("可以试试这首歌", restored[-1].content)

    def test_extracts_stable_preferences_and_avoidances(self):
        candidates = self.repository.extract_candidates("我喜欢动漫歌曲，但我不喜欢重金属")

        self.assertEqual(["preference", "avoidance"], [item.memory_type for item in candidates])
        self.assertEqual("喜欢：动漫歌曲", candidates[0].content)
        self.assertEqual("不喜欢：重金属", candidates[1].content)

    def test_does_not_extract_temporary_or_sensitive_preferences(self):
        self.assertEqual([], self.repository.extract_candidates("我今天喜欢听摇滚"))
        self.assertEqual([], self.repository.extract_candidates("我喜欢的密码是 123456"))

    def test_semantic_ranking_filters_irrelevant_memories(self):
        rows = [
            {"content": "喜欢：动漫歌曲", "embedding": "[1.0, 0.0]", "importance": 0.8, "confidence": 0.9},
            {"content": "喜欢：古典音乐", "embedding": "[0.0, 1.0]", "importance": 0.8, "confidence": 0.9},
        ]

        ranked = self.repository.rank_memories(rows, [0.9, 0.1])

        self.assertEqual(1, len(ranked))
        self.assertEqual("喜欢：动漫歌曲", ranked[0]["content"])


class FakeCursor:
    def __init__(self, fetchone=None):
        self._fetchone = fetchone
        self.query = ""
        self.parameters = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self, query, parameters=None):
        self.query = query
        self.parameters = parameters

    def fetchone(self):
        return self._fetchone


class FakeConnection:
    def __init__(self, cursor):
        self._cursor = cursor

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def cursor(self):
        return self._cursor


if __name__ == "__main__":
    unittest.main()
