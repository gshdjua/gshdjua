import hashlib
import json
import logging
import math
import re
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, List, Optional

import pymysql

from .config import mysql_config
from .contracts import AgentMessage
from .embeddings import embedding_client


LOGGER = logging.getLogger(__name__)
MAX_RECENT_MESSAGES = 6
MAX_SUMMARY_CHARS = 2000
MAX_MEMORY_ITEMS = 5
MAX_MEMORY_CANDIDATES = 100
MAX_EMBEDDING_BACKFILL = 10
SEMANTIC_SCORE_THRESHOLD = 0.45
SENSITIVE_WORDS = (
    "密码", "口令", "密钥", "token", "手机号", "电话", "身份证", "邮箱", "住址", "地址",
    "银行卡", "信用卡", "护照", "验证码",
)
TEMPORARY_SIGNALS = ("今天", "现在", "这次", "此刻", "今晚", "暂时", "刚才")
MEMORY_PATTERNS = (
    ("preference", re.compile(r"(?:我喜欢|我偏好|我爱听)([^。！？\n，,；;]{1,80})")),
    ("avoidance", re.compile(r"(?:我不喜欢|我不爱听|不要推荐|别推荐)([^。！？\n，,；;]{1,80})")),
)


@dataclass
class MemoryCandidate:
    memory_type: str
    content: str
    normalized_content: str
    importance: float = 0.8
    confidence: float = 0.9


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
            LOGGER.exception("Agent memory store is unavailable")
            return False

    def load(
        self,
        conversation_id: Optional[str],
        user_id: Optional[str],
        query: str = "",
    ) -> MemoryContext:
        if not conversation_id:
            return MemoryContext("", [], self._load_long_term(user_id, query))
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
            return MemoryContext(summary or "", recent, self._load_long_term(user_id, query))
        except Exception:
            LOGGER.exception("Failed to restore agent memory for conversation %s", conversation_id)
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
        except Exception:
            LOGGER.exception("Failed to persist agent memory for conversation %s", conversation_id)

    def capture_preferences(
        self,
        user_id: Optional[str],
        conversation_id: Optional[str],
        request_id: str,
        message: str,
    ) -> Dict:
        if not user_id:
            return {"capturedCount": 0, "duplicate": False, "enabled": False}
        self._ensure_schema()
        enabled = self.settings(user_id)["enabled"]
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "INSERT IGNORE INTO agent_memory_capture"
                "(user_id,request_id,conversation_id,enabled,captured_count) VALUES(%s,%s,%s,%s,0)",
                (user_id, request_id, conversation_id, 1 if enabled else 0),
            )
            if cursor.rowcount == 0:
                cursor.execute(
                    "SELECT enabled,captured_count FROM agent_memory_capture WHERE user_id=%s AND request_id=%s",
                    (user_id, request_id),
                )
                row = cursor.fetchone()
                return {
                    "capturedCount": int(row[1]) if row else 0,
                    "duplicate": True,
                    "enabled": bool(row[0]) if row else enabled,
                }
            if not enabled:
                return {"capturedCount": 0, "duplicate": False, "enabled": False}
            captured_count = self._save_preferences(cursor, user_id, conversation_id, message)
            cursor.execute(
                "UPDATE agent_memory_capture SET captured_count=%s WHERE user_id=%s AND request_id=%s",
                (captured_count, user_id, request_id),
            )
        return {"capturedCount": captured_count, "duplicate": False, "enabled": True}

    def settings(self, user_id: str) -> Dict[str, bool]:
        try:
            self._ensure_schema()
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute("SELECT enabled FROM agent_memory_setting WHERE user_id=%s", (user_id,))
                row = cursor.fetchone()
            return {"enabled": bool(row[0]) if row else True}
        except Exception:
            LOGGER.exception("Failed to load memory settings for user %s", user_id)
            return {"enabled": False}

    def set_enabled(self, user_id: str, enabled: bool) -> Dict[str, bool]:
        self._ensure_schema()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "INSERT INTO agent_memory_setting(user_id,enabled) VALUES(%s,%s) "
                "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),updated_at=CURRENT_TIMESTAMP",
                (user_id, 1 if enabled else 0),
            )
        return {"enabled": enabled}

    def list_memories(self, user_id: str) -> List[Dict]:
        self._ensure_schema()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT id,memory_type,content,importance,confidence,source_conversation_id,created_at,updated_at "
                "FROM agent_long_term_memory WHERE user_id=%s AND status='active' "
                "ORDER BY updated_at DESC,id DESC",
                (user_id,),
            )
            rows = cursor.fetchall()
        return [self._memory_dict(row) for row in rows]

    def update_memory(self, user_id: str, memory_id: int, content: str) -> Optional[Dict]:
        normalized = self._normalize_content(content)
        if not normalized or self._contains_sensitive(content):
            raise ValueError("Memory content is empty or contains sensitive information")
        self._ensure_schema()
        vector, model = embedding_client.embed(content, "passage")
        memory_key = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "UPDATE agent_long_term_memory SET memory_key=%s,content=%s,normalized_content=%s,embedding=%s,"
                "embedding_model=%s,updated_at=CURRENT_TIMESTAMP WHERE id=%s AND user_id=%s AND status='active'",
                (memory_key, content.strip(), normalized, self._encode_vector(vector), model, memory_id, user_id),
            )
            if cursor.rowcount < 1:
                return None
        return next((item for item in self.list_memories(user_id) if item["id"] == memory_id), None)

    def delete_memory(self, user_id: str, memory_id: int) -> bool:
        self._ensure_schema()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute("DELETE FROM agent_long_term_memory WHERE id=%s AND user_id=%s", (memory_id, user_id))
            return cursor.rowcount > 0

    def clear_memories(self, user_id: str) -> int:
        self._ensure_schema()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute("DELETE FROM agent_long_term_memory WHERE user_id=%s", (user_id,))
            return int(cursor.rowcount)

    def delete_conversation_state(self, conversation_id: str, user_id: str) -> bool:
        self._ensure_schema()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM agent_conversation_state WHERE conversation_id=%s AND user_id=%s",
                (conversation_id, user_id),
            )
            return cursor.rowcount > 0

    def extract_candidates(self, message: str) -> List[MemoryCandidate]:
        text = (message or "").strip()
        if not text or self._contains_sensitive(text) or any(signal in text for signal in TEMPORARY_SIGNALS):
            return []
        candidates: List[MemoryCandidate] = []
        seen = set()
        for memory_type, pattern in MEMORY_PATTERNS:
            for match in pattern.finditer(text):
                value = re.split(r"但|不过|然而", match.group(1), maxsplit=1)[0].strip(" ，,。！？!?；;")
                prefix = "喜欢" if memory_type == "preference" else "不喜欢"
                content = prefix + "：" + value
                normalized = self._normalize_content(content)
                if len(value) < 1 or normalized in seen:
                    continue
                seen.add(normalized)
                candidates.append(MemoryCandidate(memory_type, content, normalized))
        return candidates

    def rank_memories(self, rows: List[Dict], query_vector: Optional[List[float]]) -> List[Dict]:
        scored = []
        now = datetime.now(timezone.utc)
        for row in rows:
            similarity = self._cosine(query_vector, self._decode_vector(row.get("embedding"))) if query_vector else 0.0
            updated_at = row.get("updated_at")
            if isinstance(updated_at, datetime):
                aware = updated_at.replace(tzinfo=timezone.utc) if updated_at.tzinfo is None else updated_at
                age_days = max(0, (now - aware).days)
            else:
                age_days = 0
            freshness = math.exp(-age_days / 180.0)
            score = similarity * 0.70 + float(row.get("importance") or 0.0) * 0.15 \
                + float(row.get("confidence") or 0.0) * 0.10 + freshness * 0.05
            item = dict(row)
            item["semantic_score"] = score
            item["_updated_timestamp"] = aware.timestamp() if isinstance(updated_at, datetime) else 0.0
            if query_vector is None or similarity >= SEMANTIC_SCORE_THRESHOLD:
                scored.append(item)
        scored.sort(key=lambda item: (item["semantic_score"], item["_updated_timestamp"]), reverse=True)
        for item in scored:
            item.pop("_updated_timestamp", None)
        return scored[:MAX_MEMORY_ITEMS]

    def _load_long_term(self, user_id: Optional[str], query: str = "") -> List[str]:
        if not user_id or not self.settings(user_id)["enabled"]:
            return []
        try:
            self._ensure_schema()
            with self._connect() as connection, connection.cursor(pymysql.cursors.DictCursor) as cursor:
                cursor.execute(
                    "SELECT id,memory_type,content,importance,confidence,embedding,updated_at "
                    "FROM agent_long_term_memory WHERE user_id=%s AND status='active' "
                    "ORDER BY updated_at DESC LIMIT %s",
                    (user_id, MAX_MEMORY_CANDIDATES),
                )
                rows = list(cursor.fetchall())
            self._backfill_missing_embeddings(user_id, rows)
            query_vector, _ = embedding_client.embed(query, "query") if query.strip() else (None, "")
            return [str(item["content"]) for item in self.rank_memories(rows, query_vector)]
        except Exception:
            LOGGER.exception("Failed to recall long-term memories for user %s", user_id)
            return []

    def _backfill_missing_embeddings(self, user_id: str, rows: List[Dict]) -> None:
        missing = [row for row in rows if not row.get("embedding")][:MAX_EMBEDDING_BACKFILL]
        if not missing:
            return
        updates = []
        for row in missing:
            vector, model = embedding_client.embed(str(row.get("content") or ""), "passage")
            if vector:
                row["embedding"] = self._encode_vector(vector)
                updates.append((row["embedding"], model, row["id"], user_id))
        if not updates:
            return
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.executemany(
                "UPDATE agent_long_term_memory SET embedding=%s,embedding_model=%s "
                "WHERE id=%s AND user_id=%s AND status='active'",
                updates,
            )

    def _save_preferences(self, cursor, user_id: str, conversation_id: Optional[str], message: str) -> int:
        candidates = self.extract_candidates(message)
        if not candidates:
            return 0
        for candidate in candidates:
            vector, model = embedding_client.embed(candidate.content, "passage")
            memory_key = hashlib.sha256(candidate.normalized_content.encode("utf-8")).hexdigest()
            cursor.execute(
                "INSERT INTO agent_long_term_memory"
                "(user_id,memory_key,memory_type,content,normalized_content,importance,confidence,embedding,"
                "embedding_model,source_conversation_id,status) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'active') "
                "ON DUPLICATE KEY UPDATE content=VALUES(content),importance=VALUES(importance),"
                "confidence=VALUES(confidence),embedding=VALUES(embedding),embedding_model=VALUES(embedding_model),"
                "source_conversation_id=VALUES(source_conversation_id),status='active',updated_at=CURRENT_TIMESTAMP",
                (
                    user_id, memory_key, candidate.memory_type, candidate.content,
                    candidate.normalized_content, candidate.importance, candidate.confidence,
                    self._encode_vector(vector), model, conversation_id,
                ),
            )
        return len(candidates)

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

    @staticmethod
    def _encode_messages(messages: List[AgentMessage]) -> str:
        return json.dumps([item.model_dump() for item in messages], ensure_ascii=False)

    @staticmethod
    def _decode_messages(value: str) -> List[AgentMessage]:
        try:
            return [AgentMessage.model_validate(item) for item in json.loads(value or "[]")]
        except Exception:
            return []

    @staticmethod
    def _encode_vector(vector: Optional[List[float]]) -> Optional[str]:
        return json.dumps(vector) if vector else None

    @staticmethod
    def _decode_vector(value) -> Optional[List[float]]:
        if not value:
            return None
        try:
            decoded = json.loads(value) if isinstance(value, str) else value
            return [float(item) for item in decoded]
        except Exception:
            return None

    @staticmethod
    def _cosine(first: Optional[List[float]], second: Optional[List[float]]) -> float:
        if not first or not second or len(first) != len(second):
            return 0.0
        numerator = sum(a * b for a, b in zip(first, second))
        first_norm = math.sqrt(sum(value * value for value in first))
        second_norm = math.sqrt(sum(value * value for value in second))
        return numerator / (first_norm * second_norm) if first_norm and second_norm else 0.0

    @staticmethod
    def _normalize_content(value: str) -> str:
        return re.sub(r"[\s，,。！？!?；;：:]", "", (value or "").strip().lower())

    @staticmethod
    def _contains_sensitive(value: str) -> bool:
        normalized = (value or "").lower()
        return any(word.lower() in normalized for word in SENSITIVE_WORDS)

    @staticmethod
    def _memory_dict(row) -> Dict:
        return {
            "id": int(row[0]),
            "memoryType": str(row[1]),
            "content": str(row[2]),
            "importance": float(row[3]),
            "confidence": float(row[4]),
            "sourceConversationId": str(row[5]) if row[5] is not None else None,
            "createdAt": row[6],
            "updatedAt": row[7],
        }

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
                    "memory_type VARCHAR(30) NOT NULL DEFAULT 'preference',content VARCHAR(500) NOT NULL,"
                    "normalized_content VARCHAR(500) NOT NULL DEFAULT '',importance DOUBLE NOT NULL DEFAULT 0.8,"
                    "confidence DOUBLE NOT NULL DEFAULT 0.9,embedding JSON NULL,embedding_model VARCHAR(255) NULL,"
                    "source_conversation_id BIGINT NULL,status VARCHAR(20) NOT NULL DEFAULT 'active',"
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    "UNIQUE KEY uk_agent_memory_user_key(user_id,memory_key),"
                    "KEY idx_agent_memory_user_update(user_id,updated_at)) "
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                )
                cursor.execute(
                    "CREATE TABLE IF NOT EXISTS agent_memory_setting ("
                    "user_id INT PRIMARY KEY,enabled TINYINT(1) NOT NULL DEFAULT 1,"
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) "
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                )
                cursor.execute(
                    "CREATE TABLE IF NOT EXISTS agent_memory_capture ("
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id INT NOT NULL,request_id VARCHAR(100) NOT NULL,"
                    "conversation_id BIGINT NULL,enabled TINYINT(1) NOT NULL,captured_count INT NOT NULL DEFAULT 0,"
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    "UNIQUE KEY uk_agent_memory_capture_request(user_id,request_id),"
                    "KEY idx_agent_memory_capture_created(created_at)) "
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                )
                self._ensure_columns(cursor)
            self._schema_ready = True

    def _ensure_columns(self, cursor) -> None:
        columns = {
            "memory_type": "VARCHAR(30) NOT NULL DEFAULT 'preference'",
            "normalized_content": "VARCHAR(500) NOT NULL DEFAULT ''",
            "importance": "DOUBLE NOT NULL DEFAULT 0.8",
            "confidence": "DOUBLE NOT NULL DEFAULT 0.9",
            "embedding": "JSON NULL",
            "embedding_model": "VARCHAR(255) NULL",
            "status": "VARCHAR(20) NOT NULL DEFAULT 'active'",
        }
        for column, definition in columns.items():
            cursor.execute(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_long_term_memory' AND COLUMN_NAME=%s",
                (column,),
            )
            row = cursor.fetchone()
            if not row or int(row[0]) == 0:
                cursor.execute("ALTER TABLE agent_long_term_memory ADD COLUMN " + column + " " + definition)


memory_repository = ConversationMemoryRepository()
