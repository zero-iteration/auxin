# GATE G5 — three-agent coexistence, executed

**Verdict: ax-agent, JaCoCo and the OpenTelemetry javaagent DO coexist in one JVM under
traffic — in one `-javaagent` ordering. Getting there found five defects, three of which fail
silently, and one of which means the shipped agent jar cannot instrument this application at
all.**

```
./run-g5.sh                 # JDK 11, 17, 21 — 36 runs, 3m35s wall
==== G5 PASSED: 954 assertions, 234 recorded observations ====
```

G5 was previously deferred as "needs Docker". **That was wrong.** JaCoCo and the OTel javaagent
are ordinary jars on Maven Central, the demo app has no dependencies, and the whole gate runs in
three and a half minutes on a laptop. The deferral cost three real bugs.

---

## 1. Exact versions exercised

| component | coordinate / build | sha256 (first 16) | bytes |
|---|---|---|---|
| JaCoCo agent | `org.jacoco:org.jacoco.agent:0.8.13:jar:runtime` (`0.8.13.202504020838`) | `47e700ccb0fdb9e2` | 303,414 |
| JaCoCo CLI (report) | `org.jacoco:org.jacoco.cli:0.8.13:jar:nodeps` | `8f748683833d4dc4` | 600,668 |
| OTel javaagent | `io.opentelemetry.javaagent:opentelemetry-javaagent:2.31.1` | `bbf83c151b640070` | 25,107,554 |
| ax-agent (shipped) | `modules/ax-agent/target/ax-agent.jar`, snapshotted to `g5/out/ax-agent-shipped.jar` | `2d5b251bd74ddbd8` | 275,202 |
| ax-agent (G5-patched) | `g5/out/ax-agent-g5patched.jar` — the snapshot + one replaced class, see G5-BUG-1 | `713bf3f30273b162` | 275,138 |
| G5 order observer | `g5/out/g5-observer.jar` — passive 4th agent, modifies nothing | rebuilt per run | 12,787 |
| demo app | `demo/target/auxin-demo.jar` | `55019b7eb606bcb4` | 29,474 |

The `runtime` classifier IS the JaCoCo agent jar (`Premain-Class:
org.jacoco.agent.rt.internal_0e20598.PreMain`). Both were resolved with
`mvn dependency:get`; `run-g5.sh` re-fetches automatically if they are missing.

> **`modules/ax-agent` was being modified by other work in the tree while this gate ran**, and an
> early full matrix straddled two rebuilds of `ax-agent.jar`. `run-g5.sh` now **snapshots** the
> agent jar to `g5/out/ax-agent-shipped.jar` before anything starts and runs the entire matrix
> against the snapshot, printing its sha256 in the build banner and again in the summary; if the
> module's jar is rebuilt during a run it says so. The numbers in this document are one
> self-consistent matrix against snapshot `2d5b251bd74ddbd8`. All three findings below reproduced
> identically against the two earlier builds as well (`1da0821179520a12`, `675aff85adf610f6`) —
> none of the concurrent edits touched `IgnoreList`, the ignore-before-scope ordering in
> `ProbeInstaller`, or `ProbeStripper`'s `removed == 0 → return null`.

**JDKs, all three exercised, identical results except where noted:**

| label | version | notes |
|---|---|---|
| 11 | `openjdk 11.0.32.1` (Homebrew, aarch64) | demo recompiled `--release 11` (class-file 55) — the module targets 17, so its class-file-61 jar cannot load on 11 |
| 17 | `openjdk 17.0.18` Temurin-17.0.18+8 (aarch64) | demo jar as built |
| 21 | `openjdk 21.0.12.1` (Homebrew, aarch64) | demo jar as built |

The `--release 11` rebuild produces a **manifest identical to the release-17 build** — same 20
classes, same 86 probe indices, same `schemaHash` per class — so the fixture's ground truth
transfers exactly rather than approximately. That equality is checked by construction (both
manifests are generated from `ManifestTool` and the JDK-11 runs assert against their own).

Agent config for every run: `ax.include.packages=io.auxin.demo`,
`ax.environment=production`, `transport.enabled=false`, `probeMode=readthenstore`,
`condyDescriptor=object`, strip enabled, bridge installed.
JaCoCo: `includes=io.auxin.demo.*`.
OTel: `otel.traces.exporter=logging`, metrics and logs exporters `none`.

