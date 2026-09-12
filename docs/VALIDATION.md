# Validation results — assumptions A1..A14

Status legend: CONFIRMED / PARTIAL / REFUTED / pending

| ID | verdict | one-line |
|---|---|---|
| A1 | PARTIAL | capability confirmed, but use **raw ASM**, not ByteBuddy `Advice` |
| A2 | **UNPROVEN** | no public benchmark either way — must test both patterns ourselves |
| A14 | PARTIAL | union is sound; **identity and index assignment are not** |
| A3,A4,A5,A6,A7,A8,A9,A10,A11,A12,A13 | pending | agents running |

---

## A1 — per-method constant index injection

**CONFIRMED as a capability.** ByteBuddy `Advice.withCustomMapping().bind(OffsetMapping.Factory)`
hands you the `MethodDescription` at transform time; `Target.ForStackManipulation.of(int)`
emits `ICONST/BIPUSH/SIPUSH/LDC` — byte-identical to JaCoCo's `InstrSupport.push()`.
Working production precedent: reactor BlockHound binds a per-method `int` this exact way.

**But the recommendation is raw ASM `MethodVisitor`**, optionally wrapped as a ByteBuddy
`AsmVisitorWrapper` to keep `AgentBuilder`'s matching plumbing:
- `Advice` always injects a `NOP` + a stack map frame at method start → class-file bloat
  across ~50k methods → slower verification at load (this feeds A11, the startup CPU risk).
- `@Advice.FieldValue` **cannot reference a field added in the same pass**
  (`IllegalStateException: Cannot locate field named ...`), so you end up hand-writing the
  `GETSTATIC` anyway.
- Advice + branching may force `ClassWriter.COMPUTE_FRAMES`, whose `getCommonSuperClass`
  **loads classes** → `ClassCircularityError` and heavy startup cost.
  **JaCoCo deliberately never uses COMPUTE_FRAMES. Neither will we.**

### RED FLAG the plan missed: retransformation cannot add fields or methods
> "The retransformation must not add, remove or rename fields or methods, change the
> signatures of methods, or change inheritance." — `java.lang.instrument.Instrumentation`

Implications:
- The **field-based** probe strategy (`$gt$probes` static field) works **only at initial
  class load via premain**. No dynamic attach, no retransformation rollout.
- The **condy** strategy (`ConstantDynamic`, class file >= v55 / Java 11+) adds **no field**,
  so retransformation IS legal. JaCoCo switched to condy for v55+ in 0.8.4 for this reason.
- Classes loaded before the agent installs are permanently out of scope and must report
  as **unknown**, never as dead.

---

## A2 — read-then-store vs blind store

**UNPROVEN. Demote from premise to experiment.** No JaCoCo issue, PR, or published JMH
compares the two for coverage probes. JaCoCo's blind store is justified in its docs by
*minimal bytecode + thread safety*, not by a measured multicore tradeoff.

Supporting theory (test-and-test-and-set): a plain load leaves the line **Shared**;
a store forces **Exclusive** (RFO). Measured line-transfer costs when a store must take a
line another core owns: Xeon Cascade Lake **48ns** same-socket / 136ns cross-socket;
EPYC Milan 23ns intra-CCX / 107ns inter-CCX. `boolean[]` is **1 byte/element** in HotSpot,
so **64 probes share one 64-byte line** — false sharing is guaranteed for any class with
more than one hot method. Wakart's JMH work measured up to 2.5x run-to-run variance from
false sharing on plain writes.

### The three costs the "2 ns" budget omitted
1. `GETSTATIC` on a **non-final** static is a real load + null check every call.
2. `BALOAD` **and** `BASTORE` each carry an **array bounds check** unless the array
   reference is a JIT constant. **Only condy (or a trusted `static final`) folds it away.**
3. **THE DOMINANT COST IS LOST JIT INLINING, NOT THE PROBE.**
   HotSpot inlines by bytecode size: `MaxTrivialSize=6`, `MaxInlineSize=35`,
   `FreqInlineSize=325`. JaCoCo's probe is 4-7 bytes; read-then-store with its own
   `GETSTATIC` is **~12-14 bytes**. Every getter crosses `MaxTrivialSize`; many methods
   cross `MaxInlineSize`. A lost inline also loses escape analysis and scalar replacement
   downstream — **tens to hundreds of ns**, ~100x the stated probe budget.
   **A JMH microbenchmark of an isolated probe cannot see this at all.**

### Prior-art calibration
**Codekvast**, a shipping product doing exactly this job, quotes **~15 ns per method
invocation** — 8x our original budget. Treat that as the honest baseline to beat.

---

## A14 — merge semantics, identity, memory

**Union is sound**: plain OR, order-independent, idempotent, commutative — safe across pods
and restarts. Per-class memory is fixed at class load (1 byte/probe + header); 50k methods
is ~50KB. Does not grow with traffic.

### But three correctness defects
1. **Do not key on a hash of runtime class bytes.** JaCoCo's class id is CRC64 of the raw
   class file and is "identical for the exact same class file only (byte-by-byte)". Another
   agent transforming first changes it → merges get **rejected or silently report 0%**.
   With OTel + APM + us in one JVM (see A6), agent ordering can differ per pod.
2. **SHARPEST FINDING — index assignment must be build-time and deterministic.** If `IDX` is
   assigned by enumerating methods in ASM visit order, an upstream agent adding a synthetic
   method (or a lambda-desugaring difference) **shifts every subsequent index**, and the
   union then silently attributes coverage to the wrong methods. Nothing in the original
   plan would have caught this.
3. **Runtime memory grows per *loaded class*, not per method.** Apps generating classes at
   runtime (`$$Lambda$`, `$$EnhancerBySpringCGLIB$$`, `GeneratedMethodAccessor`, Groovy,
   JSP) load classes indefinitely. Real precedent: a 188MB exec file consuming >2GB.
4. **"Not invoked" != "never loaded."** A class a pod never loaded contributes no array at
   all. Without an explicit per-class *loaded* record, the cascade mislabels lazily loaded
   code as dead.
5. **Never clear probe arrays in place on flush** (JaCoCo's `reset()` does, and loses
   concurrent sets). Accumulate forever, delta against a shadow snapshot.

---

## DESIGN CHANGES ACCEPTED (from A1/A2/A14)

| # | change |
|---|---|
| C1 | Tier-1 probes install at **initial class load via premain**. Field strategy => `canRetransform=false`. Pre-agent classes report **unknown**, never dead. |
| C2 | **Raw ASM `MethodVisitor`** for tier-1, not ByteBuddy `Advice`. Never `COMPUTE_FRAMES`. ByteBuddy only as optional `AsmVisitorWrapper` plumbing. |
| C3 | **Condy (`ConstantDynamic`) for class files >= v55**, field+init fallback below. Makes the array ref a JIT constant, kills the bounds check, and legalises retransformation. |
| C4 | **NEW Tier-1b: de-instrumentation.** Once a class's probes are all set, retransform to emit probe-free bodies. Steady-state cost -> **zero**, inlining restored. Precedent: SlipCover 180% -> 5%. Must be rate-limited under an explicit CPU budget (ties to A11). |
| C5 | **Rewrite the latency gate.** Drop "<=2ns/probe" — not measurable or defensible. Replace with (a) added p99/p99.9 on a multi-threaded macro benchmark at >= #cores threads, and (b) a mandatory **inlining-regression benchmark** with `-XX:+PrintInlining` diffed instrumented vs not. |
| C6 | **A2 is an experiment, not a premise.** The JMH gate must run blind-store vs read-then-store, with and without condy, and the design must accept either answer. |
| C7 | **Probe indices assigned at build time** by `ax-static-analysis` from a stable sorted key (class + method + descriptor), shipped in the artifact manifest, **looked up** at transform time. Skip synthetic/bridge members. Only defense against index drift. |
| C8 | **Identity = (build sha, class name, method descriptor) + per-class probeCount/schema hash.** Collector rejects mismatches loudly rather than merging. |
| C9 | **Bound memory against dynamic class generation.** Only track classes present in the build manifest; exclude generated-name patterns and non-application classloaders; hard-cap and fail open. |
| C10 | **Emit an explicit per-class `loaded` signal** so "never loaded" is distinguishable from "loaded but never invoked". |
| C11 | No padding, no per-thread striping. `boolean[]` at 1 byte/element is correct. |

---

## A3 — JaCoCo overhead / method-only mode / kill switch / offline / pod rotation
### VERDICT: PARTIAL — **both load-bearing sub-claims REFUTED**

**The "10%" figure is not a measurement.** It is one unsourced sentence on jacoco.org
(no workload, hardware, or date). The FAQ publishes no number at all. Project lead Marc
Hoffmann says **25%** on the mailing list, with one integration test going 2min -> 20min.
The only large-N peer-reviewed figure (iJaCoCo, 1,122 versions / 22 repos):
172.49s -> 300.57s = **+74.3%**. The 18% figure is a single blog post, n=1, throughput only.
=> **Plan against 25%+, and state that no production measurement exists.**

**REFUTED: JaCoCo has no method-only mode.** Probe density is not configurable. The full
option list (`includes, excludes, exclclassloader, inclbootstrapclasses, inclnolocationclasses,
sessionid, dumponexit, output, address, port, classdumpdir, jmx`) has no probe-density lever.
Method/line/branch/instruction are all **derived at report time from one probe set**. The only
lever is `includes`/`excludes` on whole classes. **Tier-3-via-JaCoCo as specified is impossible.**

Clean granularity measurement (ACVTool): class+method **+17% CPU / +11% size**;
class+method+instruction **+27% CPU / +249% size**. Method-only is ~37% cheaper on CPU and
23x cheaper on size — **and 17% is still four orders of magnitude outside our budget.**

**CONFIRMED: no kill switch, structurally.** `IAgent` exposes only
`getVersion/getSessionId/setSessionId/reset/getExecutionData/dump`. None stops probe
execution; `reset()` zeroes data while stores keep running. Root cause: JaCoCo **adds**
`$jacocoData` + `$jacocoInit()`, and `retransformClasses` forbids schema change — the same
constraint found in A1. This is also why JaCoCo cannot be dynamically attached.

**>>> THE DESIGN LANE, stated by the JVM itself.** JEP 520: *"When stopped, the injected
bytecode is removed."* The JVM supports **reversible** instrumentation for agents that avoid
schema changes. JaCoCo forecloses it. **We must not.**

