# auxin — PLAN v2 (post-validation)

Supersedes PLAN.md. Every decision here traces to a verdict in VALIDATION.md (A1-A14, E1-E2).
**GREEN LIT** for implementation.

## What changed from v1, in one table

| v1 said | v2 says | why |
|---|---|---|
| `static boolean[]` field probe | **condy (`ConstantDynamic`), no field** | A1/A10; proven in E2 |
| ByteBuddy `Advice` | **raw ASM, `ClassWriter(0)`, never COMPUTE_FRAMES** | A1; COMPUTE_FRAMES loads classes |
| read-then-store probe | **blind store** (5 insns, **no branch => no stack map frame**) | E2 finding + A2 |
| "<=2 ns/probe" | **p99/p99.9 on a multi-threaded macro benchmark + an inlining-regression test** | A2: lost JIT inlining dominates |
| probe index by visit order | **build-time, deterministic, from the manifest** | A14: silent misattribution |
| JaCoCo for branch coverage | **suspended**; our own probes if ever needed | A3: no method-only mode, no kill switch |
| "1 pod in N" rationing | **SUSPENDED pending our own A/B** | Picnic: 0.03% with `includes=` scoping |
| LongAdder + histogram on app thread | **one ring write; ALL aggregation on the drain thread** | A7/A8: dd-trace's model |
| `MpscArrayQueue<E>` | **hand-rolled `long[]` MPSC ring** | A7: `<E>` forces an allocation |
| HdrHistogram `Recorder` | **plain single-writer log-linear `long[1024]`** | A8: 4 contended atomics = 150-600ns |
| static call graph drives a deletion cascade | **corroborating signal only, no cascade** | A5: **61% of executed methods missing** |
| — | **de-instrumentation (Tier-1b)** | E2: steady-state cost -> zero |

## The tiers

| tier | scope | mechanism | cost posture |
|---|---|---|---|
| **1** | all methods in `ax.include.packages` (**default-deny**) | condy `boolean[]`, blind store, installed at load by a **retransform-INCAPABLE** transformer | target <= Picnic's 0.03%; package scoping is the dominant lever |
| **1b** | classes whose probes are all set | **retransform-CAPABLE** stripper removes probe instructions | steady state -> **zero** (proven, E2) |
| **2** | allowlist, ~50-200 boundary methods | app thread: **one `long` ring write**. Drain thread: counters + log-linear histogram | ~80ns/method, zero allocation, zero app-thread atomics beyond one CAS |
| **3** | branch coverage | **DEFERRED.** Re-derive from our own measurement | — |
| **4** | sampling profiler | optional, external (async-profiler/Pyroscope) | **budget 5%**, never feeds dead-code |

## The correctness rule (v2)

```
dead  = runtime-unobserved
      AND static-unreachable            (corroborating only; never a cascade)
      AND NOT suppressed                (repo-checked-in suppression file)
      AND NOT public-API-surface        (downstream consumers are invisible to us)
      AND window covers required phases (month/quarter/year-end, peak, DR drill)
      AND class was observed LOADED     ("never loaded" != "never invoked")
otherwise -> unknown
```
Never `<clinit>`. Never auto-delete. Always render the window and the phases it spanned.
Publish a **false-negative-biased posture**, never a precision number (ceiling is ~88%).

## Modules (strict one-way deps)

```
ax-manifest   (Java 8)  the shared contract: class -> methods -> probe index, schema-hashed
     ^                   depended on by ax-static AND ax-agent; depends on nothing
     |
ax-static     (Java 17) ASM scan: inventory, deterministic probe-index assignment,
                        entry points, corroborating call graph      -> emits manifest JSON
ax-agent      (Java 8)  ProbeInstaller(incapable) + ProbeStripper(capable),
                        condy probes, long[] MPSC ring, drain thread, HTTP egress
gt-collector  (Python)  ingest, merge (OR-union), reject schema mismatch loudly
gt-store      (Python)  port + SQLite adapter
gt-analysis   (Python)  the correctness rule above
gt-api/gt-mcp (Python)  query surfaces
bench         (Java)    JMH gates G1-G4
```

## Gates (must pass before the next step)

| gate | what |
|---|---|
| **G1** | JMH: blind store vs read-then-store vs hoisted-local, at >= #cores threads. Score latency **and** class-size/verification cost. |
| **G2** | **Inlining regression**: `-XX:+PrintInlining` diffed instrumented vs not on a small-method-heavy chain. `MaxTrivialSize=6`, `MaxInlineSize=35`. |
| **G3** | E2 re-proven on JDK 11/17/21 with real condy + strip. |
| **G4** | Startup: time-to-healthy on Spring PetClinic at 0.5 / 1 / 2 CPU limits. |
| **G5** | 3-agent harness (us + JaCoCo + OTel) under **traffic**, asserting all three fire simultaneously. |