---

## 2. What "under traffic" means here

Every assertion is made **after** traffic, never at startup — the OTel/SkyWalking conflict in
A6 was invisible until the first request triggered a retransform.

Per run: 2,000 `POST /orders`, 200 `GET /healthz`, 9 `GET /admin/status`, 1 `GET /admin/rebuild`
= 2,210 requests over `HttpURLConnection` against an in-process `com.sun.net.httpserver`,
4 driver threads, `--max-requests=2000` so the fixture is bit-for-bit reproducible. 14 of those
are deliberate 500s (the every-137th error path). OTel emits **4,430 spans** per run (2,215
`java-http-server` SERVER + 2,215 `http-url-connection` CLIENT).

Then, in the same JVM: ax-agent window flushed → JaCoCo dumped in-process via
`org.jacoco.agent.rt.RT.getAgent().getExecutionData(false)` → Tier-1b experiment →
**a second burst of 5 requests on `/g5postcheck`** so the span log can be asked whether OTel is
still instrumenting *after* a strip and a foreign retransform. It is.

---

## 3. The gate

`ALL3.simultaneous` is asserted only where all three agents are expected to work, and it is a
conjunction — two of three is a FAIL by construction.

| config | `-javaagent` order | JDK 11 | 17 | 21 |
|---|---|---|---|---|
| `all3-A` | jacoco, **gt**, otel, obs | PASS | PASS | PASS |
| `all3-no-observer` | jacoco, **gt**, otel (no 4th agent at all) | PASS | PASS | PASS |

```
PASS  ALL3.simultaneous     gt=True jacoco=True otel=True -- one JVM, under traffic
```

Broken out, from `out/runs/17/all3-A/assertions.tsv`:

| assertion | result |
|---|---|
| `GT.classesInstrumented` | 17 (all loaded demo classes) |
| `GT.transformFailures` | 0 |
| `GT.noUnexpectedSkips` | only `noManifestEntry: 1` (the `Repository` interface) and `notDynamicallyObservable: 2` (C51: `MaintenanceWindow.start/stop`, empty bodies) |
| `GT.deadMethodsUnset` | **13/13** fixture dead methods read as never-invoked |
| `GT.rareMethodsSet` | **6/6** fixture rare methods read as invoked |
| `GT.neverLoadedAbsent` | `AuditRepository`, `DeadUtils`, `LegacyReportService` contribute no probe array at all |
| `GT.loadedButNeverInvoked` | `MaintenanceWindow` present in `classesLoaded`, zero methods invoked (the C10 distinction holds) |
| `GT.syntheticNotProbed` | 5/5 bridge+synthetic methods have no manifest entry and no probe |
| `JAC.noClassIdMismatch` | clean — `Analyzing 20 classes`, no warnings |
| `JAC.classesWithCoverage` | 17 demo classes carry covered instructions |
| `JAC.deadMethodsUncovered` / `JAC.rareMethodsCovered` | 13/13 and 6/6 — JaCoCo independently reproduces the fixture |
| **`JAC.agreesWithGt`** | **71 methods compared, 0 disagreements** |
| `OTEL.spansEmitted` | 4,430 |
| `OTEL.serverSpans` / `OTEL.clientSpans` | both instrumentations fired |
| `OTEL.spansAfterStripAndRetransform` | 10 spans on `/g5postcheck` |
| `OTEL.noErrors` | none |

