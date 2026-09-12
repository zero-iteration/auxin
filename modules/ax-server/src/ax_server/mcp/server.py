"""MCP server over stdio JSON-RPC 2.0.

A THIN adapter. Every tool here is one call into `QueryService` and a JSON
dump; there is no analysis logic in this package and there must never be. If a
tool needs a new capability, it belongs in `api`.

Read-only by construction: no tool mutates state, and none can delete anything.
"""

import json
import logging
import sys
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any, TextIO

from ax_server.api.service import QueryService

__all__ = ["McpServer", "Tool"]

log = logging.getLogger("ax.mcp")

PROTOCOL_VERSION = "2024-11-05"

_BUILD_ARG = {
    "buildSha": {"type": "string", "description": "Build SHA to query (CONTRACTS 1 identity)."}
}


@dataclass(frozen=True, slots=True)
class Tool:
    name: str
    description: str
    schema: dict[str, Any]
    fn: Callable[[Mapping[str, Any]], Any]

    def to_json(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "inputSchema": self.schema,
        }


class McpServer:
    def __init__(self, api: QueryService, *, name: str = "auxin") -> None:
        self.api = api
        self.name = name
        self.tools: dict[str, Tool] = {}
        self._register()

    # -- tools -----------------------------------------------------------

    def _add(
        self,
        name: str,
        description: str,
        properties: dict[str, Any],
        required: list[str],
        fn: Callable[[Mapping[str, Any]], Any],
    ) -> None:
        self.tools[name] = Tool(
            name,
            description,
            {"type": "object", "properties": properties, "required": required},
            fn,
        )

    def _register(self) -> None:
        self._add(
            "gt_builds",
            "List build SHAs that have reported observation windows.",
            {},
            [],
            lambda a: self.api.builds(),
        )
        self._add(
            "gt_verdicts",
            "Verdicts for a class, package or source file. UNKNOWN is the default "
            "status; DEAD_CANDIDATE requires every clause of the PLAN-v2 rule to "
            "pass. `reasons` lists every rule that fired, in order.",
            {
                **_BUILD_ARG,
                "class": {"type": "string", "description": "Fully qualified class name."},
                "package": {"type": "string", "description": "Package prefix."},
                "file": {"type": "string", "description": "Source file name."},
            },
            ["buildSha"],
            self._verdicts,
        )
        self._add(
            "gt_dead_candidates",
            "Methods where every clause of the correctness rule passed. These are "
            "PROPOSALS for human review, re-derived and revocable on every run; "
            "nothing is ever auto-deleted. Treat roughly 1 in 3 as genuinely "
            "removable (C57).",
            {**_BUILD_ARG, "limit": {"type": "integer", "default": 100}},
            ["buildSha"],
            lambda a: self.api.dead_candidates(
                str(a["buildSha"]), int(a.get("limit", 100))
            ),
        )
        self._add(
            "gt_hot_methods",
            "Tier-2 boundary methods by call volume, with p50/p90/p99 computed "
            "server-side from log-linear buckets.",
            {
                **_BUILD_ARG,
                "sinceDays": {"type": "integer", "default": 7},
                "limit": {"type": "integer", "default": 50},
            },
            ["buildSha"],
            lambda a: self.api.hot_methods(
                str(a["buildSha"]),
                since_days=int(a.get("sinceDays", 7)),
                limit=int(a.get("limit", 50)),
            ),
        )
        self._add(
            "gt_agent_health",
            "Agent health for a build: transform failures, skipped classes, ring "
            "drops, degraded windows, plus collector ingest counters.",
            dict(_BUILD_ARG),
            ["buildSha"],
            lambda a: self.api.agent_health(str(a["buildSha"])),
        )
        self._add(
            "gt_coverage_windows",
            "Observation windows and which business phases they spanned. Windows "
            "that are degraded, non-production or test-tainted are listed "
            "separately and never used as evidence of death.",
            dict(_BUILD_ARG),
            ["buildSha"],
            lambda a: self.api.coverage_windows(str(a["buildSha"])),
        )
        self._add(
            "gt_summary",
            "Verdict counts, window span and phase coverage for a build.",
            dict(_BUILD_ARG),
            ["buildSha"],
            lambda a: self.api.summary(str(a["buildSha"])),
        )
        self._add(
            "gt_proposals",
            "Standing DEAD_CANDIDATE proposals with first_proposed_at (C53).",
            dict(_BUILD_ARG),
            ["buildSha"],
            lambda a: self.api.proposals(str(a["buildSha"])),
        )
        self._add(
            "gt_effective_false_positives",
            "Per-rule-class effective-false-positive rates and self-disable state "
            "(C55, Google Tricorder's definition).",
            {},
            [],
            lambda a: self.api.effective_false_positives(),
        )

    def _verdicts(self, args: Mapping[str, Any]) -> Any:
        sha = str(args["buildSha"])
        if "class" in args:
            return self.api.verdicts_by_class(sha, str(args["class"]))
        if "package" in args:
            return self.api.verdicts_by_package(sha, str(args["package"]))
        if "file" in args:
            return self.api.verdicts_by_file(sha, str(args["file"]))
        raise ValueError("one of class, package or file is required")

    # -- JSON-RPC --------------------------------------------------------

    def handle(self, message: Mapping[str, Any]) -> dict[str, Any] | None:
        method = message.get("method")
        msg_id = message.get("id")
        try:
            if method == "initialize":
                result: Any = {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": self.name, "version": "0.2.0"},
                }
            elif method in ("notifications/initialized", "initialized"):
                return None
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": [t.to_json() for t in self.tools.values()]}
            elif method == "tools/call":
                params = message.get("params") or {}
                name = str(params.get("name", ""))
                tool = self.tools.get(name)
                if tool is None:
                    return _error(msg_id, -32602, f"unknown tool {name!r}")
                payload = tool.fn(params.get("arguments") or {})
                result = {
                    "content": [
                        {"type": "text", "text": json.dumps(payload, default=str, indent=2)}
                    ],
                    "isError": False,
                }
            else:
                return _error(msg_id, -32601, f"method not found: {method}")
        except Exception as exc:
            log.exception("mcp tool failed: %s", method)
            return _error(msg_id, -32000, str(exc))
        if msg_id is None:
            return None
        return {"jsonrpc": "2.0", "id": msg_id, "result": result}

    def run(self, stdin: TextIO | None = None, stdout: TextIO | None = None) -> None:
        src = stdin or sys.stdin
        dst = stdout or sys.stdout
        for line in src:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError as exc:
                dst.write(json.dumps(_error(None, -32700, str(exc))) + "\n")
                dst.flush()
                continue
            response = self.handle(message)
            if response is not None:
                dst.write(json.dumps(response, default=str) + "\n")
                dst.flush()


def _error(msg_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}