## Non-negotiables in code

- Agent deps: shaded ASM only. No SLF4J, no Jackson, no Guava.
- **Fail-open everywhere.** A Throwable must never escape into app code. The JVM silently
  ignores transformer exceptions => ship `ax_transform_failures_total` and
  `ax_classes_skipped_total{reason}` from day one.
- Default-deny scope. Unset `ax.include.packages` instruments nothing.
- Startup CPU budget + circuit breaker (`ThreadMXBean` cumulative + wall clock).
- Three-level kill switch: agent / tier / package.
- ONE transformer pair, name-only matching in the first pass.
- Ignore list: copy OTel's `GlobalIgnoredTypesConfigurer` + `org.jacoco.`, `org.apache.skywalking.`.
- Never clear probe arrays in place; accumulate + delta against a shadow snapshot.
- Never rely on shutdown hooks (k8s SIGKILL runs none).

## Unsupported, stated up front
GraalVM native image (**impossible**). OSGi / WildFly / Spring Boot fat jar / JPMS: must be
**tested and claimed**, never assumed. JDK 24+ AOT cache (JEP 483) is **mutually exclusive** with
any classfile-rewriting agent — strategic risk, Datadog and Dynatrace already blocked.

---

# MEASURED RESULTS — gates G1-G4 (run locally, JDK 17, **aarch64 Apple M4**)

