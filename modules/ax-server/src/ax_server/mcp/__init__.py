"""gt-mcp: a thin stdio JSON-RPC adapter over `api`. Highest layer."""

from ax_server.mcp.server import McpServer, Tool

__all__ = ["McpServer", "Tool"]
