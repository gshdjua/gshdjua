import hashlib
import json
import logging
import math
import re
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
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
EXPIRING_MEMORY_DAYS = 7
MEMORY_PATTERNS = (
    ("preference", re.compile(r"(?:我喜欢|我偏好|我爱听)([^。！？\n，,；;]{1,80})")),
    ("avoidance", re.compile(r"(?:我不喜欢|我不爱听|不要推荐|别推荐)([^。！？\n，,；;]{1,80})")),
)
EXPIRING_MEMORY_PATTERNS = (
    ("preference", re.compile(r"(?:这周|本周|接下来(?:一|1)周|未来(?:一|1)周)(?:我)?(?:想听|喜欢听|请(?:多)?推荐)([^。！？\n，,；;]{1,80})")),
    ("avoidance", re.compile(r"(?:这周|本周|接下来(?:一|1)周|未来(?:一|1)周)(?:我)?(?:不想听|不要推荐|别推荐)([^。！？\n，,；;]{1,80})")),
)
REQUEST_TAIL_PATTERNS = (
    re.compile(
        r"(?:请(?:你)?|麻烦(?:你)?|你(?:能不能|能否|可不可以|可以|能)?|能不能|能否|可不可以|可以|能|可|帮我|给我|我?想)"
        r"(?:(?:不要|别|再|也|先|多|继续|就))*?(?:帮我|给我)?(?:再|多|继续)?"
        r"(?:推荐|介绍|找|播放|来|列出|听)"
    ),
    re.compile(r"(?:再|多|继续)?(?:推荐|介绍|找|播放|来|列出)(?:给我)?(?:几|一|些)"),
)
UNCERTAIN_TOPIC_PATTERN = re.compile(r"(?:什么|哪些|哪(?:一)?种|哪类|是否|是不是|还是)")
QUESTION_ENDINGS = ("吗", "么", "嘛", "呢")
MAX_EXTRACTED_TOPIC_CHARS = 60
TOPIC_ALIASES = {
    "摇滚乐": "摇滚",
    "rock": "摇滚",
    "rock音乐": "摇滚",
    "动漫音乐": "动漫歌曲",
    "二次元音乐": "动漫歌曲",
    "二次元歌曲": "动漫歌曲",
    "动画歌曲": "动漫歌曲",
    "动漫类型的歌曲": "动漫歌曲",
    "动漫类型歌曲": "动漫歌曲",
    "古典乐": "古典音乐",
    "classical": "古典音乐",
    "电子乐": "电子音乐",
    "electronic": "电子音乐",
    "爵士乐": "爵士",
    "jazz": "爵士",
    "嘻哈音乐": "嘻哈",
    "hiphop": "嘻哈",
    "hip-hop": "嘻哈",
}