> **Hazard, repeated:** these ran on aarch64 (128-byte cache line, `mach_absolute_time`).
> Production is x86_64 Linux (64-byte line, vDSO/TSC, TSO vs ARM's weak ordering).
> **G1's headline IS a false-sharing result, and false sharing is exactly what does not transfer.**
> Class-file sizes, frame counts, the G2 crossing table and the G3 correctness proof DO transfer.

## >>> G1 REVERSES A PLAN-v2 DECISION: read-then-store wins

ns/op, 9 probes/op, all in one shared `boolean[9]` (one cache line):

| arm | t=1 | t=4 | **t=10** |
|---|---|---|---|
| baseline | 7.73 | 8.22 | 11.68 +/- 2.09 |
| **(a) blind store** (what PLAN-v2 chose) | 7.45 | 10.20 | **18.14 +/- 2.68** |
| **(b) read-then-store** | 7.92 | 8.44 | **10.70 +/- 0.33** |
| (c) read-then-store, array hoisted | 7.99 | 8.52 | 10.51 +/- 0.15 |
| (d) blind hoisted = **JaCoCo's actual shape** | 7.50 | 13.61 | **18.19 +/- 2.58** |

Per probe at 10 threads: **blind +0.72-0.77 ns; read-then-store ~0.** The blind arms are also the
unstable ones (blind swung 10.20 -> 12.26 across runs; read arms held +/-0.06). Single-threaded is
the *only* regime where blind wins, and there it wins by 0.02-0.07 ns/probe.

Cost side, measured: blind **+8 bytes/probe, 0 frames, +19.7% class size**; read-then-store
**+18 bytes/probe, 1 frame per probe site, +54.3%**. Load-time verification of 20,400 probed
methods: none 46.9 ms / blind 56.2 / read-then-store 68.4.

**=> The E2 no-frame argument for the blind store is REAL and CONFIRMED — it is simply an order of
magnitude smaller than the contention cost it was traded against.** ~+30 ms of one-time verification
CPU per 50k methods buys the removal of a permanent ~1.55x steady-state penalty on contended
classes.

### DECISION: **Tier-1 switches to read-then-store (arm b).** PLAN-v2's blind store is overturned.
Arm (c) buys nothing at Tier-1's one-probe-per-method density and cannot be applied to pre-compiled
methods without rewriting every existing StackMapTable frame — revisit only at >=2 probes/method
(branch coverage, deferred). Blind store would only be defensible if Tier-1b stripped fast enough
that steady state is never reached, which would make Tier-1 depend on Tier-1b's rate limiter.

## G2 — the A2 framing was half right

- **Stock C2 flags: ZERO methods stopped being inlined**, yet throughput fell **+29.6%**.
  HotSpot 17 judges a sufficiently-executed site frequent and raises the budget from
  `MaxInlineSize=35` to `FreqInlineSize=325`, swallowing the probe. Four getters moved
  `accessor` -> `inline (hot)` (5->13 bytes crosses `MaxTrivialSize=6`).
- **Lukewarm sites (`-XX:FreqInlineSize=35`): 2 methods stopped being inlined** and the penalty
  rose to **+47.7%**. So ~18pp is attributable to lost inlines where the frequency exemption
  does not apply.
- Statically, **11 of 32 methods cross `MaxTrivialSize=6`** with the blind probe.
- End-to-end at 10 threads: uninstrumented 283 -> blind **557 (+96.6%)** -> read-then-store
  **339 (+19.8%)**.
**=> The dominant cost in this harness is NOT lost inlining; it is the blind store's cache-line
invalidation. G1 and G2 agree, and both point the same way.**

## G3 — PASS on JDK 17, 26/26 assertions
Install -> correct probes flip (`1100001`) -> strip removes 7/7 -> **`beta()` called after the strip
records nothing** -> all methods still correct -> **no field or method added or removed** (verified
by reflection AND by ASM-walking installer vs stripper bytes). Zero transformer failures.

**NEW FINDING: the `CHECKCAST` is unnecessary on JDK 17.** A condy declared directly as `[Z`
verifies and works, taking the blind probe from 8 bytes to 5 — which directly shrinks the G2
crossing population. JDK-8216970 lived in 11, so keep the workaround behind a classfile-version
switch until 11 is actually tested. **Only JDK 17 was exercised; G3 is one third complete.**

## G4 — startup, and the A11 number
5,000 classes x 10 methods = 50,000 methods, median of 5 reps:

| config | class-load wall | whole-JVM CPU |
|---|---|---|
| OFF | 148.8 ms | 335.4 ms |
| **NAMEONLY** (prefix check, never parses) | **+0.3 ms (+0.2%)** | +17.0 ms (+5.1%) |
| FULL (name-only first pass, then probe) | +120.0 ms (+80.6%) | **+538.9 ms (+160.7%)** |
| PARSE (parse every class to decide) | +163.7 ms | **+793.0 ms (+236.4%)** |

Parsing the 258 classes we were always going to reject costs **+254 ms CPU** — half the cost of
instrumenting all 5,000 we want. **C13 (name-only first-pass matching) is quantitatively justified.**

**>>> The A11 number: FULL's CPU/wall ratio is 4.5x** (+539 ms CPU vs +120 ms wall) because
transform work parallelises across GC/JIT threads. **Under a 1-CPU cgroup that 539 ms of CPU
becomes ~539 ms of WALL.** That is precisely the throttling vector, quantified.

## Integration bug found and fixed during verification
`SchemaHash` diverged between `ax-manifest` (sorted the **concatenated** `name+desc` string) and
`ax-agent` (sorted the **tuple** `(name, desc)`). They differ whenever one method name prefixes
another (`'$'`=0x24 < `'('`=0x28), so `pick` vs `pick$inner` reversed. Effect: the agent's hash
would never match the manifest's, **every class silently skipped, coverage empty — indistinguishable
from "this code is dead."** Fixed in `ax-manifest` to tuple order (matching its own `ProbeIndex`),
verified identical across modules, and locked by `SchemaHashOrderingTest` (3 tests).

---

# GAP CLOSURE (post-build pass)

Three items previously reported as "environment-blocked" were **not actually blocked**.

## 1. G3 now covers JDK 11, 17 and 21 — was "one third complete"
`brew install openjdk@11 openjdk@21` (formula, no sudo) provided the missing JDKs. The bench jar
was compiled to class-file 61, so JDK 11 died with `UnsupportedClassVersionError` — the harness was
retargeted to `--release 11` (one `record` converted to a plain class) and now runs everywhere.

```
== G3 on OpenJDK 11.0.32.1 (aarch64) ==  G3 PASS   exit=0
== G3 on OpenJDK 17.0.18   (aarch64) ==  G3 PASS   exit=0
== G3 on OpenJDK 21.0.12.1 (aarch64) ==  G3 PASS   exit=0
```

### >>> OPEN QUESTION RESOLVED: the `CHECKCAST` is unnecessary on JDK 11 too
The raw `[Z` condy descriptor **works on 11.0.32, 17.0.18 and 21.0.12.1**. JDK-8216970 is not
reachable on any version we support, so the probe can drop `CHECKCAST` unconditionally for
classfile >= 55: **8 bytes/probe -> 5 bytes/probe**, which directly shrinks the G2
inline-threshold-crossing population. Keep the config flag for older 11.0.x point releases.

## 2. Kyverno policies CAN be tested without Docker — and two real bugs were found
`kyverno apply` (CLI 1.19.1, `brew install kyverno`) evaluates policies **without a cluster**.
The "no Docker" limitation was wrong, and the assumption cost us: the policy **did not load at all**.

| bug | status |
|---|---|
| **1. Invalid JMESPath** — `merge(x, {'key': v})` used QUOTED keys in a multi-select hash -> `SyntaxError`. 10 occurrences. | **FIXED** |
| **2. R10 CPU guard false-positives** — a pod with `limits.cpu: "2"` is refused with *"limits.cpu < 1"*. **As written it would refuse to inject on every pod**, silently disabling the product. | assigned |
| **3. PROCESS BUG — `spec.webhookConfiguration` MASKS load errors.** With it present, `kyverno apply` reports `Applying 0 policy rule(s)` and `error: 0` — indistinguishable from success. | assigned |

**The lesson is the point of this whole project:** `deploy/verify.sh` ran **133 static string checks
and passed every one** against a policy Kyverno could not load. Grep-checking YAML proves nothing
about whether the tool accepts it. `verify.sh` must shell out to `kyverno apply`, assert a non-zero
applied-rule count, and assert the **mutation results** — not the policy text.

Reproduction harness: `deploy/test/` (pods.yaml, values.yaml, README.md).

## 3. Integration bug found by cross-module testing
`SchemaHash` diverged between `ax-manifest` (sorted the **concatenated** `name+desc`) and `ax-agent`
(sorted the **tuple** `(name, desc)`). They differ whenever one method name prefixes another.
Effect: **every class silently skipped, coverage empty — indistinguishable from "this code is
dead."** Fixed to tuple order, verified byte-identical across modules, locked by
`SchemaHashOrderingTest`.

## Still genuinely blocked (machine limits, not choices)
- **x86_64 Linux** — G1 is a false-sharing result and false sharing is exactly what does not
  transfer from aarch64. `bench/run-gates.sh g1` is one command on a Linux box.
- **JDK 8 attach** — no JDK 8 available; the <55 field fallback is proven by bytecode path only.
- **A real cluster** — `kyverno apply` covers policy evaluation, but not CRD admission, Event
  generation, or actual pod creation.
- **G5 (3-agent coexistence under traffic)** — needs the agent + JaCoCo + OTel together.

## Gap closure, round 2 — completed

**Tier-1 switched to read-then-store** (the G1-mandated reversal) and the **`java.lang.$Auxin`
bridge** is implemented. Both verified on **JDK 11, 17 and 21**: 88/88 smoke checks x 4 configs
(unclassified / production / `probe.mode=blind` / `condy.descriptor=array`) plus 14/14 javap
bytecode-shape checks, on every JDK. 35/35 negative checks, `APP_OK=true` throughout.
**No existing assertion was weakened; the original 67+25 are intact and the counts grew by addition.**

Measured probe cost (javap, tier-1 only): blind **+8.4 B/probe, 0 frames**; read-then-store
**+18.3 B/probe, 8 frames for 9 probes** (one branch reused a frame the original code already had);
with condy `[Z` **+15.2 B/probe**. This matches G1's +8/+18 prediction.

### Four frame cases the design sketch did not cover (each would have been a VerifyError)
1. **A frame already at offset 0** (a method starting with a loop header) — two StackMapTable
   entries at one offset is illegal. The existing label becomes the `IFNE` target instead.
2. **Expanded frames** — a class carrying a tier-2 method is read with `EXPAND_FRAMES`, and ASM
   cannot mix `F_NEW` with compressed frames in one method.
3. **`<init>`** — in the expanded case local 0 is `UNINITIALIZED_THIS`, not the class. Naming the
   class is the classic constructor VerifyError.
4. **Class file version exactly 50** — keeps the blind store; a frame there makes HotSpot's
   type-checker fail over to the inference verifier.

### Two latent availability bugs found while wiring the bridge
- **Classes in an OSGi/JPMS/fat-jar loader were being given a condy whose bootstrap handle names an
  agent class** — that throws `BootstrapMethodError`/`NoClassDefFoundError` **inside a business
  method**. Such classes are now bridged, or skipped and counted `agentNotVisible`. Never broken.
- **Tier-2 emits a direct `Tier2Runtime.enter()` call** with the same resolution problem and no
  `Object.equals` trick available on a hot path. Bridge-mode classes now degrade to tier-1 and count
  `tier2NotBridgeable`.

### Deliberate deviation, and why it is right
The bridge is used **only for classes whose loader cannot resolve the agent**, not universally.
Universal use would give every class a static field plus a `<clinit>`, discarding the condy/no-field
decision AND Tier-1b strippability for the ~99% of hosts that delegate normally.

### New risk accepted, with a tested rollback
Read-then-store emits frames, and **a wrong frame is rejected by the JVM at class definition** —
a risk the blind store did not carry. `frameEmissionUnsupported` skips a single method on any
structural impossibility, and `ax.probe.mode=blind` is a kept, tested, one-flag rollback.

---

# FINAL STATE — all modules green

| module | verification | result |
|---|---|---|
| ax-manifest | 41 unit tests | pass |
| ax-static | 47 unit tests | pass |
| ax-server | 185 pytest | pass |
| ax-agent | 88x4 smoke + 14 bytecode + 35 negative, on **JDK 11/17/21** | pass |
| deploy | **60 LIVE (kyverno apply) + 158 static** | pass |
| demo | 33 ground-truth checks (measured via `-Xlog:class+load`) | pass |
| bench | G1-G4 executed; **G3 on JDK 11/17/21** | pass |

## Still genuinely blocked by this machine
- **x86_64 Linux** for G1 — false sharing is exactly what does not transfer from aarch64.
  `bench/run-gates.sh g1` is one command there.
- **JDK 8 attach** — no JDK 8 available; the <55 field fallback is proven by bytecode path only.
- **A real cluster** — `kyverno apply` proves policy evaluation and mutation, not CRD admission,
  webhook registration, `failurePolicy: Ignore` behaviour when Kyverno is down, or Event creation.
- **A real OSGi/JBoss/JPMS container** — the bridge is proven against a null-parent `URLClassLoader`,
  which reproduces the delegation property but not a real container.
- **G5** — 3-agent coexistence (us + JaCoCo + OTel) under traffic.


---

# BUG #18 — Tier-1b could never fire, and it invalidated the headline claim

**"Steady-state overhead reaches zero" was false for most real classes**, and the mechanism was
correct the whole time — it simply could never be reached.

`ProbeHolder` sizes a class's probe array to the manifest's `probeCount` (every probe-**eligible**
method). `ProbeEmitter` installs at only some of those indices: C51-exempt methods, methods whose
entry stack-map frame cannot be built safely (`frameEmissionUnsupported`), and all of them when
`ax.tier1.enabled=false`. **An index with no probe is never written by anything.** So the gate —
`CoverageSnapshot.allSet(wholeArray)` — was not slow to satisfy, it was **unsatisfiable for the life
of the JVM** for any class holding one such method. Those classes were never strip candidates and
kept their probes on the hot path for ever. **13 of 20 probed demo classes** were affected.

**Why it hid for the entire project:** every strip the suites exercised was a *forced* one
(`stripNow`), which bypasses the gate — and the deleted in-agent `ManifestTool` stand-in
under-detected C51-exempt methods, so the real exposure only appeared once the harnesses were
pointed at the real `ax-static`.

## The fix
An **installed-probe mask**: a manifest-sized `boolean[]` set at the one place that knows a probe
went in, registered *before* the probe array exists so no reader can see an array without its mask,
and OR-ed across loaders (child-first, OSGi) as the conservative merge. Written once per class at
transform time, read only by the drain thread — **nothing added to any per-call path.**
`allSet(live)` becomes `allInstalledSet(live, installed)`, which returns **false for an empty
installed set** so a class with nothing to strip is excluded rather than counted as a successful
strip (the G5-BUG-3 bookkeeping rule).

## Measured, JDK 11/17/21 identical
| | before | after |
|---|---|---|
| `classesAutoStrippedDuringTraffic` | 2 | **4** |
| `classesStripped` | 5 | **7** |
| `stripFailures` / `stripMaskMissing` | 0 | **0** |

The three gt-first orderings stay at 0 **by design** — JaCoCo has inverted our `IFNE` there so no
strip can succeed (G5-BUG-3, unchanged); `stripBlocked` rises to 15 = 5 eligible classes x 3 bounded
attempts, i.e. the pre-existing condition reported for more classes, not a new failure.

Suites: smoke 123->**145** per run x4, 119->**141**, 140->**162**, 121->**143**, javap 37/37;
negative 105->**106**; isolation **1146/0**; g5 **972/0**. No assertion weakened.

**The new assertion was mutation-checked**: reverting `allInstalledSet` to the whole-array test made
exactly the three auto-strip assertions fail (142/145) for the right reason, then it was reverted and
re-verified. It uses the drain thread, never `stripNow`.

## The second half: the payload was shipping unsettable bits as death evidence
See CONTRACTS §2 `coverage[].probesInstalled` and its reading rule. The C51 subset was already
recoverable via the manifest, but `frameEmissionUnsupported` and tier-1-off are per-JVM facts a
manifest cannot express — so a tier-1-disabled window read as "every method is dead."
