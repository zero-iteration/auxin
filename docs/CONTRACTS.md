# Module contracts — frozen interfaces

**Contract version 3** (was 2). v3 is **additive only**: §2 gained `edges[]` and the
`agentHealth.edges*` counters for the SCOPE-v3 sampled runtime call-edge tier. Readers of v2 stay
correct — they ignore unknown keys and see an empty call graph — so the **wire `schemaVersion`
deliberately stays 2** (see the end of §2 for why bumping it would be the breaking change, not
the fix). Breaking changes in v2, for the record: §2 gained the C50 liveness gate and
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
          "tier2Reason": "entryPoint:GetMapping",
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
- **`tier2` is populated by ax-static, not hand-maintained.** Two selection paths, and the
  boundary one needs no configuration: (a) **every detected entry point** -- the `entryPoints[]`
  methods are the boundary, and under SCOPE-v3 this scan is the only source of TPS/rate/latency
  that will exist; (b) `--tier2 <glob>` over `fully.qualified.Class#method` (`*` within a package
  segment, `**` across), with `--tier2-exclude <glob>` applied after the includes and after the
  automatic entry points. A method is **never** tier-2 when it is not probeable (synthetic, bridge,
  `<clinit>`, abstract, native), when `dynamicallyObservable` is `false` (timing what cannot be
  covered is cost with no signal), or when its class `isTest`. `shortCircuitable: true` is
  **not** a bar -- that rule forbids a dead-code verdict, not a timing. Selection larger than
  `--tier2-max` (default 250; PLAN-v2 sizes the allowlist at ~50-200) **fails the scan with exit 3**
  and is never truncated: tier-2 costs ~80ns per call per method and that number must be chosen by
  a human.
- **`tier2Reason`** (additive, **no schema bump**): a string saying *why* a method is timed --
  `entryPoint:<kind>` (the kind written on the method wins over one propagated from its type) or
  `pattern:<the --tier2 glob, verbatim>`. **An absent value means not tier-2.** It is advisory
  provenance for a human reading a latency graph months later; `tier2` remains the only field a
  reader may act on, and the two always agree. Readers ignore unknown keys (see `ManifestReader`),
  so a producer that does not emit it stays valid.

---

## 2. Wire protocol — `ax-agent` -> `gt-collector`

`POST /v1/ingest`, `Content-Type: application/json`, gzip. One body per flush interval.

```json
{
  "schemaVersion": 2,
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
               "buckets": [0,0,14,881,306,0] , "bucketScheme": "loglinear-16-v1" } ],
  "edges": [ { "fromClass": "com.acme.web.RateController", "fromIdx": 2,
               "toClass": "com.acme.shipping.RateSelector", "toIdx": 0, "count": 17 } ]
}
```

