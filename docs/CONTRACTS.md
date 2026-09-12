# Module contracts — frozen interfaces

**Contract version 2** (was 1). Breaking changes in v2: §2 gained the C50 liveness gate and
`coverage[].frames`; §4 gained `NOT_DYNAMICALLY_OBSERVABLE`, `eligibility`, and the C53 proposal
fields. Agents implementing v1 must be updated.

These are the ONLY coupling points between modules. Implement against these, not against each
other's internals. Any change here is a breaking change and needs the version bumped.

---

## 1. Artifact manifest — `ax-static` -> `ax-agent`, `gt-collector`

File: `auxin-manifest.json`. Emitted at build time. Shipped alongside the agent.

```json
{
  "schemaVersion": 2,
  "buildSha": "abc123def",
  "artifact": "checkout-service",
  "generatedAt": "2026-09-12T10:00:00Z",
  "classes": [
    {
      "name": "com.acme.shipping.RateSelector",
      "sourceFile": "RateSelector.java",
      "probeCount": 7,
      "schemaHash": "5f2a...",
      "isPublicApi": false,
      "isTest": false,
      "methods": [
        { "idx": 0, "name": "pick", "desc": "(Ljava/util/List;)Lcom/acme/Rate;",
          "line": 47, "access": "public", "synthetic": false, "tier2": true,
          "dynamicallyObservable": true, "sccId": 91, "testOnlyReachable": false,
          "shortCircuitable": false }
      ]
    }
  ],
  "entryPoints": [ {"class":"...","method":"...","desc":"...","kind":"RequestMapping"} ],
  "callEdges": [ {"from":"C#m(D)","to":"C2#m2(D2)",
                  "resolution":"exact|cha|unresolved",
                  "semantics":"blocking|noop"} ]
}
```

**Rules (non-negotiable, from A14/C7):**
- `idx` is assigned by **sorting `(className, methodName, descriptor)` lexicographically** and
  numbering from 0 **within each class**. Deterministic across builds and machines.
- **Skip** synthetic and bridge methods, `<clinit>`, and abstract/native. They get no probe.
- `schemaHash` = SHA-256 of the class's sorted `(name+desc)` list. The agent refuses to probe a
  class whose computed hash differs; the collector refuses to merge a mismatch, **loudly**.
- Identity is `(buildSha, className, methodDesc)`. **Never** a hash of runtime class bytes.
- `callEdges[].resolution` must be honest. `unresolved` is a first-class value, not an omission.
- **Manifest `schemaVersion` is 2** (v1 gained six fields: `isTest`, `dynamicallyObservable`,
  `shortCircuitable`, `sccId`, `testOnlyReachable`, `semantics`). Readers MUST accept 1 and 2;
  every field absent in a v1 manifest defaults to its **safe** direction, which means a v1
  manifest nominates nothing. That is correct behaviour, not a bug.
- **`callEdges[].to` may name a TYPE, not a method**, in the form `com.acme.Bar#<type>`
  (RATIFIED). Collision-free: the JVM forbids `<` and `>` in method names other than `<init>`
  and `<clinit>`. Needed because `instanceof` / `.class` / catch-clause references are type
  references, and the C52 no-op catalogue is built from exactly those.
- `callEdges[].semantics` (C52): `noop` = the reference site can be constant-folded and therefore
  does NOT block deletion (`instanceof`, `.class` literals, `getClass()` comparisons, catch-only
  type references). Everything else is `blocking`. A binary graph over-blocks and destroys yield.
- `dynamicallyObservable: false` (C51) for single-instruction bodies, constant-returning accessors,
  foldable `static final` initializers, empty methods. TOSEM 2022: these "cannot be covered
  dynamically". **They must NEVER be counted as unobserved.** Absent field => assume `false` (safe).
- `shortCircuitable: true` (C59) when the method or its class carries `@Cacheable`/`@CachePut`/
  `@CacheResult`/`@Caching`, `@CircuitBreaker`/`@Retryable`/`@Recover`/`@HystrixCommand`/
  `@Bulkhead`/`@RateLimiter`/`@TimeLimiter`, or `@Transactional`. A Spring proxy can short-circuit
  **before the target body runs** — a warm `@Cacheable` method may never execute while the feature
  is heavily used. Absent field => assume `true` (safe). **Never a DEAD_CANDIDATE.**
