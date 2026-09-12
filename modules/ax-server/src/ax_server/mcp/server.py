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
            "gt_instrumentation_gaps",
            "Which methods COULD have been observed at all. Reports, separately, the "
            "methods the manifest says cannot be covered (C51), the ones our own "
            "tier-1b de-instrumented (C4), and the ones that are observable but that "
            "NO JVM ever installed a probe at (bug #18: ax.tier1.enabled=false, or "
            "frame emission unsupported for a bytecode shape). Read this BEFORE "
            "concluding anything from a low dead-candidate count: an uninstrumented "
            "probe index is permanently zero because nothing can write it, which is "
            "silence, not evidence that the method never ran.",
            {**_BUILD_ARG, "limit": {"type": "integer", "default": 50}},
            ["buildSha"],
            lambda a: self.api.instrumentation_gaps(
                str(a["buildSha"]), limit=int(a.get("limit", 50))
            ),
        )
        self._add(
            "gt_proposals",
            "Standing DEAD_CANDIDATE proposals with first_proposed_at (C53).",
            dict(_BUILD_ARG),
            ["buildSha"],
            lambda a: self.api.proposals(str(a["buildSha"])),
        )
        # -- SCOPE-v3 runtime call graph -------------------------------
        # The descriptions carry the sampling caveat because an LLM reading
        # `sampledObservations: 4` would otherwise report "called 4 times",
        # and an empty caller list would otherwise be read as "nothing calls
        # this" -- the exact inference CONTRACTS 2 v3 forbids.
        self._add(
            "gt_callers_of",
            "OBSERVED runtime callers of a method, from the sampled call-edge tier. "
            "Counts are observations in sampled traces, NOT calls: multiply by "
            "edgesSampleRate for an estimate. An empty list means nothing was "
            "SAMPLED, never that nothing calls the method.",
            {
                **_BUILD_ARG,
                "class": {"type": "string", "description": "Fully qualified class name."},
                "method": {
                    "type": "string",
                    "description": "Method name, or name(descriptor) for an overload.",
                },
                "limit": {"type": "integer", "default": 50},
            },
            ["buildSha", "class", "method"],
            lambda a: self.api.callers_of(
                str(a["buildSha"]), str(a["class"]), str(a["method"]),
                limit=int(a.get("limit", 50)),
            ),
        )
        self._add(
            "gt_callees_of",
            "OBSERVED runtime callees of a method, from the sampled call-edge tier. "
            "Same sampling caveat as gt_callers_of: counts are sampled observations, "
            "NOT calls, and an empty list is not evidence that the method calls "
            "nothing.",
            {
                **_BUILD_ARG,
                "class": {"type": "string", "description": "Fully qualified class name."},
                "method": {
                    "type": "string",
                    "description": "Method name, or name(descriptor) for an overload.",
                },
                "limit": {"type": "integer", "default": 50},
            },
            ["buildSha", "class", "method"],
            lambda a: self.api.callees_of(
                str(a["buildSha"]), str(a["class"]), str(a["method"]),
                limit=int(a.get("limit", 50)),
            ),
        )
        self._add(
            "gt_blast_radius",
            "Before deleting a method: its OBSERVED (sampled) inbound callers and "
            "whether any of them is itself LIVE, plus the separate (61%-unsound, "
            "over-approximate) static callers. Runtime and static are never merged. No "
            "observed caller is NOT evidence that none exists -- the tier is sampled.",
            {
                **_BUILD_ARG,
                "class": {"type": "string", "description": "Fully qualified class name."},
                "method": {
                    "type": "string",
                    "description": "Method name, or name(descriptor) for an overload.",
                },
            },
            ["buildSha", "class", "method"],
            lambda a: self.api.blast_radius(
                str(a["buildSha"]), str(a["class"]), str(a["method"])
            ),
        )
        self._add(
            "gt_hot_paths",
            "The highest-count OBSERVED call edges for a build. A ranking of relative "
            "sampled volume, not call totals, and only comparable among edges recorded "
            "at the same edgesSampleRate.",
            {**_BUILD_ARG, "limit": {"type": "integer", "default": 20}},
            ["buildSha"],
            lambda a: self.api.hot_paths(
                str(a["buildSha"]), limit=int(a.get("limit", 20))
            ),
        )
        # -- BUG #24: exception classes -------------------------------
        self._add(
            "gt_exception_classes",
            "Top exception CLASS NAMES thrown by this build's tier-2 boundary methods. "
            "Three numbers that must not be collapsed: `attributed` (errors with a named "
            "class), `unattributed` (`errors - attributed`, which is LEGAL -- the agent's "
            "id table holds 254 classes with id 255 as an overflow bucket and `errors` is "
            "counted unconditionally) and `errors` (the total). Never reconcile the gap by "
            "assuming a class. `typesAvailable: false` with errors > 0 means the count is "
            "real and the NAMES are unavailable -- it does NOT mean zero exception types.",
            {
                **_BUILD_ARG,
                "sinceDays": {"type": "integer", "default": 7},
                "limit": {"type": "integer", "default": 20},
            },
            ["buildSha"],
            lambda a: self.api.exception_classes(
                str(a["buildSha"]),
                since_days=int(a.get("sinceDays", 7)),
                limit=int(a.get("limit", 20)),
            ),
        )
        # -- BUG #28: the default suppression list --------------------
        self._add(
            "gt_suppressions",
            "Every active suppression rule, in match order, with the list it came from "
            "(`user` = .auxin/suppress.txt, `default` = the shipped compiler/Lombok list). "
            "Read this when a method you expected to see is reported UNKNOWN with a "
            "`suppressed:` reason. A suppressed method is never silently omitted, and the "
            "default list can be turned off entirely with --no-default-suppressions.",
            {},
            [],
            lambda a: self.api.suppressions(),
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
