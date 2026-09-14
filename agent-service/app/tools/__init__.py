"""Typed tools available to the MusicHub agent."""

from .catalog import tool_registry
from .models import ToolContext, ToolExecutionResult

__all__ = ["ToolContext", "ToolExecutionResult", "tool_registry"]
