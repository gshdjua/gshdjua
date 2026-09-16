from langchain_openai import ChatOpenAI

from .config import api_key, base_url, default_model


def create_deepseek_model(
    model: str | None,
    temperature: float,
    timeout_seconds: float | None = None,
) -> ChatOpenAI:
    key = api_key()
    if not key:
        raise RuntimeError("DeepSeek API key is not configured")
    return ChatOpenAI(
        api_key=key,
        base_url=base_url(),
        model=model or default_model(),
        temperature=temperature,
        timeout=timeout_seconds,
        max_retries=0,
    )