**REFUTED for our constraint: offline instrumentation.** Removes the class-load transform
(**startup** cost only); emitted probes are byte-identical so **steady state is unchanged**.
Adds failure modes: requires `excludes=*`, exact version match between instrumenter and
runtime, breaks under Spring Boot `LaunchedURLClassLoader`/shaded jars/OSGi, JPMS
`module-info` collisions. Keep it as an **A11 mitigation**, not an A3 one.

**Competitive intelligence — nobody ships what our plan assumed:**
- **Azul Code Inventory**: method-granular, **no branches**. Uses the JVM's "first call"
  feature (not JVMTI, not BCI) on Azul JVMs; on other JVMs it's a plain `-javaagent` and
  their docs admit *"may consume up to 10% of your application's memory usage"*.
  **No published CPU figure.**
- **SeaLights production listener**: defaults to **2 minutes active / 58 minutes idle**
  (~3.3% duty cycle), flush every 20s.
- **JCov** is the only tool with a selectable method/block/branch knob. Near-zero adoption.
- **Drill4J** is JaCoCo's probes + a server. Not lighter.
=> **Market signal: Azul dropped branches; SeaLights dropped duty cycle. Our plan dropped
   neither. That is the gap we must close by construction, not by hoping.**

**Rotating-pod union — four hard failures + one statistical hole:**
1. Class ID = CRC64 of exact bytes; mismatch is *"reported with 0% coverage"* — **silent**.
   Even recompiling identical source can change bytes (javac lambda counter is per-javac-
   instance, JDK-8067422). Union is silently bounded to one build.
2. *"Can't add different class with same name"* when aggregating two builds.
3. *"Execution data for class X does not match"* — triggered by **another java agent ordered
   before JaCoCo**. Maintainers close these as `declined: otherproject`. **This is A6.**
4. **`dumponexit` is a shutdown hook. Kubernetes SIGKILL and OOMKill run none.** Data dies
   with the pod. Must use interval pull + budget in-JVM accumulation.
5. **>>> POD EXCHANGEABILITY IS A PRODUCT-CORRECTNESS HOLE.** 1/N sampling assumes pods are
   interchangeable. Sticky sessions, shard affinity, leader election, singleton/cron pods,
   region-pinned traffic each **systematically** hide live code — and the failure mode is
   **we tell someone to delete code that runs.**

Also: bytecode-vs-source mismatch — only **88.15%** of methods are seen by both JaCoCo and
Clover (6.43% JaCoCo-only, 5.42% Clover-only).

---

## A11 — startup transform CPU -> CFS throttling
### VERDICT: **CONFIRMED. Real, repeatedly documented, and our main mitigations are blocked.**

| agent | baseline | instrumented | delta |
|---|---|---|---|
| OTel javaagent, Spring Boot | 8.2s | 22.5s | **+174%** |
| OTel javaagent, single core | — | — | **~+40s** |
| Elastic EDOT Java | 5.55s | 6.82s | +23% |
| Elastic APM (1.17->1.26) | 12.5s | 20.4s | +63% over 9 minor versions |
| **custom ByteBuddy agent, ~15 transformers** | **30s** | **10 min** | **+20x** |

**The ByteBuddy case is the warning for our architecture**: each transformer independently
scanned the whole hierarchy. **Startup cost is dominated by matcher evaluation across every
loaded class, not by transforming the classes we keep.**

