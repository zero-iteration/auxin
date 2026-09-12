"""`POST /v1/ingest` over stdlib `http.server`.

No FastAPI. The ingest surface is two routes and a gzip decode; a framework
would add a dependency, a process manager and a schema layer we would then have
to keep in sync with a contract that is already frozen. `CollectorService` holds
all the logic, so this file stays a shell.
"""

import json
import logging
import re
from collections.abc import Callable, Mapping
from urllib.parse import unquote_plus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from ax_server.collector.errors import IngestRejected
from ax_server.collector.service import CollectorService

__all__ = ["Route", "Router", "JsonHandler", "make_collector_router", "serve"]

log = logging.getLogger("ax.http")

Handler = Callable[..., tuple[int, Any]]


class Route:
    def __init__(self, method: str, pattern: str, handler: Handler) -> None:
        self.method = method.upper()
        self.regex = re.compile("^" + pattern + "$")
        self.handler = handler


class Router:
    def __init__(self, routes: list[Route] | None = None) -> None:
        self.routes = routes or []

    def add(self, method: str, pattern: str, handler: Handler) -> "Router":
        self.routes.append(Route(method, pattern, handler))
        return self

    def extend(self, other: "Router") -> "Router":
        self.routes.extend(other.routes)
        return self

    def match(self, method: str, path: str) -> tuple[Handler, dict[str, str]] | None:
        for route in self.routes:
            if route.method != method.upper():
                continue
            m = route.regex.match(path)
            if m:
                return route.handler, m.groupdict()
        return None


class JsonHandler(BaseHTTPRequestHandler):
    """Dispatches to a `Router` set as a class attribute by `serve`."""

    router: Router = Router()
    server_version = "auxin/0.2"

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        log.debug("%s - %s", self.address_string(), fmt % args)

    # -- verbs -----------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET", body=b"")

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        self._dispatch("POST", body=body)

    # -- plumbing --------------------------------------------------------

    def _dispatch(self, method: str, *, body: bytes) -> None:
        path, _, query = self.path.partition("?")
        found = self.router.match(method, path)
        if found is None:
            self._respond(404, {"error": "not found", "path": path})
            return
        handler, params = found
        try:
            status, payload = handler(
                body=body,
                headers=self.headers,
                params=params,
                query=_parse_query(query),
            )
        except IngestRejected as exc:
            self._respond(exc.status, {"error": exc.reason, "detail": exc.detail})
            return
        except Exception as exc:  # pragma: no cover - defensive
            log.exception("unhandled error serving %s %s", method, path)
            self._respond(500, {"error": "internal", "detail": str(exc)})
            return
        self._respond(status, payload)

    def _respond(self, status: int, payload: Any) -> None:
        blob = json.dumps(payload, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(blob)))
        self.end_headers()
        self.wfile.write(blob)


def _parse_query(query: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for part in query.split("&"):
        if not part:
            continue
        key, _, value = part.partition("=")
        out[unquote_plus(key)] = unquote_plus(value)
    return out


def make_collector_router(collector: CollectorService) -> Router:
    def ingest(*, body: bytes, headers: Mapping[str, str], **_: Any) -> tuple[int, Any]:
        result = collector.ingest_bytes(
            body, content_encoding=headers.get("Content-Encoding")
        )
        return 202, result.to_json()

    def health(**_: Any) -> tuple[int, Any]:
        return 200, collector.health.snapshot()

    return (
        Router()
        .add("POST", r"/v1/ingest", ingest)
        .add("GET", r"/v1/ingest/health", health)
    )


def serve(router: Router, *, host: str = "127.0.0.1", port: int = 8787) -> ThreadingHTTPServer:
    handler = type("BoundJsonHandler", (JsonHandler,), {"router": router})
    return ThreadingHTTPServer((host, port), handler)
