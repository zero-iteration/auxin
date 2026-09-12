# ax-ui — the auxin structure viewer

A single-page app that renders a service's structure as **nested rectangles
(package → class → method)**, wires them together with the **sampled runtime
call graph**, and colours every method box by its **verdict status**.

No npm. No build step. No CDN. No framework. Three files
(`index.html`, `style.css`, `app.js`), hand-rolled SVG layout and edge routing,
and nothing fetched from the internet — it works offline on a box with no
network, consistent with `ax-server` being stdlib-only.

```
index.html    shell, inline SVG canvas, caveat strip, legend, rail
style.css     all styling
app.js        API client, tree build, box packing, edge routing, panels
API-USED.md   every route and field consumed  <- read this before changing ax-server
dev-serve.sh  serve + proxy, for exercising the UI without touching the server
screenshots/  verification screenshots (see the bottom of this file)
```

---

## The one route the server needs

`ax-ui/` is static. Mount the directory at `/` (or at `/ui/`) on the same origin
as the API, and nothing else changes:

```python
# modules/ax-server/src/ax_server/__main__.py — after the two routers are joined
router = Router().extend(make_collector_router(collector)).extend(make_api_router(api))
router.extend(make_static_router(Path(__file__).parents[4] / "ax-ui"))   # <- the one line
```

**Same origin is a hard requirement**, not a preference: `JsonHandler._respond`
in `collector/http.py` emits only `Content-Type` and `Content-Length`, so there
is no `Access-Control-Allow-Origin` anywhere in `ax-server` and a cross-origin
page cannot read a single response.

`make_static_router` does not exist yet — it is the server owner's to add.
`Router.add` handlers return `(status, payload)` and `JsonHandler._respond`
always JSON-encodes, so serving CSS and JS needs the handler to be able to send
raw bytes. The smallest honest shape, stdlib only:

```python
# modules/ax-server/src/ax_server/api/static.py  (server owner's file, not ax-ui's)
from pathlib import Path
from ax_server.collector.http import Router

_TYPES = {".html": "text/html; charset=utf-8",
          ".js": "text/javascript; charset=utf-8",
          ".css": "text/css; charset=utf-8"}


def make_static_router(root: Path) -> Router:
    root = root.resolve()

    def serve(name: str):
        def handler(**_):
            path = (root / name).resolve()
            # Fixed allowlist, resolved and re-checked: no path component ever
            # comes from the request, so there is nothing to traverse with.
            if not path.is_file() or root not in path.parents:
                return 404, {"error": "not found", "path": name}
            return 200, (path.read_bytes(), _TYPES[path.suffix])
        return handler

    r = Router()
    r.add("GET", r"/", serve("index.html"))
    for f in ("index.html", "app.js", "style.css"):
        r.add("GET", "/" + f, serve(f))
    return r
```

…plus one branch in `JsonHandler._respond` to pass a `(bytes, content_type)`
payload through instead of JSON-encoding it. Mount the API router **first** so
`/v1/*` always wins; the UI only claims `/`, `/index.html`, `/app.js` and
`/style.css`.

If you would rather not add a static handler, the UI also accepts
`?api=<base-url>` — but that only helps when something else is already
same-origin-proxying the API (which is what `dev-serve.sh` does).

---

## Running it right now, without touching the server

```sh
cd modules/ax-ui
./dev-serve.sh 8080 8099      # UI on :8080, proxying /v1/* to the collector on :8099
open http://127.0.0.1:8080/
```

`dev-serve.sh` is stdlib `python3` (`http.server.SimpleHTTPRequestHandler`): it
serves this directory *and* reverse-proxies `/v1/*` to a running collector, so
the page and the API share one origin. It passes upstream status codes through
unchanged — a 404 reaches the UI as a 404, which is exactly what the
degrade-honestly paths need.

A bare `python3 -m http.server` is available as `./dev-serve.sh --static-only`,
but every API call will fail under it, for the CORS reason above. It is useful
only for eyeballing the "not available" rendering.

### End-to-end, from a cold repo

```sh
cd /path/to/auxin

java -jar modules/ax-static/target/ax-static.jar \
     --input demo/target/auxin-demo.jar \
     --build-sha uidemo --artifact demo --output /tmp/ui-manifest.json

(cd modules/ax-server && ./.venv/bin/python -m ax_server \
     --manifest /tmp/ui-manifest.json --db /tmp/ui.db --port 8099 &)

java -javaagent:modules/ax-agent/target/ax-agent.jar \
     -Dax.include.packages=io.auxin.demo \
     -Dax.manifest=/tmp/ui-manifest.json \
     -Dax.environment=production \
     -Dax.collector.url=http://127.0.0.1:8099 \
     -Dax.flush.interval.ms=4000 \
     -Dax.edges.enabled=true -Dax.edges.sample.rate=8 \
     -jar demo/target/auxin-demo.jar --seconds 14 --min-requests 5000

(cd modules/ax-ui && ./dev-serve.sh 8080 8099 &)
open http://127.0.0.1:8080/
```

Add a second agent run with `-Dax.environment=staging` to see the
non-production / `livenessEvidence:false` handling with real windows.

---

## Reading the screen

**Boxes.** A package box contains class boxes contains method boxes. Click a
class box to roll its methods up into a single box with aggregate counts; click
a package box to collapse or expand every class under it. Click a method box for
the detail panel. Drag to pan, wheel to zoom, `fit` to re-frame.

