"""MySQL document building and local FAISS semantic search."""

import json
import threading
from datetime import date, datetime
from pathlib import Path
from typing import Dict, List, Optional

import faiss
import numpy as np
import pymysql
import torch
from sentence_transformers import SentenceTransformer

from .cache import SingleFlightTTLCache
from .config import (
    EMBEDDING_CACHE_MAX_ENTRIES,
    EMBEDDING_CACHE_TTL_SECONDS,
    MODEL_NAME,
    MODEL_PATH,
    MYSQL_CONFIG,
    SEARCH_CACHE_MAX_ENTRIES,
    SEARCH_CACHE_TTL_SECONDS,
    get_data_dir,
)


class VectorRagEngine:
    """Keeps the model, FAISS index and JSON metadata entirely on this machine."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._model: Optional[SentenceTransformer] = None
        self._index: Optional[faiss.Index] = None
        self._metadata: List[Dict] = []
        self._data_dir = get_data_dir()
        self._index_path = self._data_dir / "music.faiss"
        self._metadata_path = self._data_dir / "music_metadata.json"
        self._index_generation = 0
        self._embedding_cache = SingleFlightTTLCache(
            EMBEDDING_CACHE_TTL_SECONDS, EMBEDDING_CACHE_MAX_ENTRIES
        )
        self._search_cache = SingleFlightTTLCache(
            SEARCH_CACHE_TTL_SECONDS, SEARCH_CACHE_MAX_ENTRIES
        )
        self._load_saved_index()

    @property
    def device(self) -> str:
        return "cuda" if torch.cuda.is_available() else "cpu"

    def health(self) -> Dict:
        return {
            "status": "ready" if self._index is not None else "index_missing",
            "model": MODEL_NAME,
            "model_loaded": self._model is not None,
            "index_ready": self._index is not None,
            "document_count": len(self._metadata),
            "device": self.device,
            "cache": self.cache_stats(),
        }

    def cache_stats(self) -> Dict:
        return {
            "embedding": self._embedding_cache.stats(),
            "search": self._search_cache.stats(),
            "indexGeneration": self._index_generation,
        }

    def rebuild(self) -> Dict:
        """Fetch current audio metadata, embed documents and overwrite the local index."""

        with self._lock:
            rows = self._fetch_audio_rows()
            documents = [self._to_document(row) for row in rows]
            self._data_dir.mkdir(parents=True, exist_ok=True)

            if not documents:
                self._index = None
                self._metadata = []
                self._remove_index_files()
                self._invalidate_index_caches()
                return {"document_count": 0, "model": MODEL_NAME, "device": self.device}

            passages = [document["text"] for document in documents]
            vectors = self._encode_cached(passages, "passage")
            index = faiss.IndexFlatIP(vectors.shape[1])
            index.add(vectors)

            faiss.write_index(index, str(self._index_path))
            self._metadata_path.write_text(
                json.dumps(documents, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            self._index = index
            self._metadata = documents
            self._invalidate_index_caches()
            return {
                "document_count": len(documents),
                "model": MODEL_NAME,
                "device": self.device,
            }

    def search(self, query: str, top_k: int, audio_ids: Optional[List[int]] = None) -> List[Dict]:
        """Return semantic Top-K candidates with their local document evidence."""

        normalized_query = (query or "").strip()
        if not normalized_query:
            return []
        normalized_ids = tuple(sorted(set(audio_ids or [])))
        key = (self._index_generation, normalized_query, int(top_k), normalized_ids)
        results = self._search_cache.get_or_load(
            key, lambda: self._search_uncached(normalized_query, top_k, list(normalized_ids))
        )
        return [dict(item) for item in results]

    def _search_uncached(self, normalized_query: str, top_k: int, audio_ids: List[int]) -> List[Dict]:
        with self._lock:
            if self._index is None or not self._metadata:
                return []
            query_vector = self._encode_cached([normalized_query], "query")
            requested_count = min(max(top_k * 4, top_k), len(self._metadata)) if audio_ids else min(top_k, len(self._metadata))
            scores, positions = self._index.search(query_vector, requested_count)
            allowed_ids = set(audio_ids) if audio_ids else None
            results: List[Dict] = []
            for score, position in zip(scores[0], positions[0]):
                if position < 0:
                    continue
                document = self._metadata[int(position)]
                if allowed_ids is not None and document["audioId"] not in allowed_ids:
                    continue
                results.append({
                    "audioId": document["audioId"],
                    "score": round(float(score), 6),
                    "text": document["text"],
                    "songName": document["songName"],
                    "singer": document["singer"],
                })
                if len(results) >= top_k:
                    break
            return results

    def embed_texts(self, texts: List[str], input_type: str = "passage") -> List[List[float]]:
        normalized = [str(text).strip() for text in texts if str(text).strip()]
        if not normalized:
            return []
        vectors = self._encode_cached(normalized, input_type)
        return [[round(float(value), 8) for value in vector] for vector in vectors]

    def _encode_cached(self, texts: List[str], input_type: str) -> np.ndarray:
        normalized = tuple(str(text).strip() for text in texts if str(text).strip())
        key = (input_type, normalized)

        def encode() -> np.ndarray:
            prefix = "query: " if input_type == "query" else "passage: "
            with self._lock:
                return self._get_model().encode(
                    [prefix + text for text in normalized],
                    convert_to_numpy=True,
                    normalize_embeddings=True,
                    show_progress_bar=False,
                ).astype("float32")

        return self._embedding_cache.get_or_load(key, encode).copy()

    def _invalidate_index_caches(self) -> None:
        self._index_generation += 1
        self._search_cache.clear()

    def _get_model(self) -> SentenceTransformer:
        if self._model is None:
            self._model = SentenceTransformer(MODEL_PATH, device=self.device, local_files_only=True)
        return self._model

    def _fetch_audio_rows(self) -> List[Dict]:
        config = dict(MYSQL_CONFIG)
        config.pop("cursorclass", None)
        connection = pymysql.connect(**config, cursorclass=pymysql.cursors.DictCursor)
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT id, song_name, singer, genre, source, introduction, "
                    "collect_count, upload_time FROM audio ORDER BY id"
                )
                return list(cursor.fetchall())
        finally:
            connection.close()

    def _to_document(self, row: Dict) -> Dict:
        song_name = self._text(row.get("song_name"), "未填写")
        singer = self._text(row.get("singer"), "未填写")
        genre = self._text(row.get("genre"), "其他")
        source = self._text(row.get("source"), "未填写")
        introduction = self._text(row.get("introduction"), "未填写")
        upload_time = self._format_value(row.get("upload_time"))
        text = "；".join([
            "歌名：" + song_name,
            "歌手：" + singer,
            "类型：" + genre,
            "出处：" + source,
            "简介：" + introduction,
            "上传时间：" + upload_time,
        ])
        return {
            "audioId": int(row["id"]),
            "songName": song_name,
            "singer": singer,
            "genre": genre,
            "source": source,
            "introduction": introduction,
            "text": text,
        }

    def _load_saved_index(self) -> None:
        if not self._index_path.is_file() or not self._metadata_path.is_file():
            return
        try:
            metadata = json.loads(self._metadata_path.read_text(encoding="utf-8"))
            index = faiss.read_index(str(self._index_path))
            if not isinstance(metadata, list) or index.ntotal != len(metadata):
                return
            self._index = index
            self._metadata = metadata
        except Exception:
            self._index = None
            self._metadata = []

    def _remove_index_files(self) -> None:
        for path in (self._index_path, self._metadata_path):
            if path.exists():
                path.unlink()

    @staticmethod
    def _text(value: object, default: str) -> str:
        text = str(value or "").strip()
        return text if text else default

    @staticmethod
    def _format_value(value: object) -> str:
        if isinstance(value, (datetime, date)):
            return value.strftime("%Y-%m-%d")
        return str(value or "未填写")


engine = VectorRagEngine()