- **`coverage[].probesInstalled`** (contract v3, additive, always present) — base64 bitset, same
  packing as `probes`, marking the indices where a probe was **actually installed in this JVM**.
  BUG #18: the probe array is sized to the manifest's `probeCount` (every probe-*eligible* method),
  but the emitter installs at only some indices. An index with no probe is **never written by
  anything**, so its bit is permanently zero — and it was shipped next to genuinely-zero bits as
  evidence a method never ran. Some skips are recoverable from the manifest
  (`dynamicallyObservable == false`), but the runtime-only ones are **not expressible in a
  manifest**: `frameEmissionUnsupported` is a per-JVM bytecode decision, and with
  `ax.tier1.enabled=false` every bitset is all-zero while every method stays observable — so a
  window from a tier-1-disabled JVM read as *"every method in your codebase is dead."*

  **>>> THE READING RULE:**
  ```
  probes                      -> liveness   (a set bit means it ran)
  probesInstalled & ~probes   -> the ONLY death evidence
  ~probesInstalled            -> SILENCE. Not evidence of anything.
  ```
  When the agent cannot determine the mask it ships an **all-zero** mask — losing a candidate,
  never inventing one. Preserve that bias. `agentHealth.stripMaskMissing` counts those cases.

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
  `false`. Without this gate the product inverts: every *tested* method looks alive forever, so only
  *untested* code could ever be deleted.

  **AMENDED by BUG #22b (field trial).** This previously said such a window is *"discarded at ingest
  and counted"*, and that was the single biggest barrier to anyone trying auxin: with
  `ax.environment` unset the collector 403'd **every** window, so a first run looked like total
  failure. Verbatim from the trial: *"I got 0 windows stored and had to mislabel a laptop as
  production to see anything."*

  A non-production window is now **stored, counted and explained** — and the safety property is
  **moved, not removed**. It must be withheld in **BOTH directions**, which is four merges, not one:

  | withheld | because it is evidence |
  |---|---|
  | `merge_coverage` | an observed probe => LIVE |
  | `merge_probes_installed` | decides whether an unset bit is *death* evidence (#18) |
  | `class_loaded` | its presence removes the C10 `class-never-loaded` blocker |
  | `record_edges` | an observed inbound edge => LIVE |

  The last two are the subtle half and the reason to spell this out: they make a `DEAD_CANDIDATE`
  **more** likely, so **a laptop that merely loaded a class could otherwise help manufacture a dead
  candidate for it.** Withholding only liveness would have left the dangerous direction open.

  Tier-2 telemetry (calls, errors, percentiles, exception classes) **is** stored from such a window:
  no rule reads it, so it cannot reach a verdict, and it is what lets a first-time user see the
  thing working. `testRunnerDetected: true` is still a hard reject — #22b softens only the liveness
  half. `--reject-unclassified` restores the old 403; `--allow-environments` extends the allowlist.
### `agentHealth` additions (v4, additive)
- **`tier2TimingMode`**: `full | sampled | disabled` (BUG #25). The field trial found the clock
  decision flipping between boots on one machine, and — worse — that its consequence was invisible.
  **`calls` and `errors` are counted EXACTLY in all three modes**; only the latency buckets are
  lost. *"The difference between 'tier-2 is dead on this host' and 'you still get counts'."*
- **`edgesStarvedOfSamples`** (BUG #26): the edge tier is armed but sampled zero roots this window,
  while tier-2 saw real calls. `tierArmed: true` with no edges reads as broken; this says why.

### >>> BUG #30: a slow clock must NOT degrade the window
`clockDegraded` used to call `Health.degrade(...)`, and `degraded: true` discards the **whole**
window as death evidence. But a slow clock affects **only the latency buckets** — coverage bits and
call counts never touch the clock.

Worse than over-broad. The field trial showed the clock decision is jittery near its threshold
(*"same machine, two boots: one disabled, the next sampled"*), so the coupling made **a boot-to-boot
coin flip silently discard a whole window's COVERAGE** — the substrate the entire dead-code verdict
rests on. Non-deterministic evidence loss for an unrelated reason.

Decoupled. Nothing is hidden: `clockNs`, `clockDegraded` and `tier2TimingMode` all still ship, so a
reader sees exactly what was lost — the percentiles, and only the percentiles. Same reasoning that
keeps `edgeTierFailures` out of `degraded`.

### >>> contract v4: `errorsByClass` + the per-window name table (BUG #24)
Field-trial finding: `Tier2Aggregator.MethodStats` keeps `errorCounts[]` by class id and `ErrorIds`
maintains the name table (1-254, 255 = overflow) -- but `Batch.java` wrote only the **scalar**
`errors`, and `decode.py` read only that. `grep -rn errorClass modules/ax-server/src/` returned
**zero hits**. So the README's *"what does it throw -- exception class names"* was never delivered:
you got a count, not a type. The data existed in the agent and was thrown away at the wire.

**Both halves of this change implement to THIS spec, not to each other.** That rule exists because
six bugs (#17, #18, #19, #21, #22, #23) were seams where each side was verified against a stand-in.

Per window, one shared name table (ids are window-local, NOT stable across windows):
```json
"errorClasses": {"1": "java.net.SocketTimeoutException", "2": "java.lang.NullPointerException",
                 "255": "<overflow>"},
"tier2": [{"class": "...OrderHandler", "idx": 3, "calls": 1201, "errors": 3,
           "errorsByClass": {"1": 2, "2": 1},
           "buckets": [0,0,14,881,306,0]}]
```
Rules, and a reader MUST enforce every one:
- `errorClasses` is **window-local**: id 1 in one window is unrelated to id 1 in the next. A reader
  resolves ids **within the window that carried them** and stores names, never ids.
- `sum(errorsByClass.values())` **may be less than** `errors`: the table holds 254 distinct classes
  and id **255 is the overflow bucket**, and `errors` is incremented unconditionally. A reader must
  never "reconcile" the difference by inventing a class -- report the remainder as unattributed.
- `errorsByClass` absent or empty with `errors > 0` is **legal** (an older agent, or the id table
  unavailable). Report `errors` and an explicit "types unavailable" -- never zero types.
- `errorClasses` keys are JSON strings (JSON has no integer keys). Parse to int; reject non-numeric.
- `schemaVersion` stays **2**: additive, and a reader that ignores unknown keys is unaffected.

- **Never send a percentile.** Buckets only; percentiles are computed in `gt-collector` (C31).

### `edges[]` — the sampled runtime call-edge tier (SCOPE-v3, additive)

Runtime caller->callee edges, **sampled once per entry into a tier-2 boundary method** and
aggregated on the drain thread. SCOPE-v3 removed "use OTel spans" as an answer to "what calls
what", so the agent now produces this itself; it is a single-process call graph and nothing else
(cross-process topology stays out of scope — context propagation needs a per-request allocation).

- **Both ends are identified exactly as `tier2[]` identifies its method**: `(class, idx)` from the
  build-time manifest. Never an agent-internal id, never a name+descriptor string.
- `count` is a **raw count of observations in sampled traces** in this window, additive across
  windows and pods. It is NOT a call count: multiply by `agentHealth.edgesSampleRate` for an
  estimate, and never present it as exact. No percentiles, no rates, no ratios (C31).
- The array is **always present**, and empty when the tier is off. An absent key and an empty
  graph are different facts.
- An edge is evidence of **liveness only**, and only under the same C50 gate as everything else in
  the window. Its absence is **never** evidence of death: it is sampled, depth-bounded,
  per-root-bounded and drop-on-full, every one of which is counted below. `edges[]` must never
  feed a `DEAD_CANDIDATE` verdict — it corroborates a live path, which is section 4's
  `static_reachable`-style role, not a substitute for a probe.
- Methods marked `dynamicallyObservable: false` (C51) carry no edge instrumentation, so a chain
  through a constant-returning accessor shows as a gap rather than as an edge.

New `agentHealth` counters (all raw counts, cumulative for the JVM like `ringDropped`, except
`edgesEnabled`/`edgesSampleRate` which describe the configuration the counts were produced under):

| field | meaning |
|---|---|
| `edgesEnabled` | the tier is armed in this JVM. **Default false** — absence of edges from a window with `false` here is a configuration fact, not an observation |
| `edgesSampleRate` | 1-in-N root entries traced (power of two; 1024 by default). **The counts in `edges[]` are uninterpretable without it** |
| `edgesSampledRoots` | root invocations actually sampled: the denominator |
| `edgesRecorded` | edge events folded into the aggregator |
| `edgesDropped` | edge events the ring rejected (drop-on-full, C27) |
| `edgesTruncatedDepth` | sampled root invocations that hit `ax.edges.max.depth`, one per invocation |
| `edgesTruncatedRoot` | sampled root invocations that hit `ax.edges.max.per.root`, one per invocation |
| `edgesTruncatedDistinct` | distinct edges refused because `ax.edges.max.distinct` was reached |
| `edgeTierFailures` | the tier latched itself off after an unexpected Throwable. **Deliberately not `degraded`**: coverage and tier-2 in the same window are still valid evidence |
| `edgeTracesReaped` | a trace gate was reset because it stayed up with no new edge. Non-zero means a trace was never closed |

### >>> BUG #17: this section's own example said 1 while its prose said 2
The agent has emitted `WIRE_SCHEMA_VERSION = 2` since the C50 liveness fields landed. The
collector's constant said **1** and `decode.py` refused anything `!= SCHEMA_VERSION`, so **every
window from the real agent was rejected by the real collector** — 100% data loss, reported as a
schema mismatch.

Neither side's tests caught it. The agent's smoke suite flushes to a throwaway HTTP listener that
accepts any body; the server's 250 tests used fixtures hardcoded to `1`, and one of them *pinned
`2` as a rejection* — encoding the break as correct behaviour. **Two components, each verified
against a stand-in for the other.**

Fixed: the collector now holds `SUPPORTED_SCHEMA_VERSIONS = {1, 2}` — a **set**, not a single
constant, because every addition so far has been purely additive and a reader that ignores unknown
keys decodes both. The refusal itself stays and still matters: an **unknown** version may carry a
different probe-index assignment (A14 defect 2), and accepting that silently is exactly how
coverage gets attributed to the wrong methods. Regression test pins both readable versions.

**`schemaVersion` is 2.** `edges[]` and the counters above are purely additive and a v2 reader
ignores unknown keys, while the collector refuses any `schemaVersion` it does not recognise
exactly (`decode.py`: *"schemaVersion N != 2; refusing to merge"*). Bumping the wire version to
announce an additive field would make every deployed collector drop every window — including its
coverage. The **contract document** version is bumped instead; that is what version 3 records.

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

### Optional port extensions
`Store`'s eight abstract methods above are **frozen** (a test asserts the set is exactly this).
Capabilities added since are separate ABCs, feature-detected with `isinstance`:
`WindowAttribution`, `IngestAudit`, and **`EdgeStore`** (runtime call edges: `window_edges`,
`edge_agg`, `edge_sample_rates`, callers-of / callees-of).

**Edge counts merge ADDITIVELY. Coverage merges as an idempotent OR.** Do not conflate them —
replaying one identical window must double the edge counts and leave the coverage bitset
untouched, and a test pins exactly that.

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
