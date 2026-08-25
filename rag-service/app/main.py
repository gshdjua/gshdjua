"""HTTP contract for the MusicHub local vector RAG service.

The indexing implementation is added in the next approved phase.  This file
intentionally exposes the final API now, so Spring Boot can later integrate
against a stable contract.
"""

from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .engine import engine


app = FastAPI(title="MusicHub Local Vector RAG", version="0.1.0")


class RebuildRequest(BaseModel):
    """Request a full rebuild from the local MySQL audio table."""

    reason: str = Field(default="manual", max_length=80)


class SearchRequest(BaseModel):
    """Semantic retrieval request sent by Spring Boot."""

    query: str = Field(min_length=1, max_length=2000)
    top_k: int = Field(default=5, ge=1, le=20)
    audio_ids: Optional[List[int]] = None


@app.get("/health")
def health() -> dict:
    """Return model and local FAISS index state without forcing model download."""

    return engine.health()


@app.post("/rebuild")
def rebuild_index(payload: RebuildRequest) -> dict:
    """Rebuild local FAISS data from the MySQL audio metadata table."""

    try:
        result = engine.rebuild()
        result["reason"] = payload.reason
        return result
    except Exception as error:
        raise HTTPException(status_code=500, detail="Index rebuild failed: " + str(error)) from error


@app.post("/search")
def search(payload: SearchRequest) -> dict:
    """Return Top-K local semantic matches and their evidence documents."""

    try:
        return {"items": engine.search(payload.query, payload.top_k, payload.audio_ids)}
    except Exception as error:
        raise HTTPException(status_code=500, detail="Vector search failed: " + str(error)) from error
