import unittest
from unittest.mock import patch

from app.audit import ExecutionAuditRepository


class _Cursor:
    def __init__(self):
        self.params = None
        self.all_params = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, _sql, params=None):
        self.params = params
        self.all_params.append(params)


class _Connection:
    def __init__(self, cursor):
        self._cursor = cursor

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def cursor(self):
        return self._cursor


class ExecutionAuditRepositoryTest(unittest.TestCase):
    def test_persisted_audit_excludes_prompts_answers_and_tool_payloads(self):
        repository = ExecutionAuditRepository()
        cursor = _Cursor()
        record = {
            "traceId": "trace-1",
            "requestId": "request-1",
            "provider": "deepseek",
            "model": "deepseek-chat",
            "requestedStrategy": "auto",
            "selectedStrategy": "direct",
            "strategyReason": "single_step_request",
            "costBudget": "standard",
            "modelCalls": 1,
            "toolCalls": 1,
            "toolRounds": 1,
            "toolExecutions": [{
                "tool": "favorite_search", "success": True, "attempts": 1,
                "durationMs": 8, "errorCode": "",
            }],
            "inputTokens": 100,
            "outputTokens": 20,
            "totalTokens": 120,
            "latencyMs": 40,
            "userQuestion": "这是不应保存的问题正文",
            "answer": "这是不应保存的回答正文",
            "toolArguments": {"query": "隐私查询"},
            "toolData": {"items": ["隐私结果"]},
        }
        with patch.object(repository, "_ensure_schema"), patch.object(
            repository, "_connect", return_value=_Connection(cursor)
        ):
            self.assertTrue(repository.save(record))

        persisted = "|".join(str(item) for params in cursor.all_params for item in (params or ()))
        self.assertNotIn("不应保存", persisted)
        self.assertNotIn("隐私查询", persisted)
        self.assertNotIn("隐私结果", persisted)
        self.assertIn("favorite_search", persisted)


if __name__ == "__main__":
    unittest.main()
