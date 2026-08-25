"""Configuration for the local-only vector retrieval service."""

import os
from pathlib import Path

from dotenv import load_dotenv


SERVICE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(SERVICE_DIR / ".env")


def get_setting(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def get_data_dir() -> Path:
    configured_path = Path(get_setting("RAG_DATA_DIR", "./data"))
    resolved_path = configured_path if configured_path.is_absolute() else SERVICE_DIR / configured_path
    # Windows FAISS builds cannot write indexes through paths containing Chinese characters.
    try:
        str(resolved_path).encode("ascii")
        return resolved_path
    except UnicodeEncodeError:
        return Path.home() / ".musichub-rag-data"


MODEL_NAME = get_setting("RAG_MODEL_NAME", "intfloat/multilingual-e5-small")


def get_model_path() -> str:
    """Prefer the downloaded snapshot path so transformers never checks the network again."""
    cache_root = Path.home() / ".cache" / "huggingface" / "hub" / "models--intfloat--multilingual-e5-small" / "snapshots"
    if cache_root.is_dir():
        snapshots = sorted((path for path in cache_root.iterdir() if path.is_dir()), key=lambda path: path.stat().st_mtime, reverse=True)
        if snapshots:
            return str(snapshots[0])
    return MODEL_NAME


MODEL_PATH = get_model_path()
MYSQL_CONFIG = {
    "host": get_setting("RAG_MYSQL_HOST", "127.0.0.1"),
    "port": int(get_setting("RAG_MYSQL_PORT", "3306")),
    "user": get_setting("RAG_MYSQL_USERNAME", "root"),
    "password": get_setting("RAG_MYSQL_PASSWORD"),
    "database": get_setting("RAG_MYSQL_DATABASE", "springweb_demo"),
    "charset": "utf8mb4",
    "cursorclass": None,
}
