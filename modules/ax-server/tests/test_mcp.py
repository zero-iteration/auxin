"""MCP stdio JSON-RPC adapter. Thin by construction."""

import io
import json

import pytest

from ax_server.api.service import QueryService
from ax_server.mcp.server import McpServer

from conftest import BUILD_SHA

RS = "com.acme.shipping.RateSelector"


@pytest.fixture
def mcp(ingested, engine):
    return McpServer(QueryService(engine, collector=ingested))


def call(mcp, method, params=None, msg_id=1):
    return mcp.handle(
        {"jsonrpc": "2.0", "id": msg_id, "method": method, "params": params or {}}
    )


def tool(mcp, name, args=None):
    resp = call(mcp, "tools/call", {"name": name, "arguments": args or {}})
    assert "error" not in resp, resp
    return json.loads(resp["result"]["content"][0]["text"])


def test_initialize_handshake(mcp):
    resp = call(mcp, "initialize")
    assert resp["result"]["serverInfo"]["name"] == "auxin"
    assert resp["result"]["capabilities"]["tools"] == {"listChanged": False}


def test_initialized_notification_gets_no_response(mcp):
    assert mcp.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}) is None


def test_tools_list_exposes_the_query_surface(mcp):
    names = {t["name"] for t in call(mcp, "tools/list")["result"]["tools"]}
    assert names == {
        "gt_builds",
        "gt_verdicts",
        "gt_dead_candidates",
        "gt_hot_methods",
        "gt_agent_health",
        "gt_coverage_windows",
        "gt_summary",
        "gt_proposals",
        # Bug #18: the deliberate edit this exact-set assertion asks for.
        "gt_instrumentation_gaps",
        "gt_effective_false_positives",
        # SCOPE-v3 runtime call graph. Still an EXACT set: a tool added
        # without a deliberate edit here still fails this test.
        "gt_callers_of",
        "gt_callees_of",
        "gt_blast_radius",
        "gt_hot_paths",
    }


def test_every_tool_declares_a_schema(mcp):
    for t in call(mcp, "tools/list")["result"]["tools"]:
        assert t["inputSchema"]["type"] == "object"
        assert "properties" in t["inputSchema"]


def test_verdicts_tool(mcp):
    rows = tool(mcp, "gt_verdicts", {"buildSha": BUILD_SHA, "class": RS})
    assert len(rows) == 4


def test_dead_candidates_tool_carries_the_posture(mcp):
    payload = tool(mcp, "gt_dead_candidates", {"buildSha": BUILD_SHA})
    assert payload["count"] == 1
    assert "auto-deleted" in payload["posture"]


def test_hot_methods_tool(mcp):
    rows = tool(mcp, "gt_hot_methods", {"buildSha": BUILD_SHA, "sinceDays": 3650})
    assert rows[0]["percentiles"]["p99"] == 4


def test_agent_health_and_windows_tools(mcp):
    assert tool(mcp, "gt_agent_health", {"buildSha": BUILD_SHA})["windows"] == 4
    assert tool(mcp, "gt_coverage_windows", {"buildSha": BUILD_SHA})["windowDays"] == 4


def test_summary_tool(mcp):
    assert tool(mcp, "gt_summary", {"buildSha": BUILD_SHA})["counts"]["DEAD_CANDIDATE"] == 1


def test_unknown_tool_is_a_jsonrpc_error(mcp):
    resp = call(mcp, "tools/call", {"name": "gt_delete_everything"})
    assert resp["error"]["code"] == -32602


def test_unknown_method_is_a_jsonrpc_error(mcp):
    assert call(mcp, "tools/destroy")["error"]["code"] == -32601


def test_tool_failure_is_reported_not_raised(mcp):
    resp = call(mcp, "tools/call", {"name": "gt_verdicts", "arguments": {"buildSha": "x"}})
    assert resp["error"]["code"] == -32000


def test_stdio_loop_round_trips(mcp):
    stdin = io.StringIO(
        json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize"}) + "\n"
        + json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n"
        + json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}) + "\n"
    )
    stdout = io.StringIO()
    mcp.run(stdin, stdout)
    lines = [json.loads(line) for line in stdout.getvalue().splitlines()]
    assert [m["id"] for m in lines] == [1, 2]


def test_malformed_json_line_is_a_parse_error(mcp):
    stdout = io.StringIO()
    mcp.run(io.StringIO("{not json\n"), stdout)
    assert json.loads(stdout.getvalue())["error"]["code"] == -32700


def test_no_tool_mutates_anything(mcp):
    """Walk the AST, not the text, so docstrings do not confuse the check."""
    import ast
    import inspect

    import ax_server.mcp.server as mod

    tree = ast.parse(inspect.getsource(mod))
    called = {
        node.func.attr
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    }
    forbidden = {
        "merge_coverage", "record_window", "reconcile", "revoke", "confirm",
        "record_feedback", "force", "execute", "executescript", "commit",
    }
    assert not (called & forbidden), called & forbidden
