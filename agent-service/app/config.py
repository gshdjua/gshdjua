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

