from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Dict, List

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI

from .config import (
    api_key,
    base_url,
    default_model,
    qwen_api_key,
    qwen_base_url,
    qwen_default_model,
)


@dataclass(frozen=True)
class LlmModelRequest:
    """Provider-neutral options needed to construct one chat model client."""

    model: str | None = None
    temperature: float = 0.4
    timeout_seconds: float | None = None


class LlmProvider(ABC):
    """Common boundary for every model vendor used by the Agent graph."""

    @property
    @abstractmethod
    def name(self) -> str:
        raise NotImplementedError

    @abstractmethod
    def is_configured(self) -> bool:
        raise NotImplementedError

    @abstractmethod
    def default_model(self) -> str:
        raise NotImplementedError

    @abstractmethod
    def create_chat_model(self, request: LlmModelRequest) -> BaseChatModel:
        raise NotImplementedError

    def public_status(self) -> Dict[str, object]:
        return {
            "name": self.name,
            "configured": self.is_configured(),
            "defaultModel": self.default_model(),
        }


class DeepSeekProvider(LlmProvider):
    @property
    def name(self) -> str:
        return "deepseek"

    def is_configured(self) -> bool:
        return bool(api_key())

    def default_model(self) -> str:
        return default_model()

    def create_chat_model(self, request: LlmModelRequest) -> BaseChatModel:
        key = api_key()
        if not key:
            raise RuntimeError("DeepSeek API key is not configured")
        return ChatOpenAI(
            api_key=key,
            base_url=base_url(),
            model=request.model or self.default_model(),
            temperature=request.temperature,
            timeout=request.timeout_seconds,
            max_retries=0,
        )

    def public_status(self) -> Dict[str, object]:
        result = super().public_status()
        result["baseUrl"] = base_url()
        return result


class QwenProvider(LlmProvider):
    """OpenAI-compatible Qwen adapter for Qianwen/DashScope endpoints."""

    @property
    def name(self) -> str:
        return "qwen"

    def is_configured(self) -> bool:
        return bool(qwen_api_key())

    def default_model(self) -> str:
        return qwen_default_model()

    def create_chat_model(self, request: LlmModelRequest) -> BaseChatModel:
        key = qwen_api_key()
        if not key:
            raise RuntimeError("Qwen API key is not configured")
        return ChatOpenAI(
            api_key=key,
            base_url=qwen_base_url(),
            model=request.model or self.default_model(),
            temperature=request.temperature,
            timeout=request.timeout_seconds,
            max_retries=0,
        )

    def public_status(self) -> Dict[str, object]:
        result = super().public_status()
        result["baseUrl"] = qwen_base_url()
        return result


class LlmProviderRegistry:
    def __init__(self, providers: List[LlmProvider] | None = None) -> None:
        self._providers: Dict[str, LlmProvider] = {}
        for provider in providers or []:
            self.register(provider)

    def register(self, provider: LlmProvider) -> None:
        name = (provider.name or "").strip().lower()
        if not name:
            raise ValueError("LLM provider name cannot be empty")
        if name in self._providers:
            raise ValueError("Duplicate LLM provider: " + name)
        self._providers[name] = provider

    def get(self, name: str) -> LlmProvider:
        normalized = (name or "").strip().lower()
        provider = self._providers.get(normalized)
        if provider is None:
            raise ValueError("Unsupported provider: " + normalized)
        return provider

    def create_chat_model(self, provider_name: str, request: LlmModelRequest) -> BaseChatModel:
        return self.get(provider_name).create_chat_model(request)

    def statuses(self) -> List[Dict[str, object]]:
        return [self._providers[name].public_status() for name in sorted(self._providers)]


llm_provider_registry = LlmProviderRegistry([DeepSeekProvider(), QwenProvider()])
