import os
import unittest
import uuid
from unittest.mock import patch

import pymysql

from app.config import mysql_config
from app.memory import ConversationMemoryRepository


@unittest.skipUnless(os.getenv("AGENT_MEMORY_INTEGRATION") == "1", "integration test disabled")
class ConversationMemoryIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.database = "musichub_memory_it_" + uuid.uuid4().hex
        cls.config = mysql_config()
        server_config = dict(cls.config)
        server_config.pop("database", None)
        with pymysql.connect(**server_config) as connection, connection.cursor() as cursor:
            cursor.execute("CREATE DATABASE `" + cls.database + "` CHARACTER SET utf8mb4")
        cls.config["database"] = cls.database
        with pymysql.connect(**cls.config) as connection, connection.cursor() as cursor:
            cursor.execute(
                "CREATE TABLE agent_long_term_memory ("
                "id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id INT NOT NULL,memory_key CHAR(64) NOT NULL,"
                "memory_type VARCHAR(30) NOT NULL DEFAULT 'preference',content VARCHAR(500) NOT NULL,"
                "normalized_content VARCHAR(500) NOT NULL DEFAULT '',importance DOUBLE NOT NULL DEFAULT 0.8,"
                "confidence DOUBLE NOT NULL DEFAULT 0.9,embedding JSON NULL,embedding_model VARCHAR(255) NULL,"
                "source_conversation_id BIGINT NULL,status VARCHAR(20) NOT NULL DEFAULT 'active',"
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                "UNIQUE KEY uk_agent_memory_user_key(user_id,memory_key),"
                "KEY idx_agent_memory_user_update(user_id,updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )

    @classmethod
    def tearDownClass(cls):
        if not cls.database.startswith("musichub_memory_it_"):
            return
        server_config = dict(cls.config)
        server_config.pop("database", None)
        with pymysql.connect(**server_config) as connection, connection.cursor() as cursor:
            cursor.execute("DROP DATABASE `" + cls.database + "`")

    def setUp(self):
        self.config_patch = patch("app.memory.mysql_config", return_value=self.config)
        self.config_patch.start()
        self.addCleanup(self.config_patch.stop)
        self.repository = ConversationMemoryRepository()

    def test_restart_recovery_semantic_recall_and_governance(self):
        self.repository.save(
            "9001", "101", "", [], [], "我喜欢动漫歌曲", "我记住了你的偏好。"
        )
        captured = self.repository.capture_preferences(
            "101", "9001", "assistant-message-1", "我喜欢动漫歌曲"
        )
        duplicate = self.repository.capture_preferences(
            "101", "9001", "assistant-message-1", "我喜欢动漫歌曲"
        )

        self.assertEqual(1, captured["capturedCount"])
        self.assertFalse(captured["duplicate"])
        self.assertTrue(duplicate["duplicate"])

        memories = self.repository.list_memories("101")
        self.assertEqual(1, len(memories))
        self.assertEqual("喜欢：动漫歌曲", memories[0]["content"])

        restarted_repository = ConversationMemoryRepository()
        restored = restarted_repository.load("9001", "101", "请推荐一些动漫音乐")
        self.assertEqual(2, len(restored.recent_messages))
        self.assertEqual(["喜欢：动漫歌曲"], restored.long_term_memories)

        restarted_repository.set_enabled("101", False)
        disabled = restarted_repository.load("9001", "101", "继续推荐动漫音乐")
        self.assertEqual([], disabled.long_term_memories)
        disabled_capture = restarted_repository.capture_preferences(
            "101", "9001", "assistant-message-2", "我喜欢爵士"
        )
        self.assertFalse(disabled_capture["enabled"])
        self.assertEqual(1, len(restarted_repository.list_memories("101")))

        restarted_repository.set_enabled("101", True)
        temporary_capture = restarted_repository.capture_preferences(
            "101", "9001", "assistant-message-3", "我今天喜欢听摇滚"
        )
        self.assertEqual(0, temporary_capture["capturedCount"])
        self.assertEqual(1, len(restarted_repository.list_memories("101")))

        memory_id = memories[0]["id"]
        updated = restarted_repository.update_memory("101", memory_id, "喜欢：热血动漫歌曲")
        self.assertEqual("喜欢：热血动漫歌曲", updated["content"])
        self.assertTrue(restarted_repository.delete_memory("101", memory_id))
        self.assertEqual([], restarted_repository.list_memories("101"))

    def test_exact_conflict_supersedes_old_memory_and_recall_tracks_access(self):
        self.repository.capture_preferences("201", "9101", "request-1", "我喜欢摇滚")
        self.repository.capture_preferences("201", "9101", "request-2", "我不喜欢摇滚")

        memories = self.repository.list_memories("201")
        by_content = {item["content"]: item for item in memories}
        self.assertEqual("superseded", by_content["喜欢：摇滚"]["status"])
        self.assertEqual("active", by_content["不喜欢：摇滚"]["status"])
        self.assertEqual(by_content["不喜欢：摇滚"]["id"], by_content["喜欢：摇滚"]["supersededBy"])

        recalled = self.repository.load(None, "201", "推荐摇滚音乐").long_term_memories
        self.assertEqual(["不喜欢：摇滚"], recalled)
        refreshed = {item["content"]: item for item in self.repository.list_memories("201")}
        self.assertEqual(1, refreshed["不喜欢：摇滚"]["accessCount"])
        self.assertIsNotNone(refreshed["不喜欢：摇滚"]["lastAccessedAt"])

        reactivated = self.repository.reactivate_memory("201", by_content["喜欢：摇滚"]["id"])
        self.assertEqual("active", reactivated["status"])
        after_reactivation = {item["content"]: item for item in self.repository.list_memories("201")}
        self.assertEqual("superseded", after_reactivation["不喜欢：摇滚"]["status"])

    def test_alias_and_compound_topics_are_governed(self):
        captured = self.repository.capture_preferences(
            "204", "9104", "request-1", "我喜欢摇滚、爵士和电子乐"
        )
        self.assertEqual(3, captured["capturedCount"])
        self.repository.capture_preferences("204", "9104", "request-2", "我不喜欢摇滚乐")

        memories = self.repository.list_memories("204")
        by_content = {item["content"]: item for item in memories}
        self.assertEqual("superseded", by_content["喜欢：摇滚"]["status"])
        self.assertEqual("active", by_content["不喜欢：摇滚乐"]["status"])
        self.assertEqual("active", by_content["喜欢：爵士"]["status"])
        self.assertEqual("active", by_content["喜欢：电子乐"]["status"])

    def test_request_tail_is_not_saved_as_part_of_preference(self):
        captured = self.repository.capture_preferences(
            "205", "9105", "request-1", "我喜欢动漫类型的歌曲你能给我推荐几首吗"
        )

        self.assertEqual(1, captured["capturedCount"])
        memories = self.repository.list_memories("205")
        self.assertEqual(1, len(memories))
        self.assertEqual("喜欢：动漫类型的歌曲", memories[0]["content"])
        self.assertEqual("动漫类型的歌曲", memories[0]["topic"])

    def test_specific_exception_does_not_replace_broader_preference(self):
        self.repository.capture_preferences("202", "9102", "request-1", "我喜欢摇滚")
        self.repository.capture_preferences("202", "9102", "request-2", "我不喜欢日系摇滚")

        active = [item["content"] for item in self.repository.list_memories("202") if item["status"] == "active"]
        self.assertCountEqual(["喜欢：摇滚", "不喜欢：日系摇滚"], active)

    def test_expired_memory_is_visible_but_not_recalled(self):
        captured = self.repository.capture_preferences("203", "9103", "request-1", "本周我想听轻快的歌曲")
        self.assertEqual(1, captured["capturedCount"])

        with pymysql.connect(**self.config) as connection, connection.cursor() as cursor:
            cursor.execute(
                "UPDATE agent_long_term_memory SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) "
                "WHERE user_id=%s",
                ("203",),
            )

        self.assertEqual([], self.repository.load(None, "203", "推荐轻快歌曲").long_term_memories)
        memories = self.repository.list_memories("203")
        self.assertEqual(1, len(memories))
        self.assertEqual("expired", memories[0]["status"])


if __name__ == "__main__":
    unittest.main()
