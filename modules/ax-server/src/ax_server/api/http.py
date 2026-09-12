"""Read-only HTTP surface. GET only -- there is no route here that writes."""

from collections.abc import Mapping
from typing import Any

from ax_server.api.service import QueryService
from ax_server.collector.http import Router

__all__ = ["make_api_router"]


def _int(query: Mapping[str, str], key: str, default: int) -> int:
    try:
        return int(query.get(key, default))
    except (TypeError, ValueError):
        return default


def make_api_router(api: QueryService) -> Router:
    def verdicts(*, params: Mapping[str, str], query: Mapping[str, str], **_: Any):
        sha = params["build"]
        if "class" in query:
            return 200, api.verdicts_by_class(sha, query["class"])
        if "package" in query:
            return 200, api.verdicts_by_package(sha, query["package"])
        if "file" in query:
            return 200, api.verdicts_by_file(sha, query["file"])
        return 400, {"error": "one of class=, package= or file= is required"}

    def _edge_endpoint(query: Mapping[str, str]) -> tuple[str, str] | None:
        cls, method = query.get("class"), query.get("method")
        if not cls or not method:
            return None
        return cls, method

    def callers(*, params: Mapping[str, str], query: Mapping[str, str], **_: Any):
        target = _edge_endpoint(query)
        if target is None:
            return 400, {"error": "class= and method= are both required"}
        try:
            return 200, api.callers_of(params["build"], *target, limit=_int(query, "limit", 50))
        except ValueError as exc:
            return 404, {"error": str(exc)}

    def callees(*, params: Mapping[str, str], query: Mapping[str, str], **_: Any):
        target = _edge_endpoint(query)
        if target is None:
            return 400, {"error": "class= and method= are both required"}
        try:
            return 200, api.callees_of(params["build"], *target, limit=_int(query, "limit", 50))
        except ValueError as exc:
            return 404, {"error": str(exc)}

    def blast_radius(*, params: Mapping[str, str], query: Mapping[str, str], **_: Any):
        target = _edge_endpoint(query)
        if target is None:
            return 400, {"error": "class= and method= are both required"}
        try:
            return 200, api.blast_radius(params["build"], *target)
        except ValueError as exc:
            return 404, {"error": str(exc)}

    return (
        Router()
        .add("GET", r"/v1/builds", lambda **_: (200, api.builds()))
        .add("GET", r"/v1/builds/(?P<build>[^/]+)/verdicts", verdicts)
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/summary",
            lambda *, params, **_: (200, api.summary(params["build"])),
        )
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/dead-candidates",
            lambda *, params, query, **_: (
                200,
                api.dead_candidates(params["build"], _int(query, "limit", 100)),
            ),
        )
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/hot-methods",
            lambda *, params, query, **_: (
                200,
                api.hot_methods(
                    params["build"],
                    since_days=_int(query, "sinceDays", 7),
                    limit=_int(query, "limit", 50),
                ),
            ),
        )
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/agent-health",
            lambda *, params, **_: (200, api.agent_health(params["build"])),
        )
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/coverage-windows",
            lambda *, params, **_: (200, api.coverage_windows(params["build"])),
        )
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/instrumentation-gaps",
            lambda *, params, query, **_: (
                200,
                api.instrumentation_gaps(params["build"], limit=_int(query, "limit", 50)),
            ),
        )
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/proposals",
            lambda *, params, **_: (200, api.proposals(params["build"])),
        )
        .add(
            "GET",
            r"/v1/effective-false-positives",
            lambda **_: (200, api.effective_false_positives()),
        )
        # BUG #28: the default suppression list has to be LISTABLE, or it is an
        # invisible filter.
        .add("GET", r"/v1/suppressions", lambda **_: (200, api.suppressions()))
        # BUG #24: "top exception classes for this build".
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/exception-classes",
            lambda *, params, query, **_: (
                200,
                api.exception_classes(
                    params["build"],
                    since_days=_int(query, "sinceDays", 7),
                    limit=_int(query, "limit", 20),
                ),
            ),
        )
        # -- SCOPE-v3 runtime call graph ---------------------------------
        .add("GET", r"/v1/builds/(?P<build>[^/]+)/callers", callers)
        .add("GET", r"/v1/builds/(?P<build>[^/]+)/callees", callees)
        .add("GET", r"/v1/builds/(?P<build>[^/]+)/blast-radius", blast_radius)
        .add(
            "GET",
            r"/v1/builds/(?P<build>[^/]+)/hot-paths",
            lambda *, params, query, **_: (
                200,
                api.hot_paths(params["build"], limit=_int(query, "limit", 20)),
            ),
        )
    )
