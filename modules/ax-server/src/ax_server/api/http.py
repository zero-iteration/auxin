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
            r"/v1/builds/(?P<build>[^/]+)/proposals",
            lambda *, params, **_: (200, api.proposals(params["build"])),
        )
        .add(
            "GET",
            r"/v1/effective-false-positives",
            lambda **_: (200, api.effective_false_positives()),
        )
    )
