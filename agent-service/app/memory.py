import hashlib
import json
import re
import threading
from dataclasses import dataclass
from typing import List, Optional

import pymysql

from .config import mysql_config
from .contracts import AgentMessage


MAX_RECENT_MESSAGES = 6
MAX_SUMMARY_CHARS = 2000
MAX_MEMORY_ITEMS = 8
PREFERENCE_PATTERN = re.compile(r"(?:我喜欢|我偏好|我爱听|我不喜欢|不要推荐)([^。！？\n]{1,80})")
SENSITIVE_WORDS = ("密码", "手机号", "身份证", "邮箱", "住址", "地址")


@dataclass
class MemoryContext:
    summary: str
    recent_messages: List[AgentMessage]
    long_term_memories: List[str]


class ConversationMemoryRepository:
    def __init__(self) -> None:
        self._schema_ready = False
        self._schema_lock = threading.Lock()

    def available(self) -> bool:
        try:
            self._ensure_schema()
            return True
        except Exception:
            return False

    def load(self, conversation_id: Optional[str], user_id: Optional[str]) -> MemoryContext:
        if not conversation_id:
            return MemoryContext("", [], self._load_long_term(user_id))
        try:
            self._ensure_schema()
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "SELECT summary, recent_messages FROM agent_conversation_state "
                    "WHERE conversation_id=%s AND (%s IS NULL OR user_id=%s)",
                    (conversation_id, user_id, user_id),
                )
                row = cursor.fetchone()
            summary = row[0] if row else ""
            recent = self._decode_messages(row[1] if row else "[]")
            return MemoryContext(summary or "", recent, self._load_long_term(user_id))
        except Exception:
            return MemoryContext("", [], [])

    def save(
        self,
        conversation_id: Optional[str],
        user_id: Optional[str],
        previous_summary: str,
        previous_recent: List[AgentMessage],
        incoming_history: List[AgentMessage],
        user_message: str,
        assistant_answer: str,
    ) -> None:
        if not conversation_id:
            return
        try:
            self._ensure_schema()
            merged = self._merge_messages(previous_recent, incoming_history)
            merged.extend(
                [AgentMessage(role="user", content=user_message), AgentMessage(role="assistant", content=assistant_answer)]
            )
            summary, recent = self._compress(previous_summary, merged)
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO agent_conversation_state"
                    "(conversation_id,user_id,summary,recent_messages,state_version) VALUES(%s,%s,%s,%s,1) "
                    "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),summary=VALUES(summary),"
                    "recent_messages=VALUES(recent_messages),state_version=state_version+1,updated_at=CURRENT_TIMESTAMP",
                    (conversation_id, user_id, summary, self._encode_messages(recent)),
                )
            self._save_preferences(user_id, conversation_id, user_message)
        except Exception:
            return

    def _load_long_term(self, user_id: Optional[str]) -> List[str]:
        if not user_id:
            return []
        try:
            self._ensure_schema()
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "SELECT content FROM agent_long_term_memory WHERE user_id=%s "
                    "ORDER BY updated_at DESC LIMIT %s",
                    (user_id, MAX_MEMORY_ITEMS),
                )
                return [str(row[0]) for row in cursor.fetchall()]
        except Exception:
            return []

    def _save_preferences(self, user_id: Optional[str], conversation_id: str, message: str) -> None:
        if not user_id or any(word in message for word in SENSITIVE_WORDS):
            return
        preferences = [match.group(0).strip() for match in PREFERENCE_PATTERN.finditer(message)]
        if not preferences:
            return
        with self._connect() as connection, connection.cursor() as cursor:
            for preference in preferences:
                memory_key = hashlib.sha256(preference.lower().encode("utf-8")).hexdigest()
                cursor.execute(
                    "INSERT INTO agent_long_term_memory"
                    "(user_id,memory_key,content,source_conversation_id) VALUES(%s,%s,%s,%s) "
                    "ON DUPLICATE KEY UPDATE content=VALUES(content),"
                    "source_conversation_id=VALUES(source_conversation_id),updated_at=CURRENT_TIMESTAMP",
                    (user_id, memory_key, preference, conversation_id),
                )

    def _compress(self, summary: str, messages: List[AgentMessage]):
        if len(messages) <= MAX_RECENT_MESSAGES:
            return summary, messages
        archived = messages[:-MAX_RECENT_MESSAGES]
        recent = messages[-MAX_RECENT_MESSAGES:]
        additions = []
        for item in archived:
            label = "用户" if item.role == "user" else "助手"
            content = " ".join(item.content.split())[:180]
            additions.append(label + "：" + content)
        combined = "；".join(part for part in (summary, "；".join(additions)) if part)
        return combined[-MAX_SUMMARY_CHARS:], recent

    def _merge_messages(self, stored: List[AgentMessage], incoming: List[AgentMessage]) -> List[AgentMessage]:
        result = list(stored)
        fingerprints = {(item.role, item.content) for item in result}
        for item in incoming:
            fingerprint = (item.role, item.content)
            if fingerprint not in fingerprints:
                result.append(item)
                fingerprints.add(fingerprint)
        return result

    def _encode_messages(self, messages: List[AgentMessage]) -> str:
        return json.dumps([item.model_dump() for item in messages], ensure_ascii=False)

    def _decode_messages(self, value: str) -> List[AgentMessage]:
        try:
            return [AgentMessage.model_validate(item) for item in json.loads(value or "[]")]
        except Exception:
            return []

    def _connect(self):
        return pymysql.connect(**mysql_config())

    def _ensure_schema(self) -> None:
        if self._schema_ready:
            return
        with self._schema_lock:
            if self._schema_ready:
                return
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TABLE IF NOT EXISTS agent_conversation_state ("
                    "conversation_id BIGINT PRIMARY KEY,user_id INT NULL,summary TEXT NOT NULL,"
                    "recent_messages JSON NOT NULL,state_version BIGINT NOT NULL DEFAULT 1,"
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    "KEY idx_agent_state_user_update(user_id,updated_at)) "
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                )
                cursor.execute(
                    "CREATE TABLE IF NOT EXISTS agent_long_term_memory ("
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id INT NOT NULL,memory_key CHAR(64) NOT NULL,"
                    "content VARCHAR(500) NOT NULL,source_conversation_id BIGINT NULL,"
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    "UNIQUE KEY uk_agent_memory_user_key(user_id,memory_key),"
                    "KEY idx_agent_memory_user_update(user_id,updated_at)) "
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                )
            self._schema_ready = True


memory_repository = ConversationMemoryRepository()

