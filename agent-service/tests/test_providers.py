import unittest
from unittest.mock import patch

from app.providers import (
    DeepSeekProvider,
    LlmModelRequest,
    LlmProvider,
    LlmProviderRegistry,
    QwenProvider,
)


class FakeProvider(LlmProvider):
    @property
    def name(self) -> str:
        return "fake"

    def is_configured(self) -> bool:
        return True

    def default_model(self) -> str:
        return "fake-model"

    def create_chat_model(self, request: LlmModelRequest):
        return {"model": request.model or self.default_model(), "temperature": request.temperature}


class LlmProviderRegistryTest(unittest.TestCase):
    def test_registry_routes_provider_neutral_model_request(self):
        registry = LlmProviderRegistry([FakeProvider()])

        model = registry.create_chat_model(
            "FAKE", LlmModelRequest(model=None, temperature=0.2, timeout_seconds=3.0)
        )

        self.assertEqual({"model": "fake-model", "temperature": 0.2}, model)
        self.assertEqual("fake", registry.statuses()[0]["name"])

    def test_registry_rejects_duplicate_and_unknown_provider(self):
        registry = LlmProviderRegistry([FakeProvider()])

        with self.assertRaisesRegex(ValueError, "Duplicate LLM provider"):
            registry.register(FakeProvider())
        with self.assertRaisesRegex(ValueError, "Unsupported provider"):
            registry.get("unknown")


class DeepSeekProviderTest(unittest.TestCase):
    def test_deepseek_adapter_builds_existing_chat_openai_client(self):
        provider = DeepSeekProvider()
        sentinel = object()

        with patch("app.providers.api_key", return_value="secret"), \
                patch("app.providers.base_url", return_value="https://api.deepseek.test/v1"), \
                patch("app.providers.default_model", return_value="deepseek-chat"), \
                patch("app.providers.ChatOpenAI", return_value=sentinel) as chat_open_ai:
            model = provider.create_chat_model(
                LlmModelRequest(model=None, temperature=0.3, timeout_seconds=8.0)
            )

        self.assertIs(sentinel, model)
        chat_open_ai.assert_called_once_with(
            api_key="secret",
            base_url="https://api.deepseek.test/v1",
            model="deepseek-chat",
            temperature=0.3,
            timeout=8.0,
            max_retries=0,
        )

    def test_deepseek_adapter_requires_api_key(self):
        with patch("app.providers.api_key", return_value=""):
            with self.assertRaisesRegex(RuntimeError, "DeepSeek API key"):
                DeepSeekProvider().create_chat_model(LlmModelRequest())


class QwenProviderTest(unittest.TestCase):
    def test_qwen_adapter_builds_openai_compatible_client(self):
        provider = QwenProvider()
        sentinel = object()

        with patch("app.providers.qwen_api_key", return_value="qwen-secret"), \
                patch("app.providers.qwen_base_url", return_value="https://qwen.test/v1"), \
                patch("app.providers.qwen_default_model", return_value="qwen-plus"), \
                patch("app.providers.ChatOpenAI", return_value=sentinel) as chat_open_ai:
            model = provider.create_chat_model(
                LlmModelRequest(model="qwen-turbo", temperature=0.2, timeout_seconds=9.0)
            )

        self.assertIs(sentinel, model)
        chat_open_ai.assert_called_once_with(
            api_key="qwen-secret",
            base_url="https://qwen.test/v1",
            model="qwen-turbo",
            temperature=0.2,
            timeout=9.0,
            max_retries=0,
        )

    def test_qwen_adapter_accepts_configured_default_model_and_requires_key(self):
        provider = QwenProvider()
        with patch("app.providers.qwen_api_key", return_value=""):
            self.assertFalse(provider.is_configured())
            with self.assertRaisesRegex(RuntimeError, "Qwen API key"):
                provider.create_chat_model(LlmModelRequest())


if __name__ == "__main__":
    unittest.main()
