# API-USED — every route and field `ax-ui` consumes

This file exists so that a change in `modules/ax-server/` that breaks the UI is
**traceable**. If you rename or remove anything listed here, this UI is affected.
Everything below was read out of `modules/ax-server/src/ax_server/api/` — nothing
was guessed.

The UI also echoes this at runtime: the **data sources** tab lists every request it
made, the HTTP status it got, and every field group it could not get. A route that
404s degrades to a visible *"not available"*; it never degrades to a zero.

Verified against `ax-server` as of 2026-09-13 (`api/http.py`, `api/service.py`,
`analysis/models.py`, `analysis/engine.py`, `collector/buckets.py`,
`store/sqlite_store.py`).

---

## 1. Routes

| Route | Query params sent | Used for | If it fails |
|---|---|---|---|
| `GET /v1/builds` | — | build picker | **fatal**: banner says "no data at all — a failed request, not an empty codebase"; nothing is drawn and the canvas says so |
| `GET /v1/builds/{sha}/summary` | — | caveat strip (edge sample rate, phases, windows, probe mask), verdict counts, precision posture, revocations | caveat chips read *not available*; counts section omitted |
| `GET /v1/builds/{sha}/instrumentation-gaps` | `limit=20000` | **class enumeration** (the only bulk route that names every class), eligibility totals, install diagnosis | graph may be missing classes entirely — reported as a problem, loudly |
| `GET /v1/builds/{sha}/verdicts` | `package=<prefix>` (one per covering prefix) and `class=<name>` for default-package classes | every method box: status, eligibility, reasons, phases, edge counts, probe mask | *"No verdict rows were returned"* banner; empty canvas is explained |
| `GET /v1/builds/{sha}/hot-methods` | `limit=5000`, `sinceDays=3650` | calls, errors, error rate, p50/p90/p99, per-method exception classes | every method shows *calls / latency: not available* |
| `GET /v1/builds/{sha}/hot-paths` | `limit=1500` | **the runtime call edges drawn on the graph** | **no runtime edge is drawn, and the caveat strip says the request failed** — never "no calls" |
| `GET /v1/builds/{sha}/coverage-windows` | — | per-window provenance, phase detail, observation span | window provenance reads *unknown* |
| `GET /v1/builds/{sha}/dead-candidates` | `limit=1000` | the dead-code view | *"There is no dead-code answer for this build"* |
| `GET /v1/builds/{sha}/agent-health` | — | every way an edge was lost; ring drops; skipped classes | section reads *not available* |
| `GET /v1/builds/{sha}/exception-classes` | `limit=50`, `sinceDays=3650` | build-wide exception class names (BUG #24) | *"type names are unknown; counts unaffected. Not 'no exceptions'."* |
| `GET /v1/suppressions` | — | the filter applied *before* any verdict (BUG #28) | *"you cannot see which suppression rules were applied … an unaudited filter, not an empty one"* |
| `GET /v1/builds/{sha}/proposals` | — | standing C53 proposals and revocations | section reads *not available* |
| `GET /v1/effective-false-positives` | — | C55 rule-class FP posture | section reads *not available* |
| `GET /v1/builds/{sha}/callers` | `class=`, `method=<name+desc>`, `limit=200` | detail panel: observed inbound edges | *"request failed — not an absence of callers"* |
| `GET /v1/builds/{sha}/callees` | `class=`, `method=<name+desc>`, `limit=200` | detail panel: observed outbound edges | *"request failed — not an absence of callees"* |
| `GET /v1/builds/{sha}/blast-radius` | `class=`, `method=<name+desc>` | detail panel: static callers, `readBeforeDeleting`; and the on-demand **static-edge overlay** (one call per visible method, capped at 400) | *"failed request, not an empty static graph"* |

### Route-shape assumptions the UI depends on

* `verdicts` **requires** one of `class=` / `package=` / `file=` (400 otherwise).
  There is no "all verdicts" route, so the UI derives a minimal set of covering
  `package=` prefixes from the class list returned by `instrumentation-gaps`,
  and then checks that every enumerated class actually came back. Classes that
  did not are reported as *absent from the graph*, not silently dropped.
* `callers` / `callees` / `blast-radius` take `method` as `name` **or**
  `name(descriptor)`, and **raise → 404 on an ambiguous bare name**. The UI
  therefore always sends `verdict.method + verdict.desc`.
* `hot-methods` returns a **bare JSON array**, not an object.
* `hot-paths` is a **ranked top-N**. When it returns exactly `limit` rows the UI
  says so: edges below the cut are not drawn and their absence means nothing.
* `instrumentation-gaps.byClass` is capped by `limit`; the UI compares
  `classesListed` against `classes` and reports any truncation.
* There is **no bulk static-call-graph route**. Static edges are only reachable
  per-method, inbound-only, via `blast-radius.static.callers`.
* Every response is `Content-Type: application/json` with **no CORS headers**
  (`collector/http.py: JsonHandler._respond`). That is why the UI must be served
  from the same origin — see `README.md` and `dev-serve.sh`.
* `summary.counts{}` **omits statuses with a zero count**. The UI renders the
  missing ones as `0` with an explicit note that the zero was inferred from the
  omission, not reported.

---

## 2. Fields

### `/v1/builds`
A JSON array of build-sha strings. An **empty array** is rendered as
*"no JVM has reported an observation window yet"*, never as an empty graph.

### `/v1/builds/{sha}/summary`
```
counts{}                                  -> verdict-count table (missing key => inferred 0, flagged)
windowDays                                -> fallback for the windows chip
phasesCovered[] phasesMissing[]           -> fallback for the phase chip
windows.stored                            -> evidence tab "did my data count?"  (BUG #22b)
windows.usableAsDeathEvidence
windows.excluded
windows.nonProduction
windows.nonProductionEnvironments{}
windows.degraded
windows.testTainted
windows.note
runtimeEdges.reported                     -> "tier never reported" vs a real sample rate
runtimeEdges.edgesSampleRates{}           -> the "1-in-N" printed next to every edge count
instrumentation.maskReported              -> bug #18 chip
instrumentation.maskSupportedByStore
instrumentation.observableButNeverInstrumented
revoked[][0] revoked[][1]                 -> C53 revocation list
precisionPosture                          -> quoted verbatim in the evidence tab
```
Not consumed: `buildSha`, `artifact`, `at`, `usableWindows`, `excludedWindows`,
`runtimeEdges.tierArmed`, `runtimeEdges.methodsWithObserved{Inbound,Outbound}`,
`runtimeEdges.note`, `instrumentation.classesWithMask`,
`instrumentation.stripMaskMissingTotal`, `instrumentation.note`.

### `/v1/builds/{sha}/verdicts` — one object per method (`Verdict.to_json()`)
```
class method desc                         -> box identity and label
status                                    -> the four colours; an unrecognised value is treated as UNKNOWN
reasons[]                                 -> detail panel, numbered, IN ORDER, rule prefix highlighted
windowDays                                -> detail panel
phasesCovered[] phasesMissing[]           -> detail panel + the "a DEAD_CANDIDATE cannot exist" note
lastSeen                                  -> detail panel (null => "never observed in an accepted window")
staticReachable                           -> TRI-STATE: null renders "unresolved", never false
runtimeReachable                          -> TRI-STATE: no false exists in the domain
runtimeInboundEdges runtimeOutboundEdges  -> method box + detail; gated on runtimeEdgesReported
runtimeInboundSampledObservations         -> printed as sampledObservations, NEVER as calls
edgesSampleRate                           -> the "@1-in-N" printed beside it
runtimeEdgesReported                      -> false => the zeroes above render as "no data"
suppressed                                -> box annotation + pill
publicApi                                 -> box annotation + pill
probeInstalled                            -> TRI-STATE (bug #18): false => "SILENCE, nothing could write this bit"
probeInstallMaskReported                  -> detail panel
eligibility                               -> hatched overlay for NO_PROBE_INSTALLED / DE_INSTRUMENTED
firstProposedAt revokedReason             -> detail panel
probeIdx                                  -> joins verdicts to hot-methods and to edge endpoints
entryPointKind                            -> detail panel
```

### `/v1/builds/{sha}/hot-methods` — array
```
class idx                                 -> join key (class + probeIdx)
method                                     -> unused (the verdict supplies the name)
calls errors                              -> method box + error rate; absent row => "not available", never 0
percentiles.p50 .p90 .p99                 -> method box + detail (see the unit note below)
bucketScheme                              -> detail panel, quoted
errorClasses{}                            -> per-method exception breakdown (BUG #24)
errorsAttributed errorsUnattributed
errorTypesAvailable errorTypesSource
errorReading                              -> quoted verbatim
```
**Unit caveat, surfaced in the UI:** the API does **not** declare a unit for the
percentiles. `Tier2Runtime` records `System.nanoTime()` deltas, so the UI
formats them as nanoseconds *and always prints the raw integer beside the
formatted value* so the reader can check. The value is the **upper bound of the
bucket the rank fell into**, not an exact quantile.

### `/v1/builds/{sha}/hot-paths`
```
paths[].caller.class .idx                 -> edge source (resolved to a rendered box)
paths[].callee.class .idx                 -> edge target
paths[].sampledObservations               -> edge label and stroke width. NEVER labelled "calls"
paths[].edgesSampleRate                   -> the "@1-in-N" beside every count
paths[].estimatedCalls                    -> shown only as "~N calls", order of magnitude
paths[].windows .firstSeen .lastSeen      -> read into the edge record (tooltip reserve)
```
Not consumed: `edgesSampleRate` (top level), `edgesSampleRates`, `tierReported`,
`countSemantics`, `rankingNote` — the equivalent facts come from `summary` and
from `callers`/`callees`, which carry them per method.

### `/v1/builds/{sha}/callers` and `/callees`
```
callers[] | callees[]                     -> the list (key differs per direction)
  .class .method .idx                     -> row label; a null method renders "(idx N — not in manifest)"
  .sampledObservations                    -> printed with the rate, never as calls
  .edgesSampleRate .estimatedCalls
observedEdgeCount
totalSampledObservations
edgesSampleRates{}                        -> "MIXED: 8 (2 window(s))" when more than one rate is in play
tierReported                              -> false => an explicit "these are no data, not zero"
countSemantics                            -> quoted verbatim under a populated list
absenceNote                               -> quoted verbatim when the list is EMPTY
```

### `/v1/builds/{sha}/blast-radius`
```
static.callers[].class .method .desc      -> static-edge endpoints and the detail list
static.callers[].resolution .semantics    -> shown per row (exact | cha | unresolved)
static.callerCount
static.graphSemantics                     -> quoted verbatim (the ~61%-unsound warning)
readBeforeDeleting                        -> quoted verbatim, in red, at the bottom of the panel
```
Not consumed: `verdict` (the UI already holds it), `runtime.*` (covered by
`callers`), `buildSha`, `class`, `method`, `idx`.

### `/v1/builds/{sha}/dead-candidates`
```
buildSha artifact count
windowDays phasesCovered[] phasesMissing[]
candidates[]                              -> list; each row uses class/method/desc/reasons/windowDays/staticReachable
instrumentation.maskReported
instrumentation.observableButNeverInstrumented
instrumentation.note                      -> quoted verbatim
posture                                   -> quoted verbatim, under the UI's own posture block
```

### `/v1/builds/{sha}/coverage-windows`
```
windowDays phasesCovered[] phasesMissing[] phaseDetail{} span[]
usable[] excluded[]  (each row:)
  windowId instanceId durationSeconds
  degraded environment production testTainted usableAsDeathEvidence
```
`production === false` is rendered as a red **"non-production — excluded from
liveness (`livenessEvidence:false`)"** pill; `degraded` as **"never death
evidence"**; `testTainted` as its own pill. The three are never merged.

### `/v1/builds/{sha}/instrumentation-gaps`
```
byClass[].class                           -> THE CLASS ENUMERATOR for the whole graph
classes classesListed                     -> truncation detection
methods installedIndices classesWithNothingInstalled
byEligibility.OBSERVABLE
byEligibility.NO_PROBE_INSTALLED
byEligibility.NOT_DYNAMICALLY_OBSERVABLE
byEligibility.DE_INSTRUMENTED             -> shown as FOUR separate counts, never summed
observableButNeverInstrumented
stripMaskMissing.total .windows
diagnosis                                 -> quoted verbatim
readingRule                               -> quoted verbatim
```

### `/v1/builds/{sha}/agent-health`
```
windows degradedWindows clockDegradedWindows
transformFailuresTotal ringDroppedTotal stripMaskMissingTotal
instances[] classesSkippedTotal{} note
edgeTier.windowsWithTierEnabled .sampledRoots .recorded .dropped
edgeTier.truncatedDepth .truncatedRoot .truncatedDistinct
edgeTier.tierFailures .tracesReaped
edgeTier.edgesSampleRates{} .note
```
A non-zero `edgeTier.dropped` gets its own red note: *"every drop is an edge you
are not seeing — which is exactly why a missing edge is never evidence of death."*

### `/v1/builds/{sha}/exception-classes`
```
supported                                 -> false => "this store does not implement Tier2ErrorStore"
errors attributed unattributed unresolvedIds
typesAvailable source
topClasses[].class .errors
distinctClasses classesListed windows
reading note                              -> both quoted verbatim
```

### `/v1/suppressions`
```
count countsByOrigin{} userRules defaultsEnabled
rules[].pattern .origin .source .line .comment
note                                      -> quoted verbatim
```

### `/v1/builds/{sha}/proposals`
```
class method desc firstProposedAt lastConfirmedAt active revokedReason
```

### `/v1/effective-false-positives`
```
definition
thresholds.probation .autoDisable
ruleClasses[].ruleKey .status .reports .judged .actioned .notUseful
ruleClasses[].effectiveFalsePositiveRate   -> shown only when `judged > 0`; a rate over zero judged
                                              reports renders as "unmeasured", never as 0.0.
                                              An EMPTY ruleClasses[] renders as "UNMEASURED",
                                              explicitly not as "zero false positives"
```

---

## 3. Things the UI deliberately does **not** do

* It never sums counts recorded at different `edgesSampleRate`s without
  labelling the result `@MIXED`.
* It never writes. Every request is a `GET`; the API has no write route and the
  one mutating operation (C55 feedback) is intentionally off the HTTP surface.
* It never merges `staticReachable` with `runtimeReachable`, and never merges a
  static edge with a runtime edge. They are separate fields, separate lists and
  separate stroke styles.
* It never renders a tri-state `null` (`staticReachable`, `runtimeReachable`,
  `probeInstalled`) as `false`.
* It does not attribute a filtered-out method's edge to an ancestor box. Only a
  **collapsed class** stands in for its own methods; anything else is counted as
  an unresolved edge and reported in the caveat strip.
