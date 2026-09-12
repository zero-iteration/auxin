#!/usr/bin/env bash
# Serve modules/ax-ui/ as static files AND reverse-proxy /v1/* to a running
# collector, so the UI can be exercised with no change to ax-server.
#
# WHY A PROXY AND NOT A BARE `python3 -m http.server`:
#   ax-server's JsonHandler._respond sends Content-Type and Content-Length and
#   NOTHING else -- there is no Access-Control-Allow-Origin header anywhere in
#   modules/ax-server/. So a page served from http://localhost:8000 cannot read
#   a response from http://127.0.0.1:8099: the browser blocks it. Every fetch
#   in the UI would fail, and the page would (correctly, but uselessly) render
#   "not available" everywhere.
#   This script therefore puts the static files and the API on ONE origin --
#   which is also exactly how it will behave once the server mounts ax-ui/.
#   It is still stdlib-only python3 http.server; no dependency is added.
#
# Usage:
#   ./dev-serve.sh                       # UI on :8080, collector at :8787
#   ./dev-serve.sh 8080 8099             # UI on :8080, collector on :8099
#   ./dev-serve.sh 8080 http://host:9000 # explicit collector base URL
#
# Then open http://127.0.0.1:8080/
#
#   --static-only   skip the proxy and run a plain `python3 -m http.server`.
#                   The page will load and every API call will fail; useful
#                   only for checking that the "not available" paths render.

set -euo pipefail
cd "$(dirname "$0")"

UI_PORT="${1:-8080}"
UPSTREAM="${2:-8787}"
case "$UPSTREAM" in
  http://*|https://*) ;;
  *) UPSTREAM="http://127.0.0.1:${UPSTREAM}" ;;
esac

if [ "${1:-}" = "--static-only" ] || [ "${2:-}" = "--static-only" ] || [ "${3:-}" = "--static-only" ]; then
  echo "ax-ui: static only on http://127.0.0.1:${UI_PORT}/  (API calls WILL fail: no CORS on the collector)"
  exec python3 -m http.server "${UI_PORT}" --bind 127.0.0.1
fi

echo "ax-ui: http://127.0.0.1:${UI_PORT}/   ->  proxying /v1/* to ${UPSTREAM}"

AX_UI_PORT="${UI_PORT}" AX_UPSTREAM="${UPSTREAM}" exec python3 - <<'PY'
import os, sys, time, urllib.request, urllib.error
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ["AX_UI_PORT"])
UP = os.environ["AX_UPSTREAM"].rstrip("/")
HERE = os.path.dirname(os.path.abspath(__file__)) if "__file__" in dir() else os.getcwd()


class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        sys.stderr.write("  %s\n" % (fmt % a))

    def do_GET(self):
        if self.path.startswith("/v1/"):
            return self._proxy()
        return SimpleHTTPRequestHandler.do_GET(self)

    def do_POST(self):
        # The UI never POSTs (the API is read-only), but proxy it anyway so a
        # curl against this port behaves like a curl against the collector.
        if self.path.startswith("/v1/"):
            return self._proxy(body=self.rfile.read(int(self.headers.get("Content-Length") or 0)))
        self.send_error(405)

    def _proxy(self, body=None):
        url = UP + self.path
        payload = status = ctype = None
        # ax-server is a stdlib ThreadingHTTPServer and socketserver's default
        # listen backlog is 5, so a burst of parallel GETs can have a
        # connection REFUSED at the socket. Retry connection-level failures a
        # couple of times -- and SAY SO on stderr, because a silent retry that
        # papered over a genuinely dead collector would be its own lie.
        for attempt in range(3):
            req = urllib.request.Request(url, data=body, method=self.command)
            ct = self.headers.get("Content-Type")
            if ct:
                req.add_header("Content-Type", ct)
            try:
                with urllib.request.urlopen(req, timeout=60) as up:
                    payload = up.read()
                    status = up.status
                    ctype = up.headers.get("Content-Type", "application/json")
                break
            except urllib.error.HTTPError as e:
                # Pass the upstream status THROUGH. A 404 must reach the UI as
                # a 404 so it can say "not available" instead of drawing a zero.
                payload, status = e.read(), e.code
                ctype = e.headers.get("Content-Type", "application/json")
                break
            except Exception as e:
                last = e
                if attempt < 2:
                    sys.stderr.write("  retry %d %s (%s)\n" % (attempt + 1, self.path, e))
                    time.sleep(0.15 * (attempt + 1))
        if payload is None:
            payload = ('{"error": "ax-ui dev proxy could not reach %s after 3 attempts: %s"}'
                       % (UP, last)).encode()
            status, ctype = 502, "application/json"
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def end_headers(self):
        if not self.path.startswith("/v1/"):
            self.send_header("Cache-Control", "no-store")
        SimpleHTTPRequestHandler.end_headers(self)


class Server(ThreadingHTTPServer):
    request_queue_size = 128   # don't be the bottleneck ourselves
    daemon_threads = True


try:
    httpd = Server(("127.0.0.1", PORT), partial(Handler, directory=os.getcwd()))
except OSError as e:
    sys.stderr.write(
        "ax-ui: cannot listen on 127.0.0.1:%d (%s).\n"
        "       Something is already there. Find it with:  lsof -nP -iTCP:%d -sTCP:LISTEN\n"
        % (PORT, e, PORT)
    )
    raise SystemExit(1)
try:
    httpd.serve_forever()
except KeyboardInterrupt:
    pass
finally:
    httpd.server_close()
PY
