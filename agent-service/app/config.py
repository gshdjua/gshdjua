import os
from pathlib import Path

from dotenv import load_dotenv


for env_path in (
    Path.cwd() / ".env",
    Path.cwd().parent / ".env",
    Path.cwd().parent / "springboot-web-demo" / ".env",
):
    if env_path.is_file():
        load_dotenv(env_path, override=False)


def first_config(*names: str, default: str = "") -> str:
    for name in names:
        value = os.getenv(name, "").strip()
        if value:
            return value
    return default


def api_key() -> str:
    return first_config("DEEPSEEK_API_KEY", "OPENAI_API_KEY")


def base_url() -> str:
    return first_config(
        "DEEPSEEK_BASE_URL", "OPENAI_BASE_URL", default="https://api.deepseek.com/v1"
    ).rstrip("/")


def default_model() -> str:
    return first_config("DEEPSEEK_MODEL", "OPENAI_MODEL", default="deepseek-chat")


def mysql_config() -> dict:
    return {
        "host": first_config("AGENT_MYSQL_HOST", "MUSICHUB_DB_HOST", default="127.0.0.1"),
        "port": int(first_config("AGENT_MYSQL_PORT", default="3306")),
        "user": first_config("AGENT_MYSQL_USERNAME", "MUSICHUB_DB_USERNAME", default="root"),
        "password": first_config("AGENT_MYSQL_PASSWORD", "MUSICHUB_DB_PASSWORD", default="123456"),
        "database": first_config("AGENT_MYSQL_DATABASE", default="springweb_demo"),
        "charset": "utf8mb4",
        "autocommit": True,
        "connect_timeout": 2,
        "read_timeout": 3,
        "write_timeout": 3,
    }


def vector_rag_base_url() -> str:
    return first_config("VECTOR_RAG_BASE_URL", default="http://127.0.0.1:8090").rstrip("/")