`JAC.agreesWithGt` is the strongest single result in this gate: **two independent coverage
engines, instrumenting the same classes in the same JVM at the same time, agree on all 71
comparable methods** (the 86 manifest probes minus `<clinit>`/never-loaded/C51-exempt).
No direct JaCoCo+OTel conflict report existed in the literature (A6: "absence of evidence
only"). It now has evidence.

---

## 4. Transformer ordering — measured, not inferred

A passive fourth agent (`g5obs.OrderObserver`) registers **two** transformers in one `premain`
and modifies nothing: `OBS-INCAPABLE` via `addTransformer(t)` and `OBS-CAPABLE` via
`addTransformer(t, true)`. It records the byte array each one is handed and scans it for
`$axProbes`, `$jacocoData`/`$jacocoInit` and `io/opentelemetry`.

### 4.1 Capability dominates `-javaagent` order — the two-line proof

`all3-B-reversed`, order `obs, otel, gt, jacoco`. The observer is listed **first**, so
`OBS-INCAPABLE` is the first transformer registered anywhere in the JVM:

```
phase              transformer     class                               redef  bytes  gt jacoco otel
load-and-traffic   OBS-INCAPABLE   io/auxin/demo/model/Customer  false   1065   0    0     0
load-and-traffic   OBS-CAPABLE     io/auxin/demo/model/Customer  false   2131   1    1     0
```

1,065 bytes is the untouched class file. Both transformers were registered in the **same
`premain`, before JaCoCo's and before ax-agent's** — and the capable one still received their
output. That is the `ClassFileTransformer` guarantee, executed.

Same run, the complement: `OBS-CAPABLE` was registered **before** OTel's transformer, and
`otel=0` everywhere — so within the capable group the order is registration order
(`ORDER.capableGroupIsRegistrationOrdered`).

### 4.2 OTel is in the capable group, and runs last

`all3-A`, order `jacoco, gt, otel, obs` (observer last):

```
load-and-traffic   OBS-INCAPABLE   sun/net/www/protocol/http/HttpURLConnection  false  66414  0 0 0
load-and-traffic   OBS-CAPABLE     sun/net/www/protocol/http/HttpURLConnection  false  68714  0 0 1
load-and-traffic   OBS-INCAPABLE   sun/net/httpserver/HttpServerImpl            false   2671  0 0 0
load-and-traffic   OBS-CAPABLE     sun/net/httpserver/HttpServerImpl            false   3527  0 0 1
```

`ORDER.otelNeverSeenByIncapable` held in every run on every JDK: **no OTel-instrumented byte
array was ever handed to a transformer in the incapable group.** OTel's manifest declares
`Can-Retransform-Classes: true`; JaCoCo's declares nothing (so `false`).

### 4.3 The observed order, on all three JDKs

```
  [ incapable group, in -javaagent registration order ]   then   [ capable group, in registration order ]
     JaCoCo CoverageTransformer                                     ax-agent ProbeStripper
     ax-agent ProbeInstaller                                        OTel (ByteBuddy, RETRANSFORMATION)
     OBS-INCAPABLE                                                  OBS-CAPABLE
```

**So VALIDATION A6's conclusion is confirmed: `ProbeInstaller` registering
`canRetransform=false` does put us ahead of OTel regardless of flag order, and our probes are in
the bytes OTel receives.** `ORDER.gtRunsBeforeCapableTransformers` passed 17/18 demo classes in
every run (the 18th is the `Repository` interface, which has no probe-eligible method).

### 4.4 Our probes survive OTel's retransform batch — and every other retransform

`BYTECODE.*` (javap, counting `Dynamic #n:$axProbes` LDCs on `Customer`, 6 probes):

| | `all3-A` (jacoco → gt) | `gt-before-jacoco` (gt → jacoco) |
|---|---|---|
| at load | gtProbes=**6** jacocoProbes=6 | gtProbes=**6** jacocoProbes=6 |
| after our Tier-1b strip | gtProbes=**0** jacocoProbes=6 | gtProbes=**6** ← nothing removed |
| after a foreign retransform | gtProbes=**6** jacocoProbes=6 | gtProbes=6 |

Coverage is correct in both, so the probes are never *lost*. See §6 for what the middle row
means.

---

## 5. Does `-javaagent` order matter? YES — but not for us

| ordering | our probes | OTel spans | JaCoCo report | Tier-1b strip |
|---|---|---|---|---|
| `jacoco, gt, otel` (`all3-A`) | correct | 4,430 | **correct, 0 warnings** | **works** |
| `jacoco, gt, otel` no observer | correct | 4,430 | correct | works |
| `obs, otel, gt, jacoco` (`all3-B`) | correct | 4,430 | **0% — 17 classes "does not match"** | **silent no-op** |
| `gt, jacoco, otel` (`all3-C`) | correct | 4,430 | **0% — 17 classes "does not match"** | **silent no-op** |
| `gt, jacoco` (no OTel) | correct | — | **0%** | **silent no-op** |

Findings:

* **ax-agent's own coverage is order-insensitive.** 13/13 dead and 6/6 rare correct in *every*
  ordering, on every JDK. The condy probe is immune to whatever runs after it.
* **OTel is order-insensitive.** 4,430 spans, both instrumentations, in every ordering, with gt
  before it and after it on the command line.
* **JaCoCo is NOT.** And neither is our own Tier-1b.
* The two failures are **caused by gt/JaCoCo registration order alone** — reproduced with OTel
  absent (`gt-before-jacoco`), so OTel is not involved.

> **Deployment rule this gate establishes: ax-agent must be listed AFTER JaCoCo on the command
> line.** Since both are retransform-incapable, ordering inside that group is registration
> order, which is `-javaagent` order, which in Kubernetes is decided by `JAVA_TOOL_OPTIONS`
> append order (C39) — i.e. by whichever admission webhook mutates the pod second. That is not
> currently pinned anywhere, and it silently decides whether the customer's existing JaCoCo
> coverage keeps working.

---

## 6. Findings — five; three are silent failures, all reproduced on JDK 11, 17 and 21

### G5-BUG-1 — the shipped agent produces ZERO coverage on this application, silently

**Severity: highest. Found in the first five minutes; blocked G5 entirely.**

`IgnoreList.PREFIXES` ends with `"io/auxin/"`. It is there to stop the agent
instrumenting itself. It also rejects `io.auxin.demo.*` — the project's own integration
fixture, and, for any customer, any application sharing the vendor's package root.

Reproduced every run, all three JDKs, **with no other agent attached**:

```
PASS  G5-BUG-1.silentZeroCoverageReproduced
      classesInstrumented=0 coverage=0 classesSkipped={} transformFailures=0
      -- indistinguishable from 'all this code is dead'
```

The silence is the defect, not the prefix. In `ProbeInstaller.doTransform` the
`IgnoreList.ignored()` check sits **before** `scope.included()` and returns `null` without
touching any counter, so the wire carries `classesInstrumented: 0`, `classesSkipped: {}`,
`transformFailures: 0`, `coverage: []`. This is exactly the failure mode C37 exists to prevent:
*"without these we ship 40% coverage and call the rest dead code."* Here it is 0%, and the
product's answer would be "your entire service is dead code."

Suggested fix (not applied — G5 does not modify `modules/`):
1. narrow the prefix to `io/auxin/agent/` + `io/auxin/shaded/`;
2. **count it.** When a class passes `scope.included()` and is then rejected by the ignore list,
   emit `ax_classes_skipped_total{reason="ignoreList"}`. An in-scope class rejected by a
   name filter is precisely the event an operator must be able to see. (Do not count the
   `java/`, `jdk/`, `sun/` rejections — they are not in scope and would drown the signal.)
3. Consider refusing to start, or emitting `degraded=1`, when `include.packages` is non-empty
   and `classesInstrumented` is still 0 after the first flush window.

**Workaround used by this harness**, contained entirely in `g5/`:
`g5/src/patch/io/auxin/agent/instrument/IgnoreList.java` is a copy of the shipped class
with that one prefix narrowed; `run-g5.sh` compiles it and substitutes it into a **copy** of the
shipped jar (`g5/out/ax-agent-g5patched.jar`). Nothing under `modules/` is touched. Both jars
are run every time: the shipped one in the `shipped-gt-jar` config to keep the evidence live,
the patched one everywhere else.

### G5-BUG-2 — ax-agent registered before JaCoCo silently zeroes JaCoCo's coverage

**Severity: high. This is VALIDATION A3 failure 3 and A6, with us as the cause.**

```
PASS  G5-BUG-2.jacocoClassIdMismatchReproduced
      17 classes reported 'does not match', 0 classes carry coverage
INFO  G5-BUG-2.jacocoCoverageLost
      JaCoCo reports 0% for the whole application; nothing in its output says why
```

JaCoCo's class id is CRC64 of *the exact bytes it was handed*. With ax-agent registered first,
those bytes carry our condy probes, so `jacococli report --classfiles <the build's own classes>`
emits:

```
[WARN] Some classes do not match with execution data.
[WARN] For report generation the same class files must be used as at runtime.
[WARN] Execution data for class io/auxin/demo/http/handler/HealthHandler does not match.
   ... 17 classes ...
```

and produces **0% coverage for the entire application**. The `[WARN]` lines are the only signal;
the XML report itself looks structurally fine and simply says nothing is covered. A team with a
coverage gate in CI would see it go to zero with no error.

This is not fixable in ax-agent (JaCoCo's identity model is what it is). It is a **documented
ordering constraint and a deployment/admission-policy requirement**: ax-agent must register
after JaCoCo, and the Kyverno injector must guarantee it.

### G5-BUG-3 — Tier-1b de-instrumentation silently does nothing, and the metric lies

**Severity: high, and it is a new finding — E1/E2 could not have seen it, because they had no
second incapable transformer.**

```
PASS  G5-BUG-3.tier1bSilentNoOpReproduced
      strip reported success (stripNow=true, stripFailures=0, classesStripped+1)
      but the probe still fires: probeAfterCall=True
INFO  G5-BUG-3.classesStrippedMetricOverstated
      health.classesStripped=4 while at least one of those strips changed nothing
```

**Mechanism, from javap on the observer's byte dumps.** `Customer.name()`, JaCoCo registered
*after* ax-agent:

```
   0: ldc     Dynamic #1:$jacocoData     // JaCoCo hoists its array
   5: astore_2
   6: ldc     Dynamic #0:$axProbes       // our probe array
  11: astore_3
  12: aload_3
  13: iconst_4
  14: baload
  15: ifeq    26                <-- WAS ifne. JaCoCo INVERTED our branch,
  18: aload_2                       inserted its own probe in the fall-through arm,
  19: bipush  12                    and added a goto.
  21: iconst_1
  22: bastore
  23: goto    35
  26: aload_3
  27: iconst_4
  28: iconst_1
  29: bastore
  30: aload_2
  31: bipush  13
  33: iconst_1
  34: bastore
  35: aload_0
  36: getfield  name
  ...
```

`ProbeStripper.probeLength()` matches an exact instruction sequence ending
`... BALOAD IFNE ALOAD push ICONST_1 BASTORE L_end`. After JaCoCo the opcode is `IFEQ` and
JaCoCo's own store sits inside our branch, so **no probe matches, `removed == 0`, the stripper
returns `null`** — and then:

* `retransformClasses` returns normally (nothing threw),
* `stripNow` returns `true` because it only checks for an exception,
* `DrainThread` adds the class to `stripped` so it is **never retried**,
* `Health.classStripped()` increments.

Measured consequence: `gtProbes=6` before and `gtProbes=6` after the "successful" strip, the
never-invoked `Customer.name()` executed post-strip and **did** record, and
`classesStripped=4` on a run where at most 3 strips were real. The steady-state-cost-goes-to-zero
claim is false in this configuration and nothing reports it.

Suggested fixes (not applied):
1. `ProbeStripper` must return a **result**, not just bytes: report probes-found vs
   probes-removed. `DrainThread` should count `gt_strip_noop_total` and **not** mark the class
   stripped when zero probes were removed.
2. Match the probe **structurally** rather than by exact opcode sequence — anchor on the
   `ConstantDynamic` named `$axProbes` and the `BASTORE` to the same slot/index, tolerating
   foreign instructions and an inverted branch, or refuse and count rather than silently
   succeed.
3. This is also a second, independent argument for the blind store at Tier-1: it has **no
   branch**, so there is nothing for another transformer to invert. G1 overturned the blind
   store on contention grounds; this is a cost on the other side of that trade that was not in
   the ledger.

**Note the collision with G5-BUG-2.** Tier-1b needs ax-agent registered *before* other incapable
transformers (so nothing rewrites our branch). JaCoCo's class identity needs ax-agent registered
*after* them. **The two requirements are in direct conflict and cannot both be satisfied by
ordering.** Only fix (2) — or the blind store — resolves it.

### G5-FINDING-4 — `classesLoaded` is really `classesInstrumented`

```
INFO  G5-FINDING-4.absentFromClassesLoaded
      ['io.auxin.demo.repo.Repository'] -- no probe-eligible method, so never
      instrumented, so never reported as loaded
```

`WindowPayload.classesLoaded` is built from `ProbeHolder.instrumentedClasses()`. A class that is
loaded but has nothing probe-eligible — a pure interface, a class skipped for *any* reason —
never appears. C10 wants "loaded" to be a real, independent signal precisely so that "never
loaded" can be distinguished from "loaded but never invoked". As implemented, every skip reason
also removes the class from the loaded set, so a skipped class is indistinguishable from a class
the pod never loaded.

Harmless for `Repository` (no probes ⇒ correctly `UNKNOWN` either way), but it means the field
cannot carry the weight C10 assigns it. Suggested fix: populate `classesLoaded` from a
`ClassFileTransformer`-observed set of in-scope class names, independent of whether
instrumentation succeeded.

### G5-FINDING-5 — any retransform drops `-parameters` metadata on JDK 11 and 17

Not our bug, but Tier-1b makes it routine. Measured by reflection on the **live** class
(`Constructor.getParameters()[0].isNamePresent()`), not inferred from byte counts:

| JDK | before strip | after our strip | after a foreign retransform |
|---|---|---|---|
| 11 | `true` | **`false`** | **`false`** |
| 17 | `true` | **`false`** | **`false`** |
| 21 | `true` | `true` | `true` |

The `MethodParameters` attribute is present in the bytes at class load and absent from the
bytes HotSpot replays for retransformation on 11 and 17 — confirmed in the configuration where
*no transformer modified anything at all* (`gt-before-jacoco`: the stripper returned `null`, and
the attribute was still gone). Fixed by JDK 21.

Consequence: on JDK 11/17, any class ax-agent de-instruments loses its parameter names, which is
what Spring MVC parameter binding and Jackson's `ParameterNamesModule` read. Tier-1b
de-instruments **every fully-covered class** by default. Worth a supported-environment note and
a Tier-1b opt-out for apps compiled with `-parameters`.

---

## 7. The known-unhandled interaction, characterised

The brief called this out in advance; here is exactly what happens, identically on JDK 11, 17
and 21, with all three agents attached:

```
INFO  T1B.probesReappearedAfterForeignRetransform   True
INFO  T1B.gtReStrippedAfterwards                    False
INFO  T1B.classesAutoStrippedDuringTraffic          3
INFO  T1B.autoStrippedClass.reStripDelta            0
```

Sequence, all inside the three-agent JVM:

1. **Strip works.** `stripNow(Customer)` in the correct ordering removes all 6 probes
   (`gtProbes 6 → 0`), the class stays fully callable (`Customer.name()` returns `"Ada"`), and
   the never-invoked `name()` executes post-strip and records **nothing**. E2's result
   reproduced with JaCoCo and OTel present, on three JDKs.
2. **A foreign agent retransforms the class.** The observer agent — a genuinely separate
   `Instrumentation` — calls `retransformClasses(Customer.class)`. It succeeds.
3. **The probes come back.** The JVM replays `ProbeInstaller`'s cached load-time output as the
   input to the capable transformers; `ProbeStripper` is not armed for that class so it returns
   `null`. `gtProbes 0 → 6`. `anonymize()`, never invoked before, now records on the very next
   call.
4. **ax-agent does not notice.** `DrainThread.stripped` already contains the class, so
   `stripCoveredClasses` skips it forever. Waiting 2.5 s (≈12 drain cycles) produced no
   re-strip. The same holds for the 3 classes ax-agent auto-stripped during traffic:
   `reStripDelta = 0`.

So Tier-1b's "steady state → zero" is **not durable** in a multi-agent JVM: any third party's
retransform silently restores the permanent per-probe cost on that class, and ax-agent's own
accounting still says the class is de-instrumented. Correctness is unaffected (a re-installed
probe only ever sets a bit that was already set); **cost accounting and the zero-overhead claim
are.** This is the C32/C4 tension, and the fix is a re-arm on observed retransform, not a
one-shot `stripped` set — `DrainThread` should treat the `stripped` set as a hint and re-check
(or subscribe to a retransform signal) rather than a permanent decision.

---

## 8. Attribution matrix — nothing here is guessed

Every conclusion above is isolated by running the agents alone and in pairs, so no failure can
be blamed on the wrong component. `PASS/FAIL/INFO` counts per config, identical on JDK 11, 17
and 21:

| config | order | PASS | FAIL | what it isolates |
|---|---|---|---|---|
| `gt-only` | gt, obs | 25 | 0 | our probes + Tier-1b with nobody else in the JVM |
| `jacoco-only` | jacoco, obs | 14 | 0 | JaCoCo's own baseline against the fixture |
| `otel-only` | otel, obs | 14 | 0 | OTel's own baseline (4,430 spans) |
| `gt+jacoco` | jacoco, gt, obs | 32 | 0 | the two coverage engines, correct order |
| `gt+otel` | gt, otel, obs | 32 | 0 | us under OTel's retransform, no JaCoCo |
| `jacoco+otel` | jacoco, otel, obs | 21 | 0 | JaCoCo under OTel, us absent |
| `all3-A` | jacoco, gt, otel, obs | 40 | 0 | **the gate** |
| `all3-B-reversed` | obs, otel, gt, jacoco | 36 | 0 | ordering proof + G5-BUG-2/3 |
| `all3-C-gt-first` | gt, jacoco, otel, obs | 35 | 0 | G5-BUG-2/3 with OTel present |
| `all3-no-observer` | jacoco, gt, otel | 33 | 0 | **the gate with no 4th agent at all** |
| `gt-before-jacoco` | gt, jacoco, obs | 28 | 0 | G5-BUG-2/3 with OTel **absent** ⇒ OTel is not the cause |
| `shipped-gt-jar` | gt (unpatched), obs | 8 | 0 | G5-BUG-1 evidence |

Independent sanity assertions present in every run — four agents must not change what the
program does:

```
PASS  APP.exitZero               exit=0
PASS  APP.noDeadCountersFired    the fixture's own tripwires: none fired
PASS  APP.rareCountersMet        all met
PASS  APP.noDriverIoErrors       0
PASS  APP.warmupBranchExact      warmup.createCustomer=25 (expected 25)
```

The fixture's own in-app tripwires (`dead.cancelAll`, `dead.flagSuspicious`,
`dead.dumpThreads`) never fired under any agent combination, and the 25-iteration warm-up branch
ran exactly 25 times. So the ground truth we are asserting against was still true at runtime,
independently of our probes.

---

## 9. What did NOT work / was not covered — honestly

| item | status |
|---|---|
| **The shipped ax-agent jar** | Cannot instrument the demo app at all (G5-BUG-1). Everything in §3–§7 was measured with a one-class-patched copy built in `g5/`. The gate is therefore green **for the code**, not for the artifact as it stands today. |
| **Tier-1b with another incapable transformer** | Broken and silent (G5-BUG-3). Not worked around — reported. |
| **JaCoCo with ax-agent registered first** | 0% coverage, silent (G5-BUG-2). Unfixable by us; an ordering/admission constraint. |
| **JaCoCo `output=tcpserver`** | Not used. The in-process `RT.getAgent().getExecutionData(false)` dump is strictly better for this harness: it fires at a controlled moment, *before* the Tier-1b experiment invokes methods the fixture calls dead (which would otherwise pollute JaCoCo's data — it did, on the first attempt). `output=file,dumponexit=true` also runs and is written to `jacoco-exit.exec` for comparison, but is not asserted on. |
| **OTel via `otlp` to a local listener** | Not used; `otel.traces.exporter=logging` was sufficient and needs no listener process. Span *export* is therefore unproven; span *creation and completion* is proven (the logging exporter is called from the BatchSpanProcessor, so spans reached a real processor pipeline). |
| **x86_64 Linux** | Not available. All results are aarch64 macOS. Ordering, class identity, bytecode shape and the pattern-matching bug are architecture-independent; nothing in G5 is a timing or cache-line result. |
| **A framework (Spring Boot, Tomcat, Kafka)** | C41 specified "Spring Boot 3 + Tomcat + Kafka". The demo is deliberately plain Java — an unexplained gap there is a bug in the agent, not in a framework. So CGLIB proxies, `LaunchedURLClassLoader`, OTel's Spring/Kafka instrumentation modules and runtime-generated-proxy interactions are **still untested**. The OTel instrumentations that did fire are `java-http-server` and `http-url-connection`. |
| **Dynamic attach** | Out of scope (ax-agent deliberately refuses `agentmain`). |
| **JDK 8, JDK 24+** | No JDK 8 on this machine; JDK 24+ AOT (JEP 483) is a declared incompatibility, not something to test. |
| **`JAVA_TOOL_OPTIONS` vs command-line `-javaagent` precedence (A6b)** | Still open. G5 shows that ordering inside the incapable group is load-bearing (§5), which makes A6b more urgent, not less — but it was not tested here. |
| **A 4th real APM (Datadog, Dynatrace)** | Not tested. The observer is a passive stand-in for "a third party that retransforms", not for "a second APM that rewrites the same methods". |
| **A stable `modules/ax-agent`** | The module was being edited and rebuilt by other work while this gate ran; the first complete matrix straddled two builds of `ax-agent.jar` and had to be discarded. Fixed by snapshotting the jar (§1), which is the right behaviour for the harness regardless. If the module has changed since snapshot `2d5b251bd74ddbd8`, rerun before quoting these numbers. |

### One harness caveat, stated plainly

The JDK 11 runs use a `--release 11` recompilation of the demo sources (written to
`g5/out/demo11/`, nothing in `demo/` is modified) because the demo module targets release 17.
The generated manifest is identical to the release-17 one — same classes, probe counts and
schema hashes — but the class files are not the ones `demo/verify-auxin.sh` measured, so
the JDK 11 result inherits the fixture's ground truth by manifest equality rather than by direct
measurement.

---

## 10. Reproducing

```sh
cd g5
./run-g5.sh                 # JDK 11, 17, 21 — 36 runs, ~3m35s, exits non-zero on any FAIL
./run-g5.sh --quick         # JDK 17 only
./run-g5.sh --jdks=17,21
```

Artifacts per run in `g5/out/runs/<jdk>/<config>/`:

| file | contents |
|---|---|
| `cmdline.txt` | the exact `java` invocation, one argument per line |
| `stdout.log` / `stderr.log` | app output; agent logs; the OTel span log |
| `ax-window.json` | one ax-agent flush window (the wire format), captured right after traffic |
| `ax-window-2.json` | a second window after the Tier-1b experiment |
| `jacoco-mid.exec` | in-process JaCoCo dump taken before the Tier-1b experiment |
| `jacoco-exit.exec` | JaCoCo's own `dumponexit` file, for comparison |
| `jacoco-mid.xml`, `jacoco-report.log` | `jacococli report` output, including the mismatch warnings |
| `tier1b.json` | every Tier-1b / foreign-retransform measurement |
| `xform-events.tsv` | every transform event the observer saw: phase, transformer, class, `beingRedefined`, byte count, and the three agent markers |
| `dump/` | the raw class bytes handed to the observer at load, after our strip, and after the foreign retransform |
| `../../ax-agent-shipped.jar` | the pinned snapshot every run in the matrix used |
| `assertions.tsv` | `PASS/FAIL/INFO` for that run |

`g5/out/` is `.gitignore`d — it is ~300 MB, mostly span logs.

### Design of the harness

| file | role |
|---|---|
| `run-g5.sh` | resolves the agent jars, **snapshots** `ax-agent.jar` so a concurrent rebuild cannot split the matrix across two artifacts, builds everything, runs the 12-config × 3-JDK matrix, aggregates `PASS/FAIL/INFO`, and exits non-zero if any assertion failed. It deliberately does **not** stop at the first failure — an attribution matrix is only useful complete |
| `check.py` | the assertion engine. Decodes the ax-agent wire format against `demo/ground-truth.json`, runs `jacococli`, parses the span log, reads the ordering log, runs `javap` on the byte dumps |
| `src/g5/Harness.java` | the in-JVM half: runs the demo, flushes ax-agent, dumps JaCoCo mid-run, performs the Tier-1b + foreign-retransform experiment, then re-exercises HTTP so OTel can be re-checked afterwards. All reflection, compiled `--release 8`, so it loads next to class-file 55 or 61 demo classes on any of the three JDKs |
| `src/g5obs/OrderObserver.java` | the passive fourth agent: two transformers, one capable and one not, that modify nothing and record what they were handed |
| `src/patch/.../IgnoreList.java` | the G5-BUG-1 workaround, one prefix narrowed, substituted into a copy of the shipped jar |

Two rules the assertion engine follows, both deliberate:

* **Nothing passes by absence.** A missing input file is a `FAIL`, never a skip. "We could not
  tell" is graded the same as "it broke".
* **Discovered defects are pinned, not hidden.** The configurations that are known to break
  assert that they break *in exactly the documented way*
  (`G5-BUG-2.jacocoClassIdMismatchReproduced`, `G5-BUG-3.tier1bSilentNoOpReproduced`). The
  script is green while behaviour matches this document and goes red the moment it changes —
  **including when somebody fixes one of these bugs**, at which point the pin should be removed
  and turned into a real assertion. The bug numbers are in the assertion names so a green run
  can never be mistaken for a clean one.
