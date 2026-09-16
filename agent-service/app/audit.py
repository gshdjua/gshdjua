import json
import logging
import threading
import time
from typing import Any, Dict, Optional

import pymysql

from .config import audit_retention_days, mysql_config


LOGGER = logging.getLogger(__name__)


class ExecutionAuditRepository:
    """Persists privacy-safe execution summaries without prompts, answers, or chain of thought."""

    def __init__(self) -> None:
        self._schema_ready = False
        self._schema_lock = threading.Lock()
        self._cleanup_lock = threading.Lock()
        self._last_cleanup_at = 0.0

    def available(self) -> bool:
        try:
            self._ensure_schema()
            return True
        except Exception:
            LOGGER.exception("Agent execution audit store is unavailable")
            return False

    def save(self, record: Dict[str, Any]) -> bool:
        try:
            self._ensure_schema()
            tool_executions = record.get("toolExecutions") or []
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO agent_execution_audit("
                    "trace_id,request_id,provider,model,requested_strategy,selected_strategy,strategy_reason,"
                    "cost_budget,model_calls,tool_calls,tool_rounds,tool_executions,input_tokens,output_tokens,"
                    "total_tokens,latency_ms,budget_exceeded,stop_reason,finish_reason,status,error_code) "
                    "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                    "ON DUPLICATE KEY UPDATE request_id=VALUES(request_id),provider=VALUES(provider),"
                    "model=VALUES(model),requested_strategy=VALUES(requested_strategy),"
                    "selected_strategy=VALUES(selected_strategy),strategy_reason=VALUES(strategy_reason),"
                    "cost_budget=VALUES(cost_budget),model_calls=VALUES(model_calls),tool_calls=VALUES(tool_calls),"
                    "tool_rounds=VALUES(tool_rounds),tool_executions=VALUES(tool_executions),"
                    "input_tokens=VALUES(input_tokens),output_tokens=VALUES(output_tokens),"
                    "total_tokens=VALUES(total_tokens),latency_ms=VALUES(latency_ms),"
                    "budget_exceeded=VALUES(budget_exceeded),stop_reason=VALUES(stop_reason),"
                    "finish_reason=VALUES(finish_reason),status=VALUES(status),error_code=VALUES(error_code),"
                    "updated_at=CURRENT_TIMESTAMP",
                    (
                        str(record.get("traceId", ""))[:191],
                        str(record.get("requestId", ""))[:100],
                        str(record.get("provider", ""))[:30],
                        str(record.get("model", ""))[:100],
                        str(record.get("requestedStrategy", ""))[:20],
                        str(record.get("selectedStrategy", ""))[:20],
                        str(record.get("strategyReason", ""))[:50],
                        str(record.get("costBudget", ""))[:20],
                        int(record.get("modelCalls", 0) or 0),
                        int(record.get("toolCalls", 0) or 0),
                        int(record.get("toolRounds", 0) or 0),
                        json.dumps(tool_executions, ensure_ascii=False, separators=(",", ":")),
                        int(record.get("inputTokens", 0) or 0),
                        int(record.get("outputTokens", 0) or 0),
                        int(record.get("totalTokens", 0) or 0),
                        int(record.get("latencyMs", 0) or 0),
                        1 if record.get("budgetExceeded", False) else 0,
                        str(record.get("stopReason", ""))[:50],
                        str(record.get("finishReason", ""))[:50],
                        str(record.get("status", "success"))[:20],
                        str(record.get("errorCode", ""))[:80],
                    ),
                )
                self._cleanup_expired(cursor)
            return True
        except Exception:
            LOGGER.exception("Failed to persist agent execution audit traceId=%s", record.get("traceId", ""))
            return False

    def get(self, trace_id: str) -> Optional[Dict[str, Any]]:
        self._ensure_schema()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT trace_id,request_id,provider,model,requested_strategy,selected_strategy,strategy_reason,"
                "cost_budget,model_calls,tool_calls,tool_rounds,tool_executions,input_tokens,output_tokens,"
                "total_tokens,latency_ms,budget_exceeded,stop_reason,finish_reason,status,error_code,created_at "
                "FROM agent_execution_audit WHERE trace_id=%s",
                (trace_id,),
            )
            row = cursor.fetchone()
        if not row:
            return None
        return {
            "traceId": row[0], "requestId": row[1], "provider": row[2], "model": row[3],
            "requestedStrategy": row[4], "selectedStrategy": row[5], "strategyReason": row[6],
            "costBudget": row[7], "modelCalls": int(row[8]), "toolCalls": int(row[9]),
            "toolRounds": int(row[10]), "toolExecutions": self._decode_tools(row[11]),
            "inputTokens": int(row[12]), "outputTokens": int(row[13]), "totalTokens": int(row[14]),
            "latencyMs": int(row[15]), "budgetExceeded": bool(row[16]), "stopReason": row[17] or "",
            "finishReason": row[18] or "", "status": row[19], "errorCode": row[20] or "",
            "createdAt": row[21],
        }

    @staticmethod
    def _decode_tools(value: Any) -> list:
        if isinstance(value, list):
            return value
        try:
            decoded = json.loads(value or "[]")
            return decoded if isinstance(decoded, list) else []
        except (TypeError, ValueError):
            return []

    def _ensure_schema(self) -> None:
        if self._schema_ready:
            return
        with self._schema_lock:
            if self._schema_ready:
                return
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TABLE IF NOT EXISTS agent_execution_audit ("
                    "trace_id VARCHAR(191) PRIMARY KEY,request_id VARCHAR(100) NOT NULL,"
                    "provider VARCHAR(30) NOT NULL,model VARCHAR(100) NOT NULL,"
                    "requested_strategy VARCHAR(20) NOT NULL,selected_strategy VARCHAR(20) NOT NULL,"
                    "strategy_reason VARCHAR(50) NOT NULL,cost_budget VARCHAR(20) NOT NULL,"
                    "model_calls INT NOT NULL DEFAULT 0,tool_calls INT NOT NULL DEFAULT 0,"
                    "tool_rounds INT NOT NULL DEFAULT 0,tool_executions JSON NOT NULL,"
                    "input_tokens INT NOT NULL DEFAULT 0,output_tokens INT NOT NULL DEFAULT 0,"
                    "total_tokens INT NOT NULL DEFAULT 0,latency_ms INT NOT NULL DEFAULT 0,"
                    "budget_exceeded TINYINT(1) NOT NULL DEFAULT 0,stop_reason VARCHAR(50) NOT NULL DEFAULT '',"
                    "finish_reason VARCHAR(50) NOT NULL DEFAULT '',status VARCHAR(20) NOT NULL DEFAULT 'success',"
                    "error_code VARCHAR(80) NOT NULL DEFAULT '',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    "KEY idx_agent_audit_created(created_at),KEY idx_agent_audit_strategy(selected_strategy,created_at)"
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                )
                self._cleanup_expired(cursor, force=True)
            self._schema_ready = True

    def _cleanup_expired(self, cursor, force: bool = False) -> None:
        now = time.monotonic()
        with self._cleanup_lock:
            if not force and now - self._last_cleanup_at < 3600:
                return
            cursor.execute(
                "DELETE FROM agent_execution_audit "
                "WHERE created_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL %s DAY)",
                (audit_retention_days(),),
            )
            self._last_cleanup_at = now

    @staticmethod
    def _connect():
        return pymysql.connect(**mysql_config())


audit_repository = ExecutionAuditRepository()
