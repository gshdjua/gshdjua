import unittest
from unittest.mock import patch

from app.audit import ExecutionAuditRepository


class _Cursor:
    def __init__(self):
        self.params = None
        self.all_params = []
        self.sql = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, _sql, params=None):
        self.params = params
        self.all_params.append(params)
        self.sql.append(_sql)

    def fetchone(self):
        return None


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
            "promptVersion": "music_answer:v2",
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
        self.assertIn("music_answer:v2", persisted)
        self.assertEqual(cursor.sql[0].count("%s"), len(cursor.all_params[0]))

    def test_existing_audit_table_gets_prompt_version_column(self):
        repository = ExecutionAuditRepository()
        cursor = _Cursor()
        with patch.object(repository, "_connect", return_value=_Connection(cursor)):
            repository._ensure_schema()

        self.assertTrue(any("ADD COLUMN prompt_version" in sql for sql in cursor.sql))

    def test_prompt_version_label_cannot_store_request_content(self):
        self.assertEqual("unknown", ExecutionAuditRepository._safe_prompt_version("我喜欢摇滚乐"))
        self.assertEqual("music_answer:v12", ExecutionAuditRepository._safe_prompt_version("music_answer:v12"))


if __name__ == "__main__":
    unittest.main()