@dataclass
class MemoryCandidate:
    memory_type: str
    content: str
    normalized_content: str
    importance: float = 0.8
    confidence: float = 0.9
    topic: str = ""
    topic_key: str = ""
    expires_at: Optional[datetime] = None


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
            self._expire_memories(cursor, user_id)
            cursor.execute(
                "SELECT id,memory_type,content,importance,confidence,source_conversation_id,created_at,updated_at,"
                "topic,status,expires_at,last_accessed_at,access_count,superseded_by "
                "FROM agent_long_term_memory WHERE user_id=%s "
                "ORDER BY FIELD(status,'active','expired','superseded'),updated_at DESC,id DESC",
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
                "SELECT memory_type FROM agent_long_term_memory WHERE id=%s AND user_id=%s",
                (memory_id, user_id),
            )
            row = cursor.fetchone()
            if not row:
                return None
            memory_type, topic, topic_key = self._parse_memory_content(content, str(row[0]))
            cursor.execute(
                "SELECT id FROM agent_long_term_memory WHERE user_id=%s AND memory_key=%s AND id<>%s LIMIT 1",
                (user_id, memory_key, memory_id),
            )
            if cursor.fetchone():
                raise ValueError("相同的长期记忆已经存在")
            cursor.execute(
                "UPDATE agent_long_term_memory SET memory_key=%s,content=%s,normalized_content=%s,embedding=%s,"
                "embedding_model=%s,memory_type=%s,topic=%s,topic_key=%s,status='active',origin='manual',"
                "expires_at=NULL,superseded_by=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=%s AND user_id=%s",
                (memory_key, content.strip(), normalized, self._encode_vector(vector), model, memory_type,
                 topic, topic_key, memory_id, user_id),
            )
            if cursor.rowcount < 1:
                return None
            self._supersede_topic_competitors(cursor, user_id, memory_id, topic_key)
        return next((item for item in self.list_memories(user_id) if item["id"] == memory_id), None)

    def reactivate_memory(self, user_id: str, memory_id: int) -> Optional[Dict]:
        self._ensure_schema()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT topic_key FROM agent_long_term_memory WHERE id=%s AND user_id=%s",
                (memory_id, user_id),
            )
            row = cursor.fetchone()
            if not row:
                return None
            cursor.execute(
                "UPDATE agent_long_term_memory SET status='active',expires_at=NULL,superseded_by=NULL,"
                "origin='manual',updated_at=CURRENT_TIMESTAMP WHERE id=%s AND user_id=%s",
                (memory_id, user_id),
            )
            self._supersede_topic_competitors(cursor, user_id, memory_id, str(row[0]))
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
        if not text or self._contains_sensitive(text):
            return []
        candidates: List[MemoryCandidate] = []
        seen = set()
        expiring_until = datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(days=EXPIRING_MEMORY_DAYS)
        self._append_candidates(candidates, seen, text, EXPIRING_MEMORY_PATTERNS, expiring_until)
        if candidates:
            return candidates
        if any(signal in text for signal in TEMPORARY_SIGNALS):
            return candidates
        self._append_candidates(candidates, seen, text, MEMORY_PATTERNS, None)
        return candidates

    def _append_candidates(self, candidates, seen, text, patterns, expires_at) -> None:
        for memory_type, pattern in patterns:
            for match in pattern.finditer(text):
                raw_value = re.split(r"但|不过|然而", match.group(1), maxsplit=1)[0].strip(" ，,。！？!?；;")
                cleaned_value = self._clean_preference_value(raw_value)
                if not cleaned_value:
                    continue
                for value in self._split_topics(cleaned_value):
                    prefix = "喜欢" if memory_type == "preference" else "不喜欢"
                    content = prefix + "：" + value
                    normalized = self._normalize_content(content)
                    if len(value) < 1 or normalized in seen:
                        continue
                    seen.add(normalized)
                    topic = self._normalize_topic(value)
                    candidates.append(MemoryCandidate(
                        memory_type, content, normalized, topic=value.strip(),
                        topic_key=self._topic_key(topic), expires_at=expires_at,
                    ))

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
                self._expire_memories(cursor, user_id)
                cursor.execute(
                    "SELECT id,memory_type,content,importance,confidence,embedding,updated_at "
                    "FROM agent_long_term_memory WHERE user_id=%s AND status='active' "
                    "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) "
                    "ORDER BY updated_at DESC LIMIT %s",
                    (user_id, MAX_MEMORY_CANDIDATES),
                )
                rows = list(cursor.fetchall())
            self._backfill_missing_embeddings(user_id, rows)
            query_vector, _ = embedding_client.embed(query, "query") if query.strip() else (None, "")
            selected = self.rank_memories(rows, query_vector)
            self._record_memory_access(user_id, [int(item["id"]) for item in selected])
            return [str(item["content"]) for item in selected]
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
        self._expire_memories(cursor, user_id)
        for candidate in candidates:
            vector, model = embedding_client.embed(candidate.content, "passage")
            memory_key = hashlib.sha256(candidate.normalized_content.encode("utf-8")).hexdigest()
            cursor.execute(
                "INSERT INTO agent_long_term_memory"
                "(user_id,memory_key,memory_type,content,normalized_content,importance,confidence,embedding,"
                "embedding_model,source_conversation_id,status,topic,topic_key,expires_at,origin) "
                "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'active',%s,%s,%s,'automatic') "
                "ON DUPLICATE KEY UPDATE content=VALUES(content),importance=VALUES(importance),"
                "confidence=LEAST(1.0,confidence+0.05),embedding=VALUES(embedding),embedding_model=VALUES(embedding_model),"
                "source_conversation_id=VALUES(source_conversation_id),status='active',topic=VALUES(topic),"
                "topic_key=VALUES(topic_key),expires_at=VALUES(expires_at),superseded_by=NULL,"
                "id=LAST_INSERT_ID(id),updated_at=CURRENT_TIMESTAMP",
                (
                    user_id, memory_key, candidate.memory_type, candidate.content,
                    candidate.normalized_content, candidate.importance, candidate.confidence,
                    self._encode_vector(vector), model, conversation_id, candidate.topic,
                    candidate.topic_key, candidate.expires_at,
                ),
            )
            self._supersede_topic_competitors(cursor, user_id, int(cursor.lastrowid), candidate.topic_key)
        return len(candidates)

    @staticmethod
    def _expire_memories(cursor, user_id: str) -> None:
        cursor.execute(
            "UPDATE agent_long_term_memory SET status='expired',updated_at=CURRENT_TIMESTAMP "
            "WHERE user_id=%s AND status='active' AND expires_at IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP",
            (user_id,),
        )

    @staticmethod
    def _supersede_topic_competitors(cursor, user_id: str, memory_id: int, topic_key: str) -> None:
        cursor.execute(
            "UPDATE agent_long_term_memory SET status='superseded',superseded_by=%s,updated_at=CURRENT_TIMESTAMP "
            "WHERE user_id=%s AND topic_key=%s AND status='active' AND id<>%s",
            (memory_id, user_id, topic_key, memory_id),
        )

    def _record_memory_access(self, user_id: str, memory_ids: List[int]) -> None:
        if not memory_ids:
            return
        placeholders = ",".join(["%s"] * len(memory_ids))
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "UPDATE agent_long_term_memory SET access_count=access_count+1,last_accessed_at=CURRENT_TIMESTAMP "
                "WHERE user_id=%s AND status='active' AND id IN (" + placeholders + ")",
                (user_id, *memory_ids),
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
    def _normalize_topic(value: str) -> str:
        normalized = re.sub(r"[\s，,。！？!?；;：:]", "", (value or "").strip().lower())
        return TOPIC_ALIASES.get(normalized, normalized)

    @staticmethod
    def _split_topics(value: str) -> List[str]:
        parts = re.split(r"\s*(?:、|以及|还有|和|与)\s*", value or "")
        return [part.strip(" ，,。！？!?；;") for part in parts if part.strip(" ，,。！？!?；;")]

    @staticmethod
    def _clean_preference_value(value: str) -> str:
        cleaned = (value or "").strip(" ，,。！？!?；;")
        boundaries = [match.start() for pattern in REQUEST_TAIL_PATTERNS if (match := pattern.search(cleaned))]
        if boundaries:
            cleaned = cleaned[:min(boundaries)].strip(" ，,。！？!?；;的")
        if not cleaned or len(cleaned) > MAX_EXTRACTED_TOPIC_CHARS:
            return ""
        if UNCERTAIN_TOPIC_PATTERN.search(cleaned) or cleaned.endswith(QUESTION_ENDINGS):
            return ""
        return cleaned

    @staticmethod
    def _topic_key(normalized_topic: str) -> str:
        return hashlib.sha256(normalized_topic.encode("utf-8")).hexdigest()

    def _parse_memory_content(self, content: str, fallback_type: str) -> tuple[str, str, str]:
        match = re.match(r"^\s*(喜欢|不喜欢)\s*[：:]\s*(.+?)\s*$", content)
        if match:
            memory_type = "preference" if match.group(1) == "喜欢" else "avoidance"
            topic = match.group(2).strip()
        else:
            memory_type = fallback_type
            topic = content.strip()
        return memory_type, topic, self._topic_key(self._normalize_topic(topic))

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
            "topic": str(row[8] or ""),
            "status": str(row[9]),
            "expiresAt": row[10],
            "lastAccessedAt": row[11],
            "accessCount": int(row[12] or 0),
            "supersededBy": int(row[13]) if row[13] is not None else None,
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
                    "topic VARCHAR(200) NOT NULL DEFAULT '',topic_key CHAR(64) NOT NULL DEFAULT '',"
                    "expires_at TIMESTAMP NULL,last_accessed_at TIMESTAMP NULL,access_count BIGINT NOT NULL DEFAULT 0,"
                    "superseded_by BIGINT NULL,origin VARCHAR(20) NOT NULL DEFAULT 'automatic',"
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    "UNIQUE KEY uk_agent_memory_user_key(user_id,memory_key),"
                    "KEY idx_agent_memory_user_update(user_id,updated_at),"
                    "KEY idx_agent_memory_topic_status(user_id,topic_key,status)) "
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
                self._ensure_indexes(cursor)
                self._backfill_governance_metadata(cursor)
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
            "topic": "VARCHAR(200) NOT NULL DEFAULT ''",
            "topic_key": "CHAR(64) NOT NULL DEFAULT ''",
            "expires_at": "TIMESTAMP NULL",
            "last_accessed_at": "TIMESTAMP NULL",
            "access_count": "BIGINT NOT NULL DEFAULT 0",
            "superseded_by": "BIGINT NULL",
            "origin": "VARCHAR(20) NOT NULL DEFAULT 'automatic'",
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

    @staticmethod
    def _ensure_indexes(cursor) -> None:
        cursor.execute(
            "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
            "AND TABLE_NAME='agent_long_term_memory' AND INDEX_NAME='idx_agent_memory_topic_status'"
        )
        row = cursor.fetchone()
        if not row or int(row[0]) == 0:
            cursor.execute(
                "ALTER TABLE agent_long_term_memory ADD KEY idx_agent_memory_topic_status(user_id,topic_key,status)"
            )

    def _backfill_governance_metadata(self, cursor) -> None:
        cursor.execute(
            "SELECT id,user_id,memory_type,content,status,updated_at FROM agent_long_term_memory"
        )
        rows = cursor.fetchall()
        updates = []
        active_groups = {}
        for memory_id, user_id, memory_type, content, status, updated_at in rows:
            parsed_type, topic, topic_key = self._parse_memory_content(str(content), str(memory_type))
            updates.append((parsed_type, topic, topic_key, memory_id))
            if status == "active":
                active_groups.setdefault((user_id, topic_key), []).append((memory_id, updated_at))
        if updates:
            cursor.executemany(
                "UPDATE agent_long_term_memory SET memory_type=%s,topic=%s,topic_key=%s WHERE id=%s",
                updates,
            )
        supersessions = []
        for items in active_groups.values():
            ordered = sorted(items, key=lambda item: (item[1] or datetime.min, item[0]), reverse=True)
            winner_id = ordered[0][0]
            supersessions.extend((winner_id, memory_id) for memory_id, _ in ordered[1:])
        if supersessions:
            cursor.executemany(
                "UPDATE agent_long_term_memory SET status='superseded',superseded_by=%s WHERE id=%s",
                supersessions,
            )


memory_repository = ConversationMemoryRepository()
