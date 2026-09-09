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
        restarted_repository.save("9001", "101", "", [], [], "我喜欢爵士", "好的")
        self.assertEqual(1, len(restarted_repository.list_memories("101")))

        restarted_repository.set_enabled("101", True)
        memory_id = memories[0]["id"]
        updated = restarted_repository.update_memory("101", memory_id, "喜欢：热血动漫歌曲")
        self.assertEqual("喜欢：热血动漫歌曲", updated["content"])
        self.assertTrue(restarted_repository.delete_memory("101", memory_id))
        self.assertEqual([], restarted_repository.list_memories("101"))


if __name__ == "__main__":
    unittest.main()