CPU spike shape (OTel #10752): **401% -> 436%, high CPU persisting 10-14 minutes**,
normalizing only after 15 min. Ten instrumented services starting together could reach 80%
of host resources.

k8s interaction confirmed: same app, varying only the limit — **2 cores -> 10-15s;
0.5 core -> ~40s**. Full incident at 0.896 cores: first-request latency ~10s, pod startup
~90s, 5xx at startup; after burstable QoS + TieredStopAtLevel=3 + startupProbe:
**750ms / 30s / no errors**. *"The throttling, not the application code, had been the
dominant cost all along."*

**>>> The self-reinforcing kill: the 1-second `livenessProbe.timeoutSeconds` default.**
Throttled pod misses the 1s probe -> restart -> backlog -> fail again -> CrashLoopBackOff,
*mathematically unlikely to recover*.

Kernel amplifier: k8s #67577 CFS over-throttling, open since 2018. Indeed's fix landed in
4.14.154+ / 4.19.84+ / 5.3.9+ / RHEL7 3.10.0-1062.8.1+ / RHEL8 4.18.0-147.2.1+. Preflight it.

### Mitigations — what actually works
| mitigation | verdict |
|---|---|
| **ONE transformer, name-only first-pass matching** | **YES — highest leverage.** ByteBuddy can reject by name without parsing the class file; any other property forces lazy parsing |
| **Burstable QoS (limits >= 2x requests)** | **YES — biggest deployment lever** (90s -> 30s) |
| `startupProbe` | YES, necessary but not sufficient |
| In-place CPU boost (k8s >=1.33) | YES, but **`REMOVE_LIMITS=true` is discouraged for JVMs**; pin `-XX:ActiveProcessorCount` |
| `-XX:TieredStopAtLevel=3` | PARTIAL — warmup only, costs peak throughput |
| **AppCDS** | **LARGELY BLOCKED** — needs `-XX:+AllowArchivingWithJavaAgent`; JVM warns *"testing purposes only... not... production"* |
| **Leyden AOT cache (JEP 483)** | **HARD-BLOCKED** — *"must not use JVMTI agents that can arbitrarily rewrite classfiles"*. **Datadog and Dynatrace are already incompatible. PetClinic 4.486s -> 2.604s (42%) on JDK 24 AOT — customers will have to choose between AOT and us.** STRATEGIC RISK. |
| Offline/build-time instrumentation | **YES — this is the A11 mitigation**, not an A3 one |
| Lazy transform (attach later + retransform) | **NO** — trades a startup spike for a mid-traffic deoptimization spike |
| Runtime kill switch in our agent | **YES — OTel proves it is normal practice** (3 levels of enable/disable flags) |

---

## A12 — sampling cannot prove absence / 2-3% overhead
### VERDICT: PARTIAL — absence CONFIRMED, overhead figure REFUTED

**Absence claim CONFIRMED by the best possible source** — Andrei Pangin, async-profiler
maintainer: *"If a method is missing in a sampling profile, it does not mean this method has
never been executed. In the cases when it is important to know... if a particular code path
is ever taken, instrumenting profilers come to the rescue."* Corroborated by JEP 520's goal
of *"complete and exact statistics rather than incomplete and inexact sample-based statistics"*.
**This is our answer to "why not just use a profiler?"**

**"2-3%" REFUTED.** async-profiler publishes **no number** (issue #14 "Measure and document
overhead" closed with no measurement). Pyroscope's own docs say 2-5%. Bechberger/DaCapo: ~6%,
7.5% with jfrsync. Burchell et al. (MPLR 2023, 14 benchmarks x 30 runs): **1-5.4% mean, worst
7.8%**, *"may be impractical for large long-running systems"*. The 0-2% production figure comes
from a workload that was **60% off-CPU** — CPU-mode overhead is proportional to cores actually
burning CPU. **Budget 5%.** Same paper shows a bimodal observer effect (15.17% spread across
runs) because profiling changed JIT decisions.

**AGCT stability is a real risk**: JDK-8178287 (99.8% stack-walk failure on arraycopy stubs)
**still open**; JDK-8283849 (Datadog: *"a non-trivial number of JVM crashes"*); JDK-8352649
(2025). **JEP 435, the official AGCT replacement, is Withdrawn.** Re-validate per JDK.

---

## DESIGN CHANGES ACCEPTED (from A3/A11/A12)

| # | change |
|---|---|
| C12 | **Do not use JaCoCo for Tier 3.** It has no method-only mode, no kill switch, 17-25%+ overhead, and CRC64 identity that breaks under agent stacking. **Build our own branch probes in the same single ASM transformer** — condy-based, no added fields/methods, therefore retransformable, therefore we get the runtime kill switch and de-instrumentation JaCoCo structurally cannot have. |
| C13 | **ONE `ClassFileTransformer`, one `AgentBuilder`, name-only matching in the first pass.** The 30s->10min regression came from N transformers each scanning the hierarchy. This is the single highest-leverage startup mitigation. |
| C14 | **Default-deny scope.** Unset `ax.include.packages` instruments nothing. |
| C15 | **Startup CPU budget + circuit breaker.** Track cumulative transform CPU (`ThreadMXBean.getCurrentThreadCpuTime`) and wall clock; on breach stop transforming, mark degraded, emit a counter, never throw. |
| C16 | **Three-level runtime kill switch** (agent / tier / package), following OTel's precedent. |
| C17 | **Never rely on `dumponexit`/shutdown hooks.** k8s SIGKILL and OOMKill run none. Interval pull only. |
| C18 | **Pod exchangeability caveat becomes product copy, not a footnote.** Pod-subset coverage is a sound *lower bound on live code*, **never evidence of death**. Sticky sessions, shard affinity, leader election, singleton/cron pods break it. |
| C19 | **Shipped manifests must set**: `startupProbe` (periodSeconds>=10, failureThreshold>=30, timeoutSeconds>=5); `livenessProbe.timeoutSeconds>=5` and `failureThreshold>=5` (**the 1s default is what causes CrashLoopBackOff**); burstable QoS `limits.cpu >= 2x requests.cpu`; raised `progressDeadlineSeconds`. **Refuse to inject, or warn loudly, when `limits.cpu < 1`.** |
| C20 | **Register the Leyden/AOT-cache incompatibility as a strategic risk.** JEP 483 forbids classfile-rewriting agents; Datadog and Dynatrace are already blocked; AOT gives 42% faster startup on JDK 24. Customers will eventually have to choose. Decide our answer before a customer asks. |
| C21 | **Publish our own startup benchmark** (time-to-healthy on Spring PetClinic at 0.5/1/2 CPU limits). Every vendor publishes nothing or one number. Cheap, real differentiation. |
| C22 | **A12 budget 5%, not 2-3%.** Add "AGCT stability re-validated per JDK" to supported-environment constraints. |
| C23 | **Preflight node kernel version** (>=4.14.154 / 4.19.84 / 5.3.9 / RHEL7 3.10.0-1062.8.1 / RHEL8 4.18.0-147.2.1) and alert on `container_cpu_cfs_throttled_periods_total` **during the startup window specifically**. |

---

## A4 — nanoTime cost
### VERDICT: PARTIAL — it IS the floor, but the cost belongs to the **clocksource**, not to nanoTime

`System.nanoTime` is a HotSpot intrinsic compiled as an `RC_LEAF` runtime call (no JNI frame,
no safepoint poll) bottoming out at `clock_gettime(CLOCK_MONOTONIC)`. **Nothing in pure Java
is cheaper.** But:

| environment | cost |
|---|---|
| Ubuntu / AWS c5, **tsc / kvm-clock** | **25-27 ns** |
| **AWS c3, Xen** | **367 ns (13-14x)** |
| same c3 forced to tsc | 24-25 ns |
| macOS | 44 ns latency, **1009 ns granularity** |
| Windows 7 | 14-15 us latency |

Shipilev: *"nanoTime is not dirt cheap; at best, you can hope for 15-30 ns per call."*

**Real production incidents:** Quarkus TechEmpower — `System.nanoTime()` was **9% of total CPU**;
root cause a BIOS bug marking TSC unstable, falling back to hpet. Brendan Gregg —
`os::javaTimeMillis()` was **32.1% of total CPU** on Ubuntu/Xen, 1.6% after switching to tsc;
production write latency dropped 43%.

**Budget impact:** 10 nanoTime calls/request at 25ns = 250ns (fits). **At 367ns = 3,670ns from
clocks alone — blows the 5us p99 ceiling with a single request.**

Alternatives all rejected: `currentTimeMillis()` is **not cheaper** (29ns) and is CLOCK_REALTIME
(non-monotonic, NTP jumps); rdtsc is **not faster** (JDK-8273453 closed Won't Fix); coarse
volatile-long clock (Log4j2 `CoarseCachedClock`, Agrona `CachedEpochClock`) reads in ~1ns but
its +/-1ms accuracy **destroys a sub-ms latency histogram** — valid for wall-clock stamping only.
**The only real win is calling it less often.**

All three APM vendors use the identical **anchored clock**: one wall-clock read at anchor, then
`nanoTime` deltas (OTel `AnchoredClock`, Elastic `EpochTickClock`, dd-trace `getTimeWithNanoTicks`).
**None of them avoids `nanoTime` per span.**

---

## A7 — JCTools MPSC + drop-on-full
### VERDICT: CONFIRMED as the industry pattern — **but the plan has an allocation hole**

`MpscArrayQueue.offer()` is lock-free (one CAS in a retry loop), allocation-free internally,
returns `false` when full. **Only `MpscArrayQueue` is acceptable** — `MpscChunkedArrayQueue`,
`MpscGrowableArrayQueue`, `MpscUnboundedArrayQueue` all allocate on the producer path (JCTools
wiki: *"Overflowing a chunk will lead to garbage... continuous allocation throughout the
application lifetime"*). **Do not copy Netty**, which uses the chunked variant for a different
problem shape.

Cost: ~15-25ns uncontended; **1P1C 14.6 ns/op -> 6P6C 189 ns/op = 13x degradation from
contention alone.** The 100ns transport budget holds only at low producer contention and must
be JMH'd at 8 and 16 producers, not 1.

Production precedent, all with drop-on-full:
- **OTel `BatchSpanProcessor`**: `if (!queue.offer(span)) { droppedSpanCount.incrementAndGet(); }`
- **dd-trace-java**: *"publishing to the buffer will not block the calling thread, but instead
  will return false if the buffer is full. **This is to avoid impacting an application thread.**"*
- **Elastic APM**: Disruptor, default `max_queue_size=512`, drops. *"This guards the application
  from crashing."*
- **Log4j2 is the counter-example** — its **default policy BLOCKS the calling thread** (docs warn
  of deadlock risk). **Copy its `Discard` policy, never its default.**

### >>> CRITICAL GAP: the compound record allocates
`MpscArrayQueue<E>` stores an **object reference**. `(methodId, durationNanos, errorFlag)` cannot
be enqueued without allocating an object (boxing does not help — `Long.valueOf` caches only
-128..127). **Section 4's "never allocate on the app thread" is incompatible with
`MpscArrayQueue<E>` as written.**

### >>> THE STRUCTURAL FINDING: Datadog does not record latency on the app thread at all
App threads only `offer()` a snapshot into an MPSC queue; **all** DDSketch recording happens on a
single `METRICS_AGGREGATOR` thread. The histogram is **single-writer by construction** and needs
no atomics. **Copy this. It independently eliminates every problem in A8.**

---

## A8 — histograms
### VERDICT: PARTIAL — mergeable and bounded CONFIRMED; **"cheap to record" REFUTED as specified**

| class | atomics per record | thread-safe |
|---|---|---|
| `Histogram` (plain) | **0** (plain array increment) — this is the 3-6ns figure | **NO** |
| `AtomicHistogram` | 2, one on a single shared volatile `totalCount` | yes |
| `ConcurrentHistogram` | 4 | yes |
| **`Recorder`** | **4 atomic RMWs, three on single shared fields** | yes |
| `SingleWriterRecorder` | 2, uncontended **if genuinely single-writer** | single-writer |
| **`DDSketch`** | — | **"not thread-safe" (class javadoc)**, and can allocate on resize |

**"Wait-free" != cheap.** Extrapolating JCTools' own 1P->6P degradation, **4 contended atomics at
8-16 app threads plausibly costs 150-600 ns per record** — against a 150 ns/method budget.
**This was the single biggest unvalidated risk in the plan.**

Memory (computed, reproduces HdrHistogram's published example exactly):
| config | per method | x200 |
|---|---|---|
| Hdr, ns units, 3 sig | 144.5 KB | 28.2 MB |
| **Hdr, us units, 1..60e6, 2 sig** | **20.5 KB** | **4.0 MB** |
| **DDSketch, 2%, 1us..60s** | 3.5 KB | 0.68 MB |
| **hand-rolled log-linear `long[1024]`** | **8 KB** | **1.6 MB** |

Datadog's actual production config: `new DDSketch(new BitwiseLinearlyInterpolatedMapping(1.0/128.0),
() -> new CollapsingLowestDenseStore(1024))` — 0.78% accuracy, **index by pure bit manipulation,
no `Math.log`**, <=8KB hard cap. The `DDSketches` javadoc says interpolated mappings exist
precisely *"because the logarithm may be costly to compute."*

**Trap to avoid:** OTel's default exponential histogram uses `Math.log` at scale > 0 (tens of ns),
and `DoubleExplicitBucketHistogramAggregator` does striped CAS + `LongAdder[]` + `DoubleAdder` +
two min/max CAS loops **and `Thread.yield()`-spins during collection.** Do not copy it.

**Never ship a per-pod p90.** Prometheus's own lesson: summaries are not aggregatable; histograms
are, because you sum bins then compute the quantile server-side.

---

## A6 — three agents in one JVM
### VERDICT: PARTIAL — **and the JVM's guarantee is in our favour**

`ClassFileTransformer` javadoc: transformations apply in order —
**retransformation-INCAPABLE transformers first**, then capable ones. Byte arrays chain.
**Capability dominates `-javaagent` flag order.**

| agent | `canRetransform` |
|---|---|
| **JaCoCo 0.8.x** | **false** (`CoverageTransformer`: `if (classBeingRedefined != null) return null;`) |
| **OTel javaagent** | **true** (`RedefinitionStrategy.RETRANSFORMATION` + `DECORATE` + `disableClassFormatChanges()`) |

So the order JaCoCo -> us (if incapable) -> OTel **is already guaranteed and already correct.**

Documented conflicts:
- **OTel + SkyWalking**: instrumentation **silently vanished** after the first request. Root cause
  named in merged PR #7916: a cached `TypeDescription` *"...if another agent adds an interface to
  the class then returning the cached description that does not have that interface would result
  in bytebuddy removing that interface."* **Invisible until traffic triggers retransform.**
- **OTel + Datadog**: `LinkageError`, *"loader 'app' attempted duplicate class definition"*.
- **Datadog official position:** *"Loading multiple Java Agents that perform APM/tracing functions
  is not a recommended or supported configuration."*
- **byte-buddy#1248**: `UnsupportedOperationException: class redefinition failed: attempted to
  delete a method` — because JaCoCo adds `$jacocoInit()`/`$jacocoData`.
- OTel hard-codes `datadog.`, `com.dynatrace.`, `com.appdynamics.`, `com.newrelic.agent.` into its
  ignore list. **`org.jacoco.` is NOT on that list.**

**Silent-failure vectors:** (1) a transformer that throws is **ignored by the JVM** and the load
continues — a failing transform is invisible; (2) JaCoCo's `filter()` skips any class with no
`CodeSource` unless `inclnolocationclasses=true` (default **false**), so **every runtime-generated
class is silently uncovered**.

---

## A9 — Kyverno injection
### VERDICT: PARTIAL -> CONFIRMED with changes

`failurePolicy`: **OTel Operator uses `Ignore`; SkyWalking uses `Fail`; Kyverno defaults to `Fail`.**

### >>> CRITICAL GOTCHA: `failurePolicy: Ignore` does NOT make you safe
From the OTel Operator's own source comment: *"By default, `admission.Errored` sets Allowed to
**false** which **blocks pod creation even though the failurePolicy=ignore**."* `Ignore` only
covers transport failure. A webhook returning HTTP 200 with `allowed: false` blocks pods anyway.
Real instance on a java-agent injection policy: kyverno#6873 — worked on 1.7.x, broke on 1.9.1.

**JAVA_TOOL_OPTIONS append is solved** — JVM TI spec: *"Multiple tools may wish to use this
feature, so the variable should not be overwritten, instead, options should be appended."*
Reference impl is the OTel Operator's `getDefaultJavaEnvVars()`: append to existing value, and
**refuse to inject if the env var uses `valueFrom`** (unreadable at admission time).

**SkyWalking's injector gets this WRONG** — it blind-appends a duplicate env entry, and the kubelet
resolves duplicates via a map (**last wins**), so it **silently discards the app's own
`JAVA_TOOL_OPTIONS`.** Do not copy.

Precedence (later wins): `JAVA_TOOL_OPTIONS` -> command line -> `_JAVA_OPTIONS`.
Use **`JAVA_TOOL_OPTIONS`** — the only one honoured by non-`java`-launcher JVMs (Tomcat, Jetty,
JBoss, embedded). `JDK_JAVA_OPTIONS` is launcher-only. `_JAVA_OPTIONS` is undocumented.
Note: the JVM itself prints `Picked up JAVA_TOOL_OPTIONS: ...` to **stderr** in every pod, and the
variable is read by **every JVM in the container** (maven, keytool, jshell, any wrapper). Disabled
under setuid.

---

## A10 — classloader isolation
### VERDICT: PARTIAL — **the plan is wrong in one important way**

**Elastic's invokedynamic is NOT what we need.** It solves *advice dispatch with arbitrary helper
code*. Our Tier-1 probe has **no helper code on the hot path**. (Still experimental in OTel:
`otel.javaagent.experimental.indy`.) Evaluate it separately for Tier-2 only.

### >>> THE ANSWER: JaCoCo's `java.lang.$JaCoCo` + `Object.equals` bridge
1. For classfile version **>= 55, JaCoCo adds NO field at all** — `CondyProbeArrayStrategy` emits a
   `ConstantDynamic` whose bootstrap method is a synthetic `$jacocoInit` **on the instrumented
   class itself**. Field+init (`ClassFieldProbeArrayStrategy`) is the **older, weaker, Java-8-era**
   mechanism and is the primary retransform hazard. **Section 4's `static boolean[]` bullet was
   the Java-8 design.**
2. **Exactly one** bootstrap class: `java.lang.$JaCoCo { public static Object data; }`, defined via
   `MethodHandles.privateLookupIn(Object.class, lookup()).defineClass(...)` after
   `Instrumentation.redefineModule` opens `java.lang` to the agent's isolated module.
3. The emitted call references **only** `java.lang.Object`, `Object.equals`, and an `Object` field
   in `java.lang`. `RuntimeData.equals(Object)` is **overridden** to stuff the `boolean[]` into
   `args[0]`. **That is why it survives OSGi, Spring Boot fat jars, JBoss Modules and JPMS —
   `java.*` is universally boot-delegated.**
4. **JaCoCo does NOT use a system property.** It abandoned `SystemPropertiesRuntime` because it
   *"breaks the contract that system properties must only contain `java.lang.String` values."*

Known breakages: OSGi boot delegation; Spring Boot `LaunchedClassLoader` **double class
definition**; JBoss Modules (`ClassNotFoundException` on bootstrap-loaded shaded classes); JPMS
`IllegalAccessError`; **GraalVM native image — impossible, not merely hard** (*"JVMTI and other
bytecode-based tools are not supported with Native Image"*).

**`appendToBootstrapClassLoaderSearch` is FORBIDDEN** for the runtime — javadoc warns of
`IllegalAccessError`, a previously-failed symbolic reference **stays failed forever**, and it does
not cover resource lookup. OTel stores agent runtime entries as **`.classdata`, not `.class`**, to
prevent accidental loading by app classloaders.

---

## DESIGN CHANGES ACCEPTED (from A4/A6/A7/A8/A9/A10)

| # | change |
|---|---|
| C24 | **Add assumption A4b: "target clocksource is tsc or kvm-clock."** That, not nanoTime, is load-bearing. Probe `/sys/devices/system/clocksource/clocksource0/current_clocksource` at startup; **self-calibrate in premain** (median of 10k back-to-back nanoTime pairs): >60ns -> switch tier-2 to 1-in-64 sampled timing; >200ns -> disable tier-2 and emit `ax.clock.degraded=1`. Put the degraded column in the budget table, not a footnote. |
| C25 | **Anchored clock for wall-clock stamps only** (one `currentTimeMillis` + `nanoTime` at start, re-anchor on 1ms drift). Never `currentTimeMillis` for a duration. Coarse volatile-long clock permitted for flush timestamps only, never for method duration. |
| C26 | **Exact queue: `org.jctools.queues.MpscArrayQueue`**, power-of-two capacity (start 16,384), pre-allocated at premain. Chunked/Growable/Unbounded/Linked **forbidden**. Fallback: no-Unsafe -> `MpscAtomicArrayQueue` (what OTel ships). Decide once at startup. |
| C27 | **Full policy verbatim:** `if (!queue.offer(rec)) { dropped.increment(); return; }` — one call, no retry, no spin, no park. Export `ax.transport.dropped` as a first-class metric. |
| C28 | **FIX THE ALLOCATION HOLE — hand-rolled MPSC ring over `long[]`** with a cache-line-padded `AtomicLong` producer index. One event = one long: `methodId(16b) \| errorClassId(8b) \| logLinearDurationBucket(10b)`. Zero allocation, zero new deps, honours the dep rule. (Alternatives considered: Agrona `ManyToOneRingBuffer` = 4th dep; Disruptor = rejected, cannot resize, Log4j2's defaults allocate 80-140MB.) |
| C29 | **>>> MOVE ALL TIER-2 AGGREGATION OFF THE APP THREAD** (dd-trace's `ClientStatsAggregator` model). App thread does exactly one ring write. The single drain thread owns the `LongAdder`s and histograms. **This eliminates every A8 contention problem by construction.** |
| C30 | **Histogram: plain non-thread-safe, single-writer, owned by the drain thread.** No `Recorder`, no `ConcurrentHistogram`, no `AtomicHistogram`, no `WriterReaderPhaser`. Default implementation = **hand-rolled log-linear `long[1024]`** (16 sub-buckets/octave, ~2ns, +/-3%, 1.6MB for 200 methods, zero deps). Do not copy OTel's explicit-bucket record path. |
| C31 | **Never ship a per-pod p90.** Ship raw bins; compute percentiles in `gt-collector`. |
| C32 | **>>> `ax-agent-core` registers `canRetransform=false` and no-ops on retransform** (`if (classBeingRedefined != null) return null;`). This (a) guarantees we run before OTel, (b) prevents breaking OTel's startup retransform batch. **OPEN TENSION with C4 (de-instrumentation): resolve by registering a SECOND, retransform-capable transformer for de-instrument only, or by going condy-only. Must be settled empirically by the 3-agent harness before Tier-1b is built.** |
| C33 | **Tier-1 probe delivery = condy + `java.lang.$Auxin` + `Object.equals` bridge** (JaCoCo's exact recipe). Classfile >=55 -> `ConstantDynamic`, no field. <55 -> synthetic field + `$axInit`. Interfaces handled separately. |
| C34 | **Three-tier class layout:** (1) exactly one bootstrap class `java.lang.$Auxin` with one `public static Object data`; (2) agent runtime in an isolated `AuxinClassLoader`, entries stored as `.classdata`; (3) instrumented classes reference only `java.lang.*`. **`appendToBootstrapClassLoaderSearch` forbidden.** |
| C35 | **Tier 3 becomes a JaCoCo *offline-instrumented canary image*, not an on-the-fly agent.** This is JaCoCo's own documented answer to agent conflicts, and it removes the startup burn (A11) and the whole transform-chaining surface. |
| C36 | **Copy OTel's `GlobalIgnoredTypesConfigurer` wholesale**, plus ignore `org.jacoco.`, `org.apache.skywalking.`. Ship `otel.javaagent.exclude-classes=<our pkg>.*` guidance. |
| C37 | **Ship `ax_transform_failures_total` and `ax_classes_skipped_total{reason}` from day one.** The JVM silently ignores transformer exceptions — without these we ship 40% coverage and call the rest dead code. |
| C38 | **Kyverno policy hard requirements:** `failurePolicy: Ignore`; **the policy must NEVER return `allowed:false`** — on any internal error, mutate nothing and admit; `namespaceSelector NotIn [kube-system, kube-public, kube-node-lease, kyverno, <collector-ns>]`; opt-in via pod **label** `auxin.dev/inject`; `webhookTimeoutSeconds: 2`; >=2 replicas + PDB + anti-affinity; **idempotency mandatory** (guard on both initContainer presence and the `-javaagent:` substring). |
| C39 | **JAVA_TOOL_OPTIONS handling:** absent -> set with a **leading space**; present with literal value -> **append**; present with `valueFrom` -> **skip injection and emit a warning event**. Never blind-append a duplicate env entry. Document the `Picked up JAVA_TOOL_OPTIONS` stderr line in the runbook. |
| C40 | **Declare the unsupported matrix in section 1, not a footnote:** GraalVM native image = impossible; OSGi / WildFly / Spring Boot fat jar / JPMS = must be tested and claimed, not assumed. |
| C41 | **NEW GATE — 3-agent integration harness** (Spring Boot 3 + Tomcat + Kafka) asserting per class that our probe fires AND JaCoCo reports the line AND the OTel span exists, **simultaneously and under traffic**. The OTel/SkyWalking bug was invisible until the first request. Two-of-three is a failure. |

## OPEN / UNVERIFIED (must be settled empirically, not assumed)
- **A6b**: does a `-javaagent` injected via `JAVA_TOOL_OPTIONS` premain **before** one on the pod's
  own command line? HotSpot's `arguments.cpp` warns that flag *application* order differs from
  *scanning* order. Needs a 3-line test.
- **No direct JaCoCo+OTel conflict report was found.** Absence of evidence only. The mechanism
  predicts it works. Must be proven by the C41 harness.
- **C32 tension**: canRetransform=false (ordering + OTel safety) vs de-instrumentation (C4).

---

# EXPERIMENT E1 — transformer ordering & de-instrumentation (RUN LOCALLY, JDK 17.0.18)

Not researched — **executed**. Harness in scratchpad `xform/`. Two `ClassFileTransformer`s
registered in one premain; T1 incapable (1-arg `addTransformer`), T2 capable (2-arg, `true`).
T1 stamps the class via a same-length constant-pool string swap (a valid class edit needing no
bytecode library); T2 observes, and on retransform strips the stamp.

```
[T1 incapable] beingRedefined=false stampedAt=138
[T2 capable]   beingRedefined=false seesMARK1=true
[app] marker=MARK1                              <- T1's edit is live
--- triggering retransformClasses(Target.class) ---
[T2 capable]   beingRedefined=true seesMARK1=true    <- T2 sees T1's CACHED OUTPUT, not original bytes
[T2 capable]   de-instrumented at=138
--- retransform complete ---
[app] marker=ZZZZZ                              <- probe successfully REMOVED at runtime
```

### Established facts (measured, not inferred)
1. **On class load**, the incapable transformer runs **first**; the capable one receives its
   **output**. Confirms the `ClassFileTransformer` spec ordering.
2. **On retransform, the incapable transformer is NOT called** — its bytes from the last load are
   **replayed verbatim** as the input to capable transformers.
3. **The incapable transformer's edit survives** a third party's retransform. => our probes survive
   OTel's startup retransform batch.
4. **A capable transformer CAN strip the incapable one's probe during retransform**, and the change
   takes effect in the running application.

### >>> C32 TENSION RESOLVED — the two-transformer architecture
| transformer | `canRetransform` | job |
|---|---|---|
| `ProbeInstaller` | **false** | installs probes at initial class load. Runs before OTel by spec. Output is cached and replayed on every later retransform, so probes are never lost. |
| `ProbeStripper` | **true** | invoked only by **our own** `retransformClasses()` once a class's probes are all set. Receives the cached probed bytes, returns probe-free bodies. **This is Tier-1b, and it works.** |

C4 (de-instrumentation) and C32 (incapable for ordering + OTel safety) are **both satisfiable at
once.** Steady-state overhead can genuinely reach zero without giving up ordering priority.

### Caveat before this is load-bearing
E1 used a byte-level string swap, not real bytecode. The **structural** rule still stands:
retransformation must not add or remove fields or methods. **Condy-based probes (C33) add
neither**, so stripping them is legal — but this must be re-proven with real ASM + `ConstantDynamic`
in the Tier-1 build step, on JDK 11/17/21. Registered as gate E2.

---

# EXPERIMENT E2 — condy probes: install at load, strip on retransform (RUN LOCALLY, JDK 17.0.18, ASM 9.7.1)

**GATE PASSED.** The complete Tier-1 + Tier-1b mechanism, executed end to end.

```
[installer] classfile major=61
[installer] probe 0 -> alpha() / probe 1 -> beta() / probe 2 -> gamma()
-- calling alpha() and gamma() only --
  probes[Target] = 101                  <- correct: beta never called
-- retransform: STRIP probes from Target --
[stripper] removed 3 probe(s); fields/methods unchanged
-- post-strip: call beta() (should NOT record) --
  probes[Target] = 101                  <- PROBE IS GONE. beta() ran and recorded nothing.
-- verifying class still works after strip --
  all methods callable: OK
```

### Proven (measured, not inferred)
1. **`ConstantDynamic` probe arrays work on JDK 17** (classfile major 61). BSM
   `(Lookup,String,Class,String,int)[Z` on a runtime holder; condy descriptor declared as
   `Ljava/lang/Object;` + `CHECKCAST [Z` (JaCoCo's JDK-8216970 workaround) — required, works.
2. **The installer may add methods at initial class load** — the no-schema-change rule binds only
   redefine/retransform.
3. **De-instrumentation is real**: after `retransformClasses`, `beta()` executed and recorded
   nothing. **Steady-state cost genuinely goes to zero**, not merely "cheaper".
4. **The class remains valid and fully callable after stripping.**
5. **No fields or methods added or removed during retransform** => schema unchanged => legal.
6. **`ClassWriter(0)` is sufficient. COMPUTE_FRAMES was never needed.**

### >>> NEW FINDING, feeds A2 and A11
The blind store (`LDC condy; CHECKCAST; push idx; ICONST_1; BASTORE`) is **5 instructions with no
branch, therefore no stack map frame**. The read-then-store variant in C-changes needs a branch,
and every branch target needs a **StackMapTable entry per probe site**. Across ~50k methods that is
a direct increase in class-file size and in **load-time verification CPU** — which is A11, the
incident vector. **This is a second, independent reason JaCoCo uses a blind store, and it was not
in the earlier analysis.** The A2 experiment must now measure three arms:
(a) blind store, (b) read-then-store + frames, (c) read-then-store with the array hoisted to a
local (one frame per method rather than per probe), scoring **both** steady-state latency
**and** class-size/verification cost.

---

## A5 — static call graph + Spring
### VERDICT: **REFUTED.** Static call graphs are far more unsound than the plan assumed.

**Samhi et al., "Call Graph Soundness in Android Static Analysis", ISSTA 2024** — 13 static tools
vs dynamic traces across **1,000 apps**:

| finding | value |
|---|---|
| **methods executed at runtime that are MISSING from the static call graph** | **61% (mean across 13 tools)** |
| CHA-based recall | 21-67% (min 40% missed) |
| SPARK (points-to) | up to **100%** missed — **~2x worse than CHA** |
| apps with <10% of methods missed | **6 of 126** |
| dynamic entry points missed | 34.5% — **but these are only 20.3% of missed methods** |
| **the other 79.7%** | **transitive consequences** |
| single most-missed method kind | **`<clinit>` static initializers (56,708)** |

> *"A high level of precision in call graph construction is a synonym for a high level of
> unsoundness."*

Both caveats make 61% a **lower bound** (traces from only 5 minutes of random execution; the
head-to-head used the 126 *simplest* apps). Corroborated independently on Spring by Jasmine
(SPARK edge recall **4.62%** vs 86.71%) and by reflection prevalence: **78% of 461 Java projects
use reflection**, 21% use dynamic proxies.

### >>> What this does to the design
The rule `dead = static-unreachable AND runtime-unobserved` still **fails safe** — static
over-reports unreachability, and runtime catches those. But:
- **The CASCADE is the dangerous part and must be demoted.** Using the static graph to conclude
  "entry point X is dead, therefore its whole subtree is dead" is precisely what a graph missing
  79.7% of its edges transitively will get wrong.
- Static becomes a **corroborating** signal separating *candidate* from *unknown* — **never an
  independent basis for deletion.**
- Entry-point enumeration is necessary but buys only ~20% of the problem.

---

## >>> A3 REPRICED — the single most actionable number found

**Picnic (production Kubernetes, Java):** `-javaagent:jacocoagent.jar=includes=tech.picnic.*,output=tcpserver`
=> **"we observed an average overhead of 0.03%"**, measured across two 24-hour A/B periods.

This **kills the overhead panic**, and it does so with JaCoCo — blind-store **branch** probes, the
expensive option. The 10-25%-74% figures in A3 are **unscoped, whole-codebase** instrumentation.
**`includes=` package scoping is the entire lever.** Our tier-1 is method-granular and cheaper
still.

=> **C13 (default-deny scope) is not merely a startup mitigation — it is THE overhead mitigation.**
=> **The "1 pod in N" rationing (C35/tier-3) may be solving a problem that does not exist.**
   Re-derive it from our own measurement, not from the unscoped literature figures.

---

## Window policy, safety, and the precision ceiling

**Teamscale/CQSE — the best published window policy in existence:**
> *"we record code execution for several months and make sure to target important time intervals
> like the time of the **year-end closing**."*
Phase-aware, not duration-only. (SAP Coverage Analyzer "Lite mode... records code execution on
**method level** to minimize performance overhead" — same tier-1 choice we made.)

**Google Sensenmann (unofficial, HN, self-identified Googlers):** 6-month heuristic; directory
owner must approve; *"it's rare there's ever a false deletion."* And the anecdote that belongs in
the product spec:
> *"a system with a 'stop the robot uprising' button that we seem to press every couple years.
> Needs code in a lot of microservices that **looks dead for all intents and purposes**... telling
> Sensenmann to never ask about these files again was as simple as copy-pasting a line."*

**Precision ceiling — nothing published exceeds ~88%:**
| claim | value |
|---|---|
| best static-only Java dead-method detector (DCF/RTA) | **84% precision (~16% FP)** |
| Lacuna hybrid (JS) | 63% precision |
| **dead methods that were "born dead"** | **89.5% mean / 96.3% median** |
| dead methods ever actually removed | 38.8% mean |

**Safety — what happens when you actually delete:**
| claim | value |
|---|---|
| **coverage-only debloating breaks downstream consumers** | **18.5% of clients (52/281)** — while their own tests pass 98.4% |
| debloating attempts producing a sound **and** robust program | **13%** |
| JShrink test pass rate | 98.5% (100% with checkpointing); JRed 41.6%, Jax 37.3% |
| aggressive entry points | 36.6% more debloating, failures 1.5% -> **3.4%** |

> *"the general lack of attention paid to post-debloating validation has resulted in
> **over-reporting of successful debloating in the literature**."*

**Market gap CONFIRMED:** Elastic Universal Profiling is **explicitly not marketed for dead code**;
Datadog Code Analysis is static-only; New Relic has no dead-code feature; Qodana's coverage support
is test-coverage only. **Nobody in the observability vendor space sells dead-code detection from
production telemetry.** No published post found from Dropbox, LinkedIn, Shopify, Airbnb, Stripe,
Netflix, Booking, Zalando, Grab, or Twitter.

**The unclaimed measurement:** nobody has published (a) a false-positive rate for production-informed
deletion at scale, (b) a revert rate, (c) an **observation-window sensitivity curve**, or (d) a
single incident postmortem caused by dead-code removal. That absence is both a product opportunity
and a warning that industry consensus here rests on anecdote.

---

## DESIGN CHANGES ACCEPTED (from A5 + industrial evidence)

| # | change |
|---|---|
| C42 | **REPRICE the overhead design against Picnic's 0.03%.** Package scoping (`includes=`) is the dominant lever, not probe cleverness. Re-derive tier-3 rationing from our own A/B measurement; the "1 pod in N" rotation is provisionally **suspended** pending that number. |
| C43 | **DEMOTE the static cascade.** Static reachability is a corroborating signal that separates *candidate* from *unknown* — **never an independent basis for deletion**, and never a licence to delete a subtree. A 61%-unsound graph cannot carry transitive conclusions. |
| C44 | **Explicit `<clinit>` rule**: treat every `<clinit>` of any reachable type as reachable (over-approximate), and **never nominate a `<clinit>` for deletion**. It is the single most-missed method kind and ~45% of cross-tool disagreement. |
| C45 | **Phase-aware observation windows.** Render the window **and which business phases it spanned** (month-end, quarter-end, year-end close, peak season, DR drill). A 90-day window that missed year-end close is not 90 days of evidence. |
| C46 | **Permanent suppression file, checked into the repo, one line per entry.** Break-glass/emergency code *looks* dead by construction. This must exist **before** the first deletion proposal ships. |
| C47 | **Downstream-consumer guard.** Any `public` method on a type reachable from a published artifact's API surface is **`unknown`**, never `dead`, unless the consumer set is closed and observed. Our runtime data says nothing about someone else's classpath. (18.5% of clients broken while the library's own tests passed.) |
| C48 | **Cap the precision claim at the literature ceiling (~88%).** Publish a **false-negative-biased posture with a stated window**, never a precision number. |
| C49 | **Product reframe worth a decision before the IDE/MCP surfaces are specced:** 89.5% mean / 96.3% median of dead methods were **"born dead"** — never executed from the moment they were committed. The highest-value surface may be *"this PR added code that has never executed"* on a recent-commit window, not quarterly archaeology. |

---

# ADDENDUM — two CORRECTNESS BUGS + industrial safety model
(renumbered C50+ to avoid collision with C1-C49)

## >>> C50 — THE TEST-LIVENESS TRAP (correctness bug, not a filter)
Google Sensenmann, verbatim:
> *"The testing infrastructure is going to run all those tests, including lib2_test, despite lib2
> never being executed 'for real'. **This means we cannot use test runs as a 'liveness' signal**:
> if we did... We would only be able to clean up untested code, which would severely hamper our
> efforts."*

**If the agent is ever attached in CI, staging, integration, or canary smoke tests, every tested
method becomes permanently "alive" — so the system can only ever delete UNTESTED code. That is the
exact inversion of what a customer wants.** Excluding test classes from the inventory is necessary
but NOT sufficient.

Required fix, three parts:
1. **The agent must refuse to report from any JVM not explicitly production-classified.** Explicit
   allowlist, **fail-closed** (absent classification => do not report).
2. **Library<->test SCC in the static graph (Tarjan)** so a library reachable only from its own test
   collapses with it and neither keeps the other alive. Sensenmann matches test<->library by edit
   distance on names and admits the ambiguous cases are unsolved.
3. **Discard at ingest** any coverage record carrying a test-runner frame, and count the discards.

Scale context: Sensenmann submits >1000 deletion CLs/week and has deleted ~5% of all C++ at Google.

## >>> C51 — NEW ELIGIBILITY CLASS: `NOT_DYNAMICALLY_OBSERVABLE` (correctness bug)
TOSEM 2022, verbatim: *"Primitive constants, custom exceptions, and single-instruction methods...
are not part of the executable code in the bytecode, and **cannot be covered dynamically**."*
Constant-returning accessors, compile-time-folded `static final` initializers, and trivial
single-instruction methods may be optimised away or never appear as a distinct frame.
**Classify these at build time from the ASM inventory and NEVER count them as "unobserved."**
Must stay distinguishable from a de-instrumented method (C4).

## C52 — call-graph edges need SEMANTICS, not just existence
SCARF classifies edges **blocking / transitive / no-op**. `if (x instanceof Bar)` is a **no-op**
edge — the site can be constant-folded, so it does not block deprecation. A binary
reachable/not-reachable graph cannot express this and will over-block. Start with two classes
(blocking, no-op); grow the no-op catalogue.

## C53 — CONTINUOUS RE-VALIDATION (best single idea in the corpus)
SCARF: *"the properties of the candidate subgraph that led to safe removal initiation **must
continue to hold true at every run of the system**: if the subgraph stops being a candidate, then
safe removal is aborted."* A proposal is **not a snapshot** — it is a standing claim re-derived on
every analysis run, silently revoked by any new observation or new static edge.

## C54 — RATE LIMITING AS A SAFETY CONTROL (not a cost control)
SCARF onboards a new system at **5 assets/day**, rationale stated: *"even if SCARF incorrectly
marks assets as deprecated, it will do so at a limited speed and thus give more time to detect and
remediate."* Cap proposals/day/service as a documented safety parameter. A single SCARF deprecation
takes over a month end to end.

## C55 — "EFFECTIVE FALSE POSITIVE" AS THE NORTH-STAR METRIC
Google Tricorder's definition: *"any report from the tool where a user chooses not to take action
to resolve the report."* Enforcement: **>=90% actionable required for a new analyzer; >=10%
not-useful puts it on probation; >=25% can turn it off immediately**; best analyzers run 0-3%.
Instrument the IDE and MCP surfaces for this from day one, per entry-point class and per eligibility
class, and self-disable a rule class that crosses 25%. **Nobody in this space publishes this.**

## C56 — TWO-PHASE QUARANTINE + HEARTBEATING TOMBSTONE
Phase 1 = land the deletion (cheap revert via source control; SCARF performs it **transactionally**
across the subgraph). Phase 2 = a second wait *"to allow time for any errors caused by this stage to
be detected and reported"* before anything irreversible (DB column drop, schema removal).
**Known failure from practice: after a server move, tombstone logging silently failed for months —
"silent tombstone-logging failure is indistinguishable from 'code is dead'." Any tombstone MUST
heartbeat.** No published source specifies a dwell time.

## C57 — HONEST PRECISION TARGET: ~72%
The closest published analogue to our exact architecture (CQSE, *Dead Code Detection on Class
Level* — static reachability from entry points + a runtime list of loaded classes, applied
iteratively): **72% precision** judged by 6 developers, and *"about half could actually be removed"*
=> **roughly 1 in 3 flagged classes genuinely deletable.** JShrink held-out: **~15% still break on
unseen executions** even with static+dynamic combined. Lacuna: buying 5.6 points of precision costs
43 points of recall. Publish a target inside that envelope with the window attached.

## C58 — LEAD POSITIONING WITH THE SEV NUMBER, NOT THE LOC NUMBER
Mockus/Rigby et al., arXiv:2504.12517 (Meta): dead-code removal shows **odds ratio 5.2, a 90%
decrease in SEV-causing diffs**; SEV-triggering diffs **76% before -> 24% after**; median Diff
Authoring Time **0.59** (~41% faster). Statistically significant for dead-code removal and **not**
for the other refactoring practices studied. Pair with ICPC 2011: *"for over 70% of [entirely unused
features], it surprised the stakeholders that they were not used at all"* — the buyer's own belief
about what is used is measurably wrong in both directions. LOC-volume claims are Meta-scale and do
not transfer; the SEV and DAT deltas do.

## Corrections to circulating figures
- Meta's "97%" is **patch generation** success, not precision. Meta never publishes a land rate.
- Piranha uses **no runtime data at all** (it keys on flag age) — not a comparable.
- Eder/Munich Re's own conclusion: *"unused code is not a severe problem in the maintenance of the
  examined system"*; net waste ~3.6%. Do not oversell the maintenance-cost argument.
- Google's published position argues for gating on **changed lines** (Tricorder), not sweeps;
  Sensenmann attacks the standing stock. **Two different products** — see C49 ("born dead").

---

## A13 — exception class name capture
### VERDICT: **CONFIRMED**, both halves, with margin.
Cost: `Throwable.getClass().getName()` is a **cached field read** after first call (OpenJDK source),
against a measured **1,901 ns** exception *construction* — three orders of magnitude of headroom.
PII: OTel semantic conventions warn on `exception.message`, **not** on `exception.type`; OWASP ASVS
V16.5.4 / V16.3.4 **affirmatively require** retaining error detail; exception type appears on
neither tier of OWASP's exclusion list while stack traces appear on the recommended list; CWE-209
concerns *external disclosure*, which is not what we do.
**Two carve-outs:** (1) CWE-204 — do not let auth-failure exception types enable user enumeration;
(2) HTML-encode exception type names on render.

## A5b — Spring Actuator `/actuator/mappings` as an entry-point source
**CONFIRMED with three join-key defects**: `getCanonicalName()` nullity + dotted nesting for inner
classes; no method descriptor on `scheduledtasks`; not exposed by default. Usable as a **cross-check**
on the static entry-point list, never as the primary source.

## >>> CGLIB / dynamic-proxy identity — resolved, plus a NEW false-positive vector

**Good news:** probes fire on the **real target method** in every Spring proxy mode (verified in
`CglibAopProxy` source). Method identity is not lost.

**Danger 1 — proxy classes getting instrumented.** Runtime-generated proxies
(`$$EnhancerBySpringCGLIB$$`, `$$Lambda$`, `GeneratedMethodAccessor`, `$Proxy\d+`) must be excluded
by name pattern, or they pollute the inventory and grow agent memory without bound. Empirically
demonstrated in jacoco#655.

**>>> Danger 2 — THE SHARPEST FALSE-POSITIVE VECTOR FOUND: interceptors that short-circuit
before the target.** `@Cacheable` with a warm or shared cache means **the real method body may never
execute even though the feature is heavily used.** Our probe correctly reports "never invoked"; the
conclusion "dead" would be **wrong and confidently wrong**. Same shape applies to `@Transactional`
short-circuits, `@CircuitBreaker` fallbacks, `@Retryable` recovery paths, and any
`HandlerInterceptor`/servlet `Filter` that returns early.

**Required (C59):** any method carrying a short-circuiting interceptor annotation — `@Cacheable`,
`@CacheResult`, `@CircuitBreaker`, `@Retryable`, `@Recover`, `@HystrixCommand` — is flagged
`shortCircuitable: true` in the manifest and can **never** be a `DEAD_CANDIDATE`; it is `UNKNOWN`
with the reason recorded. ax-static detects these by annotation descriptor.

## Static-analysis tooling decision (A5a)
**Build CHA on ASM. Do NOT adopt Soot/SootUp/WALA/Doop as a dependency.** JackEE — the best
published Spring-aware attempt — reaches only **58.04%** of application methods and takes
**2-28 hours**. Points-to (SPARK) measured *worse* recall than CHA. We deliberately build a graph
that is wrong about ~40% of methods and label it honestly via `resolution`.

---

# ISOLATION RESULTS — JPMS + OSGi (Felix) + child-first loaders
**250 assertions x JDK 11/17/21 = 750, byte-identical.** Suite: `isolation/run-isolation.sh`.
This was previously deferred as "needs a real container" — **wrong**. JPMS ships in the JDK and
Felix is a single jar.

## The bridge works
`java.lang.$Auxin` installed and carried probes correctly in every genuinely isolated
container. Constant-pool evidence from a Felix bundle class with boot delegation **entirely off**:
`getstatic java/lang/$Auxin.data` -> `anewarray java/lang/Object` ->
`invokevirtual java/lang/Object.equals` -> `checkcast [Z` -> `putstatic $axProbes`, and
`javap -v -p -c | grep -c io/auxin` = **0**. Same for a named module in a custom `ModuleLayer`
with a bootstrap-parent loader, and for a child-first loader shadowing the agent jar.

A10's predicted JPMS `IllegalAccessError` did **not** reproduce (see F4 for why it cannot), and its
OSGi boot-delegation claim holds — better than claimed: `org.osgi.framework.bootdelegation=io.auxin.*`
alone does not even help, because Felix delegates to the **boot** loader.

## >>> F1 — CONFIRMED AVAILABILITY BUG (violates the fail-open invariant)
```
ClassNotFoundException: io.auxin.agent.runtime.Tier2Runtime
                        not found by iso.osgi.bundle [1]      <- thrown from Service.alpha(int)
```
All three JDKs, in **Felix's stock configuration**.

**Mechanism.** Felix ships `felix.bootdelegation.implicit=true`, which decides delegation by
**walking the call stack**. `LoaderVisibility` probes with `Class.forName(HOLDER, false, loader)`
**from inside `transform()` — on the agent's own stack** — so it answers *visible*, the emitter takes
the direct/condy path, and no bridge is installed. When the instrumented class later links that
package the stack is **bundle code**, delegation does not happen, and it throws **inside application
code**. The suite proves the asymmetry within one JVM: the same loader answers FOUND to the agent
and throws for the bundle.

Tier-1 survives only by accident — the probe **pre-seeds the loader's dictionary** for the one name
it asked about. **Tier-2 becomes an outage on the first call to every boundary method in every
bundle.**

**Fix:** replace the stack-sensitive `forName` probe with a structural test — `Class` **identity**
(not name; a child-first loader supplies a different copy) **plus a parent-chain walk** that actually
reaches `ProbeHolder`'s defining loader. Measured correct in all four Felix configurations where
`forName` is wrong in one. **Assigned.**

## F2 — the bridge path abandons condy
Bridged classes get a field + `<clinit>`, so **Tier-1b can never de-instrument them** and every
interface is skipped (`interfaceNeedsField`). JaCoCo avoids this because its condy bootstrap method
is a **self-method on the instrumented class**. Doing the same — a condy BSM on the instrumented
class that internally uses the bridge — would restore both strippability and interface support.

## F3 — `$Auxin.data` is a public mutable global
Anything can overwrite it and cause `ClassCastException` in application code. JaCoCo carries the
same exposure. A defensive type-check on read would degrade instead of throwing.

## >>> F4 — INVALIDATES C34
JPMS is safe today **only because the VM calls `Modules.transformedByAgent`**, which adds reads to
the **app loader's unnamed module**. **C34's isolated `AuxinClassLoader` would reintroduce the
`IllegalAccessError`** that A10 predicted. C34 must be revised before it is implemented — a design
change invalidated by measurement before it cost anything.

## F5 — two copies of one class share one probe array
Under a child-first loader that shadows a class, both copies write the same probe slots. Coverage is
then "either copy ran", not per-copy. Acceptable for dead-code purposes; must be documented.

## Untested, stated plainly
No real Spring Boot `LaunchedClassLoader`, no Tomcat, no JBoss Modules, no Equinox, no JDK 8 in
these containers, no GraalVM, no concurrency stress.

## F1 FIXED — and fixing it exposed two stale assumptions in the oracle

**The fix.** `LoaderVisibility` replaces the stack-sensitive probe with a conjunction, **structural
test first**:
```java
if (!parentChainReaches(loader, AGENT_LOADER)) return false;     // no foreign loadClass at all
return Class.forName(HOLDER_BINARY, false, loader) == ProbeHolder.class;  // identity, not name
```
Walking first buys two things beyond the fix: for an isolated loader the agent never calls
`loadClass` on it, so there is **no dictionary pre-seeding** (the asymmetry that hid F1) and **no
re-entrant class load into a foreign container from inside `transform()`**. Bounded at 256 hops
(nothing forbids a cyclic `getParent()`).

The proposed rule was **verified, not trusted** — measured across all four Felix configurations, and
**neither condition subsumes the other**:

| Felix mode | `forName` says | parent chain | conjunction | correct |
|---|---|---|---|---|
| `implicit` (stock) | FOUND (agent's own) | **false** | bridge | yes — **was the bug** |
| `strict` | CNFE | false | bridge | yes |
| `bootdelegation` | CNFE | false | bridge | yes |
| `bootdelegation-app` | FOUND | **true** | condy | yes |

Result: the Felix `ClassNotFoundException` is gone; all four bundle classes show
`io/auxin` = **0** and `java/lang/$Auxin` = 4; tier-1 probe bits are **unchanged**
(`11110111` before and after) — same data, safe delivery. `tier2NotBridgeable` now actually fires in
stock Felix, which is the point.

### >>> Two stale assumptions surfaced when the oracle was updated
The fix agent correctly refused to edit its own oracle. Updating it myself exposed:
1. **A stale exemption** — `OsgiCheck` skipped the strip-handle / interface / tier-2 expectations in
   `implicit` mode "because the agent mispredicted visibility there and the run is already a FAIL."
   The cause was gone; the exemption was not. **An exemption kept past its cause is how a regression
   hides.**
2. **A stale classification** — `bridgePath` was derived from the *mode name*
   (`!"implicit".equals(mode) && ...`), so when `implicit` moved from condy to bridge the whole
   expectation set silently re-classified itself. Removing the exemption turned this into 9 loud
   failures instead of silent wrong-path assertions.

**750 -> 762 assertions, 0 failures, JDK 11/17/21.** Twelve assertions that were exempt or encoding
the defect are now actively asserted.

### F3 MITIGATED, not closed
`BootstrapBridge.intact()` remembers the exact `Data` instance; `ProbeInstaller` and `ProbeEmitter`
consult it and degrade to `bridgeTampered` with one WARN per JVM. Catches the realistic case — a
second agent's `premain`, which runs before any application class loads. **Not a closure:** a public
static field is writable by definition and a write landing between the check and a class's
`<clinit>` still reaches application code.

### Known cost of the F1 fix, stated plainly
A **real coverage regression in Felix-default hosts**: `iso.osgi.Helper` went from `probes=11` to
`null` + `interfaceNeedsField=1`, and the strip handle from a live `Class` to `null`. So interface
methods get no coverage there, and tier-1 probes stay on the hot path for the JVM's life. This is
the stated policy ("fail toward no data, never toward throws in app code"), and the old interface
coverage only worked via the pre-seeding accident **while the same class was throwing CNFE on its
tier-2 method** — so it was never a sound state. **F2 is what closes it.**

### Also fixed: a silent coverage hole in our own test suite
`smoke/run-negative.sh` hardcoded `javac --release 17`, so it **could not run on JDK 11 at all** —
it failed in javac before the agent was ever loaded. Now release-adaptive; **negative suite passes on
JDK 11, 17 and 21.**

---

# G5 — three-agent coexistence: BUILT AND PASSING, and it found three more silent bugs
`g5/run-g5.sh` -> **954 assertions, 0 fail, 234 observations, 3m36s, JDK 11/17/21.** No Docker, no
cluster. **The "needs Docker" deferral was wrong and it cost three real bugs.**
Versions: JaCoCo agent **0.8.13**, OTel javaagent **2.31.1**.

## All three DO coexist — with evidence A6 did not have
`all3` passes on every JDK: 13/13 fixture dead methods read never-invoked, 6/6 rare methods
invoked, JaCoCo reports cleanly, **4,430 OTel spans**, and — the number that matters —
**`JAC.agreesWithGt`: 71 methods compared between two coverage engines in the same JVM,
0 disagreements.** A6's "no direct JaCoCo+OTel conflict report found — absence of evidence only"
now has evidence.

## Ordering: MEASURED, not inferred
A passive 4th agent registered one incapable + one capable transformer and recorded the bytes each
was handed. Listed **first** of all agents, its incapable transformer still saw `Customer` at 1065
bytes (original, no markers) while its capable one saw 2131 bytes carrying both gt and JaCoCo
markers. **Capability dominates `-javaagent` order — confirmed empirically.**
Observed: `[JaCoCo, ProbeInstaller, OBS-INCAPABLE] -> [ProbeStripper, OTel, OBS-CAPABLE]`.
No OTel bytes ever reached an incapable transformer.

## >>> G5-BUG-1 — the worst bug in the project. The agent silently instruments NOTHING.
`IgnoreList.PREFIXES` contains `"io/auxin/"` matched by `startsWith`, so an application
package beginning with `io.auxin` is swallowed. The shipped jar instrumented **zero classes**
and reported `classesInstrumented: 0, classesSkipped: {}, transformFailures: 0` —
**indistinguishable from "all of your code is dead."** Reproduced with no other agent attached.

Two defects: (a) the prefix list is dangerously broad — `datadog/`, `com/newrelic/`,
`com/dynatrace/`, `com/appdynamics/` will prefix-match a customer's own packages, and the list
exists to skip *other agents' runtime classes*, not to veto customer code; (b) **`ignored()` is
checked before `scope.included()` and increments nothing** — precisely the C37 failure mode that
C37 was written to prevent. **Assigned.**

## >>> G5-BUG-3 — a silent FALSE SUCCESS (E1/E2 could not have seen this)
JaCoCo, instrumenting after us, **inverts our probe's `IFNE` to `IFEQ`** and inserts its own probe
into both arms. `ProbeStripper` matches an exact opcode sequence, so `removed == 0` and it returns
null — **yet `stripNow` returns true, `stripFailures` stays 0, `classesStripped` increments, and the
class is marked permanently stripped.** `javap`: `gtProbes=6` before AND after the "successful"
strip; a never-invoked method ran post-strip and recorded. **Assigned.**

### >>> AN ARCHITECTURAL CONFLICT THAT ORDERING CANNOT RESOLVE
> **Tier-1b needs to register BEFORE other incapable transformers; JaCoCo's CRC64 identity needs us
> AFTER them. The two requirements are mutually exclusive.**

Related (**G5-BUG-2**): registering before JaCoCo changes the bytes JaCoCo CRC64s, so
`jacococli report` emits 17x "does not match" and **0% coverage for the whole app** — A3's
documented failure mode 3, with us as the cause. This is a **documented decision**, not a bug fix:
either we run after JaCoCo (losing strip-before-others) or we accept breaking JaCoCo's reports in
hosts that run both. Must be stated in the product docs.

## G5-FINDING-4 — `classesLoaded` is really `classesInstrumented`
Any skip also erases the class from the loaded set, so **C10 cannot distinguish "never loaded" from
"skipped"** — and C10 exists specifically so the analysis layer does not mislabel lazily-loaded code
as dead. **Assigned.**

## G5-FINDING-5 — retransform destroys `-parameters` metadata on JDK 11 and 17
Measured by reflection on the live class: `isNamePresent(): true -> false` on **any** retransform on
JDK 11 and 17; preserved on 21. **Not our bug — but Tier-1b de-instruments every fully-covered class
by default, so we would silently destroy parameter names in a customer's running application.**
Frameworks that rely on reflective parameter names (Spring `@RequestParam` without an explicit
value, Jackson `ParameterNamesModule`) would break. **Tier-1b must be OFF by default on JDK < 21,
or gated on detecting no reliance on parameter names.**

## Characterised: the known-unhandled foreign-retransform interaction
Strip works with all three agents attached (6 probes -> 0, class still callable, never-invoked method
records nothing). A foreign agent's `retransformClasses` then **restores all 6 probes** (the JVM
replays the installer's cached bytes) and ax-agent **never re-strips** — `stripped` is a permanent
decision. Correctness unaffected; **the zero-overhead claim and the `classesStripped` metric are
both wrong.** Assigned as part of G5-BUG-3.

## Honest gaps
Plain-Java demo, so only the `java-http-server` and `http-url-connection` OTel modules fired — no
Spring, CGLIB or fat-jar interactions. aarch64 macOS only. OTel span *export* unproven (logging
exporter, not OTLP).

---

# SERIALIZED VERIFICATION — merged tree, single build (jar `f9f23db073da6768`)

Two agents were dispatched into `modules/ax-agent` concurrently (F2 and the G5 fixes). **That was
my coordination error** — `run-smoke.sh` builds into a shared `target/`, so the F2 agent had to
test a frozen snapshot rather than the live tree, and its numbers were F2 **plus** partial in-flight
work. Re-verified from one clean build after both landed:

| suite | JDK 11 | JDK 17 | JDK 21 |
|---|---|---|---|
| `ax-agent/smoke/run-smoke.sh` | exit 0 | exit 0 | exit 0 |
| `ax-agent/smoke/run-negative.sh` | exit 0 | exit 0 | exit 0 |
| `isolation/run-isolation.sh --jdk all` | **1146 passed / 0 failed** | | |
| `g5/run-g5.sh` | **972 passed / 0 failed**, exit 0, shipped jar **unpatched** | | |
| ax-manifest / ax-static / ax-server | 41 / 47 / 185, all pass | | |
| deploy / demo / linux | PASS / PASS / PASS | | |

## F2 — CLOSED, and it closed F3 with it
The bridge now emits the **same** `ConstantDynamic` as the normal path, but with the `Handle` as
`REF_invokeStatic` on the instrumented class's **own** name, plus a synthetic
`private static synthetic Object $axInit(Lookup, String, Class)` whose body is
`java.lang.$Auxin.data.equals(new Object[]{name, count, lookup.lookupClass()})` guarded by
`DUP; INSTANCEOF [Z; IFEQ -> POP; push n; NEWARRAY boolean; ARETURN`. One hand-written `F_SAME1`
frame; `ClassWriter(0)` still, COMPUTE_FRAMES still never.

Regression closed in stock Felix: `probes(iso.osgi.Helper)=11` (was `null` + `interfaceNeedsField`),
strip handle `class iso.osgi.Service` (was `null`). `isInterface=true` correctly yields
`InterfaceMethodref`. `javap | grep -c io/auxin` = **0** for every bridged class.

**F3 closed, proven against its own counterfactual.** New `tamper-late` scenarios overwrite the
bridge field *after* the transform — the window no transform-time check can close:
```
selfbsm: F3 CLOSED: tampering does NOT reach application code (threw: null)
field:   ROLLBACK HAZARD: the SAME late tamper DOES reach application code --
         ClassCastException: class java.lang.String cannot be cast to class [Z
```
Tier-2 remains un-bridgeable by design (`equals`-based hop allocates per invocation).

## G5-BUG-1 — CLOSED
`IgnoreRules` replaces `IgnoreList` with `hard veto > explicit scope > soft veto`. The hard tier is
`java/ jdk/ sun/ com/sun/ javax/` plus the agent's own runtime **derived, not guessed** —
`IgnoreRules.class.getName()` and `ClassReader.class.getName()` yield the real (possibly relocated)
packages. `io/auxin/` is gone, so user space is user space again. Soft vetoes (other agents'
runtimes) lose to a more specific include, compared on **normalised** lengths — otherwise naming a
vendor root exactly would read as one char *less* specific and lose, which is the same bug again.

Shipped jar, unpatched, identical on 11/17/21:
`inst=17 coverage=17 classesLoaded=18 instrumentedClasses=17 scopeMatchedNothing=False transformFailures=0`
(was `classesInstrumented=0, coverage=[], classesSkipped={}`).

The veto check now sits **after** `scope.included()`, so it can only fire on a class the operator
asked for — which is why counting every one cannot drown the signal. Plus `scopeMatchedNothing`:
**"configured to do work and did none" can never again look like a clean run.**

## G5-BUG-3 — CLOSED (bookkeeping), shape-matching deliberately NOT attempted
`ProbeStripper` publishes `[removed, blocked]`; `blocked` counts probe-array loads not followed by
one of our shapes — the JaCoCo-inverted-branch signature. `removed == 0` no longer increments
`classesStripped`, does increment `stripBlocked`, does **not** mark the class stripped, and logs once.

| config | classesStripped | stripBlocked | stripReArms |
|---|---|---|---|
| `all3-A` (jacoco -> gt) | **6** | 0 | **2** |
| `gt-before-jacoco` | **0** | **12** | 0 |

Both previously reported `classesStripped=4` — 4 claimed where at most 3 were real, and 4 claimed
where **0** were real. `stripped` is now revocable: a raw constant-pool scan on every foreign
retransform detects the replay and re-arms, bounded at 3 attempts/class.

**Not fixed on purpose:** removing our branch from JaCoCo's inverted shape would mean deleting
instructions inside JaCoCo's arms or leaving an arm unreachable — silently falsifying another
agent's branch coverage. The no-op remains; **the silence is gone.**

## G5-FINDING-4 — CLOSED
`ProbeHolder.observeLoad` records the class the moment the transformer sees it, **before every skip
decision**. Every g5 window: `classesLoaded=18` vs `instrumentedClasses=17`, difference exactly the
pure interface. Negative suite proves the sharp case: under a tampered manifest `smoke.SmokeTarget`
is skipped, absent from `instrumentedClasses`, **present** in `classesLoaded`.

## Two more stale-oracle corrections (same class of error as the isolation ones)
1. **`G5-BUG-1.silentZeroCoverageReproduced`** was an intentional tripwire asserting the defect.
   Inverted into `shippedJarInstruments` + `notSilentlyIdle`, and the `g5/src/patch` machinery is
   retired. **Noted in the source:** that patch was inert after the rename only by luck — had the
   class kept the name `IgnoreList`, `jar uf` would have overwritten the FIXED class with the stale
   one and every transform would have hit `NoSuchMethodError`.
2. **`T1B.retransformAccepted` was a genuinely wrong assertion** — labelled "retransformClasses
   accepted the bytes" but reading `stripNowReturned is True`. Those were the same fact only while
   bug 3 existed. Split into `retransformAccepted` (asserts `stripFailures == 0`) and
   `probesActuallyRemoved` (gated on whether the ordering can remove anything).

## Still open, by decision or by machine limit
- **G5-BUG-2 / the C32-C4 tension.** Tier-1b needs to register before other incapable transformers;
  JaCoCo's CRC64 identity needs us after them. **Mutually exclusive — a documented product decision,
  not a fixable bug.**
- **G5-FINDING-5.** Retransform destroys `-parameters` metadata on JDK 11 and 17 (JDK bug; fine on
  21). **Tier-1b must default OFF below JDK 21** or it will silently break Spring `@RequestParam`
  without an explicit value and Jackson's `ParameterNamesModule`.
- **`ax-server` input change, needs a decision:** `decode.py` taints a window by scanning
  `classesLoaded` for test-runner prefixes (C50.3). That set is now wider, so a broad
  `include.packages` covering a test framework will taint windows it previously did not. Arguably
  more correct; confirm it is wanted.
- x86_64 G1; a real cluster; Spring/CGLIB/fat-jar under three agents; OTLP span export.

---

# E2E — the seam nobody had ever tested: real agent -> real collector

Everything in this project was verified per-component. **The one test never run was the two
halves talking to each other.** Running it found three more bugs, two of them total pipeline
breaks, in under five minutes.

| # | bug | effect |
|---|---|---|
| **#21** | `collector/classification.py` never read `agentHealth.environment` — the spelling CONTRACTS §2 pins as **CANONICAL** and the one `Batch.java` actually writes. It checked only `jvmClassification.*` and a top-level `environment`. | **Every window 403'd `not_production_classified`. 100% data loss, reported as a misconfigured JVM.** |
| **#22** | `ax-static` emits `semantics: "noop"` (CONTRACTS §1 says `blocking\|noop`); `ax-server` accepted only `{blocking, no-op, transitive}`. | **The real server could not LOAD the real manifest** — `ManifestError` at startup. Found in 30 seconds. |
| **#23** | `HttpSender`'s javadoc says `POST /v1/ingest` but it posts to `ax.collector.url` **raw**, so a natural `http://host:8099` posted to `/` while the collector serves `/v1/ingest`. | **Every window 404'd silently**, then the circuit breaker opened. `bytesIn: 0`. |

All three are the **same root cause as #17, #18 and #19**: each component was verified against a
stand-in for the one it talks to — the agent flushes to a throwaway listener that accepts any body,
the server's fixtures were hand-written. **Six bugs from one structural mistake.** Four of them were
100% data loss.

## The pipeline, working (first time, 2026-09-12)
```
windowsAccepted 2   windowsRejected 0   bytesIn 5003   classesMerged 32
probesNewlySet 39   tier2Records 6      edgeRecordsMerged 47
edgeSampledObservations 5567            probeInstallMaskRecords 32
```
Real telemetry, zero configuration beyond the package scope:
```
OrderHandler#handle    431,348 calls  0 errors  p50 4.9us  p90 15.4us  p99 49.2us
HealthHandler#handle    43,135 calls  0 errors  p50 4.6us  p90 14.8us  p99 47.1us
```
Those handlers were found automatically by **interface-boundary detection** (`implements HttpHandler`),
not by a pattern.

## Every safety property is visible in the live output
```
counts          {NOT_DYNAMICALLY_OBSERVABLE: 21, LIVE: 39, UNKNOWN: 26}   <- ZERO candidates, correct
phasesMissing   [month-end, quarter-end, year-end-close, peak-season, dr-drill]
runtimeEdges    18 inbound / 13 outbound + "ABSENCE is not evidence of anything"
instrumentation "~probesInstalled => SILENCE ... (bug #18)"
precisionPosture "false-negative-biased; do not quote a precision number"
```

## The lesson, stated once
**Per-component green is not a pipeline.** Nine of the twenty-three bugs reported success while
doing nothing, and the six worst lived in the seams — the one place a stand-in guarantees you will
never look. `g5/` and `isolation/` exist because a "needs Docker" assumption turned out false; this
e2e run should be a permanent gate for the same reason.