**Colour** is the verdict `status` — and the four are four different claims:

| | meaning |
|---|---|
| **LIVE** (green) | a probe bit was set. It ran. Positive evidence; sampling-independent. |
| **DEAD_CANDIDATE** (red) | every clause of the rule passed. A *proposal for human review*. |
| **UNKNOWN** (amber) | a probe existed and never fired: **never seen running, and not provably dead.** |
| **NOT_DYNAMICALLY_OBSERVABLE** (grey) | **could never have been observed** (C51). Its silence carries no information. |

**Hatching** over a method box means its silence is not evidence:
`NO_PROBE_INSTALLED` (bug #18 — no JVM ever installed a probe at that index, so
the zero bit is *silence*, not an observation) or `DE_INSTRUMENTED` (C4 — our own
tier-1b stripped the probe). Distinguishing these from "never ran" is the whole
point of bug #18, so they get their own texture and their own red note in the
panel.

**Edges.** Solid blue = **runtime**, from the sampled edge tier. Dashed purple =
**static**, from the manifest `callEdges`. They are never merged, because they
fail in opposite directions: the static graph over-approximates *and* was
measured ~61% unsound (A5), while runtime edges are sampled so **presence is
evidence and absence is not**. Static edges are loaded on demand from the
**data sources** tab (one `blast-radius` call per visible method, capped).

Every edge count is labelled `sampledObservations` with its `1-in-N` sample rate
beside it. It is never called `calls`. Where the API offers `estimatedCalls` it
is shown as `~N calls`, order of magnitude only.

**The caveat strip** under the toolbar is not dismissible. It always states the
edge sample rate (or that the tier never reported), which business phases have
not been spanned, how many windows are non-production or degraded, whether the
installed-probe mask is complete, and that the static graph is unsound.

---

## Degrading honestly

This project has found nine bugs whose signature was *"reports success while
doing nothing"*. The UI is written not to become the tenth:

* Every request carries `{ok, status}`. A failed route makes its section read
  **"not available"** or **"request failed — not an absence of X"**.
* **A missing field, a "we could not look" zero, and a real zero render as three
  different things.** `count(v, hasData)` prints *no data* rather than `0` when
  the surrounding evidence says nothing was measured — e.g. edge counts when
  `runtimeEdgesReported` is false.
* Tri-state booleans (`staticReachable`, `runtimeReachable`, `probeInstalled`)
  never render `null` as `false`.
* An empty canvas always explains itself: failed request, empty build list, or
  an active filter — never left ambiguous.
* Truncation is reported: `hot-paths` returning exactly `limit` rows,
  `instrumentation-gaps` listing fewer classes than it counted, enumerated
  classes that produced no verdict rows, edges whose endpoint is hidden or absent
  from the manifest.
* The **data sources** tab lists every request, its status, and everything the
  page could not get — including the routes that do not exist on older servers.

## Precision posture

The dead-code view leads with it, above the candidate list, where somebody about
to delete code will read it: auxin is **false-negative-biased** and **claims no
precision number**. In the closest published study roughly **1 in 3 flagged items
was genuinely deletable**; ~15% of removals approved by combined static *and*
dynamic analysis still broke on unseen executions (JShrink). Everything is a
proposal, re-derived and revocable on every request (C53). Nothing is ever
auto-deleted.

The same view shows the **suppression list** (BUG #28) — a default filter nobody
can enumerate is worse than the false positives it removes — and the
**installed-probe mask** state, because a short candidate list because nothing
was instrumented must not read as a short candidate list because nothing is dead.

---

## Screenshots

Taken against the real collector with real agent data (build `uidemo`,
`edges.sample.rate=8`), via the Playwright MCP tools.

| file | what it shows |
|---|---|
| `01-overview.png` | the whole build: nested package/class/method boxes, runtime edges, caveat strip, legend |
| `02-method-detail.png` | `OrderService#placeOrder` selected: 11 verdict reasons in order, phases, highlighted edges |
| `03-method-detail-reachability.png` | tri-state reachability, tier-2 "not available", live callers/callees with rates |
| `04-static-vs-runtime.png` | static callers with `resolution`/`semantics`, the unsoundness note, `readBeforeDeleting` |
| `05-collapsed-classes.png` | `collapse all`: classes rolled up with aggregate counts; "N edges inside collapsed boxes and not drawn" |
| `06-search-filter.png` | search + hide-non-matching; the caveat strip reports the edges the filter hid |
| `07-dead-code-posture.png` | the precision posture block and the phase blocker explaining a zero |
| `08-dead-code-suppressions.png` | the suppression list, and "zero candidates ≠ no dead code" |
| `09-evidence-windows.png` | `summary.windows` breakdown, per-window provenance, instrumentation gaps as four separate counts |
| `10-nonproduction-windows.png` | three real `staging` windows marked non-production and excluded |
| `11-data-sources.png` | every route, its status, and what is not shown and why |
| `12-static-and-runtime-edges.png` | both edge kinds on screen at once — solid blue vs dashed purple |
| `13-degraded-no-collector.png` | collector unreachable: every chip *not available*, canvas explains itself |
| `14-degraded-dead-code-tab.png` | the dead-code view with no data: "a failed request; it is not 'nothing is dead'" |
| `15-evidence-edges-and-exceptions.png` | edge-loss counters, inferred-zero disclosure, EFP shown as *unmeasured* |