- Runtime-generated classes are NEVER inventoried or probed. Exclude by name:
  `$$EnhancerBySpringCGLIB$$`, `$$EnhancerByCGLIB$$`, `$$FastClassBySpringCGLIB$$`, `$$Lambda$`,
  `$Proxy\d+`, `GeneratedMethodAccessor`, `GeneratedConstructorAccessor`, `ByteBuddy$`,
  `$MockitoMock$`, `_$$_jvst`. (jacoco#655: these grow agent memory without bound.)
- `testOnlyReachable` (C50) via Tarjan SCC over the call graph: true when a method's only non-test
  inbound edges lie inside its own library<->test SCC. **`null` = undecidable. Never emit `false`
  when unsure.**

---

## 2. Wire protocol — `ax-agent` -> `gt-collector`

`POST /v1/ingest`, `Content-Type: application/json`, gzip. One body per flush interval.

```json
{
  "schemaVersion": 1,
  "buildSha": "abc123def",
  "artifact": "checkout-service",
  "instanceId": "pod-7f3a",
  "windowStartMs": 1757671200000,
  "windowEndMs": 1757671260000,
  "agentHealth": { "transformFailures": 0, "classesSkipped": {"noManifestEntry": 12},
                   "ringDropped": 0, "clockNs": 26, "clockDegraded": false, "degraded": false,
                   "environment": "production", "livenessEvidence": true,
                   "testRunnerDetected": false },
  "classesLoaded": ["com.acme.shipping.RateSelector"],
  "coverage": [ { "class": "com.acme.shipping.RateSelector", "schemaHash": "5f2a...",
                  "probes": "AQAB" } ],
  "tier2": [ { "class": "...", "idx": 0, "calls": 1201, "errors": 3,
               "errorTypes": {"java.net.SocketTimeoutException": 3},
               "buckets": [0,0,14,881,306,0] , "bucketScheme": "loglinear-16-v1" } ]
}
```

- `probes` is base64 of the packed bitset, LSB = idx 0. **Accumulated, never reset** — the
  collector computes deltas.
- `classesLoaded` exists so **"never loaded" is distinguishable from "loaded but never invoked"** (C10).
- `agentHealth` is **mandatory**. A window with `degraded: true` must never be used as evidence
  of death.
- **CANONICAL SPELLING, PINNED:** the production classification is `agentHealth.environment`
  (a string). `agentHealth.livenessEvidence` and `agentHealth.testRunnerDetected` are booleans.
  Per-record test frames are `coverage[].frames` (optional array of strings). Accept no synonyms
  in new code; the collector may keep tolerant aliases for one version.
- **`livenessEvidence` (C50) is the test-liveness gate and is FAIL-CLOSED.** The agent sets it true
  only when `environment` is an explicitly production-classified value. Unset or unrecognised =>
  `false`. A window with `livenessEvidence: false`, or `testRunnerDetected: true`, is **discarded at
  ingest and counted** — never used as evidence that code is alive. Without this the product
  inverts: every *tested* method looks alive forever, so only *untested* code could ever be deleted.
- **Never send a percentile.** Buckets only; percentiles are computed in `gt-collector` (C31).

---

## 3. Store port — `gt-store`

Python ABC. SQLite adapter first; ClickHouse adapter later behind the same port.

```python
class Store(ABC):
    def record_window(self, w: IngestWindow) -> None: ...
    def merge_coverage(self, build_sha: str, cls: str, schema_hash: str, probes: bytes) -> None: ...
    def first_seen(self, build_sha: str, cls: str, idx: int) -> Optional[datetime]: ...
    def last_seen(self, build_sha: str, cls: str, idx: int) -> Optional[datetime]: ...
    def coverage(self, build_sha: str, cls: str) -> Optional[bytes]: ...
    def observed_windows(self, build_sha: str) -> list[Window]: ...
    def class_loaded_ever(self, build_sha: str, cls: str) -> bool: ...
    def tier2_buckets(self, build_sha: str, cls: str, idx: int, since: datetime) -> list[int]: ...
```

`merge_coverage` is **bitwise OR, never overwrite**. Mismatched `schema_hash` raises
`SchemaMismatch` — it must not silently merge or silently report zero.

---

## 4. Verdict model — `gt-analysis` -> `gt-api`

```python
@dataclass(frozen=True)
class Verdict:
    cls: str; method: str; desc: str
    status: Literal["DEAD_CANDIDATE", "LIVE", "UNKNOWN", "NOT_DYNAMICALLY_OBSERVABLE"]
    reasons: list[str]          # every rule that fired, in order
    window_days: int
    phases_covered: list[str]   # ["month-end","quarter-end",...]
    phases_missing: list[str]   # non-empty => can never be DEAD_CANDIDATE
    last_seen: Optional[datetime]
    static_reachable: Optional[bool]   # None = graph could not resolve; NOT False
    suppressed: bool
    public_api: bool
    short_circuitable: bool           # C59 - true => never DEAD_CANDIDATE (proxy may skip the body)
    eligibility: Literal["OBSERVABLE","NOT_DYNAMICALLY_OBSERVABLE","DE_INSTRUMENTED"]
    probe_idx: int                    # tier-2 lookup key
    entry_point_kind: Optional[str]   # C55 keying
    dynamically_observable: bool      # C51 - false => status is NOT_DYNAMICALLY_OBSERVABLE
    test_only_reachable: Optional[bool]  # C50 - None = undecidable, never treat as False
    first_proposed_at: Optional[datetime]  # C53 - a verdict is a standing claim...
    revoked_reason: Optional[str]          # ...silently revoked by any new observation
```

**A verdict is a STANDING CLAIM, not a snapshot (C53).** It must be re-derived on every analysis
run; any new observation or new static edge revokes `DEAD_CANDIDATE` and records `revoked_reason`.

**`UNKNOWN` is the default.** `DEAD_CANDIDATE` requires every clause of the PLAN-v2 rule to pass.

**`eligibility` is separate from `status`** so a **de-instrumented** method (C4/Tier-1b — probes
removed because coverage was already observed) stays distinguishable from one that **cannot be
observed at all** (C51). Conflating them would silently turn our own optimisation into false
evidence of death.
`static_reachable=None` (unresolved) must **never** be treated as `False` — A5 measured 61% of
executed methods missing from static graphs.

---

## 5. Suppression file — repo-checked-in (C46)

`.auxin/suppress.txt`, one entry per line, `#` comments.
```
com.acme.emergency.KillSwitch#*          # break-glass, exercised ~2yr
com.acme.batch.YearEndClose#*            # only runs in the year-end window
```
Matched before any verdict is computed. A suppressed method is `UNKNOWN`, never `DEAD_CANDIDATE`.
