# auxin — benchmark gates G1–G4 and G6, measured results

Run date: 2026-09-12. Harness: `bench/`, driver `./run-gates.sh`, raw output in `bench/results/`.

---

## ⚠️ READ THIS FIRST — EVERY NUMBER BELOW IS PROVISIONAL

**These benchmarks ran on aarch64 (Apple M-series, 128-byte cache line, `mach_absolute_time`).
Production is almost certainly x86_64 Linux (64-byte cache line, vDSO `clock_gettime` on TSC).**

Consequences, per `docs/TOOLCHAIN.md` §2:

- **Cache line is 128 B here, 64 B there.** G1's entire headline result is a *false-sharing*
  result. False-sharing behaviour, coherence-transfer cost, and any padding decision **do not
  transfer**. Apple silicon's coherence fabric is not an Intel ring bus or an AMD Infinity Fabric,
  and the published line-transfer costs cited in VALIDATION A2 (Xeon 48 ns / 136 ns, EPYC 23 ns /
  107 ns) are x86 numbers with no aarch64 counterpart measured here.
- **`System.nanoTime()` has a different cost profile.** macOS/arm64 uses `mach_absolute_time`;
  Linux/x86_64 uses a vDSO TSC read. Every ns/op figure carries that clock's overhead.
- **ARM is weakly ordered; x86 is TSO.** Store-buffer and store-visibility behaviour differ, and
  that is precisely the mechanism G1 is measuring.

> **The latency gate must be re-run on x86_64 Linux before any production claim is made.
> Until then every figure in this document is INDICATIVE ONLY, and the G1 arm recommendation is a
> hypothesis with local evidence, not a settled decision.**

What *does* transfer: the class-file **size** numbers, the **bytecode size** numbers, the
**StackMapTable frame counts**, the **G2 static threshold-crossing table**, and the **G3
correctness proof**. Those are properties of the class file and the JVM spec, not of the CPU.

---

## Environment

| | |
|---|---|
| CPU | Apple M4 — 10 physical / 10 logical cores, **128-byte cache line**, 24 GB |
| OS | macOS 15.3, Darwin 24.3.0, **arm64** |
| JDK | Eclipse Temurin **17.0.18+8**, OpenJDK 64-Bit Server VM, mixed mode, sharing, **aarch64** |
| Maven | 3.9.15 |
| ASM | 9.7.1 (`asm`, `asm-tree`) |
| JMH | 1.37 — `@BenchmarkMode(AverageTime)`, `@Fork(2)`, 3–4×1 s warmup, 4×1 s measurement, blackholes |
| Thread counts | 1, 4, **10** (= `hw.logicalcpu`) |
| Build | `mvn -q clean package` passes; benchmarks were actually executed, not estimated |

Laptop caveats on top of the arch caveat: no CPU pinning, macOS scheduler, E/P-core asymmetry on
M-series, thermal headroom not controlled. The high-variance arms below are affected by this.

---

## G1 — probe pattern shootout

### What is being measured

A generated class with `work(int)` plus 8 small leaf methods, **one probe per method = the Tier-1
method-granular shape**, so **9 probes per op**. All 9 probes live in **one `boolean[9]`**, i.e.
**one cache line**, and the state is `Scope.Benchmark`, so every thread stores into the same bytes.
That is the contention case, deliberately.

Arms (all condy-backed, `ClassWriter(0)`, hand-written frames, never `COMPUTE_FRAMES`):

| arm | bytecode |
|---|---|
| **(a) BLIND** | `LDC condy; CHECKCAST [Z; push idx; ICONST_1; BASTORE` — 5 insns, **no branch, no frame** |
| **(b) READ_STORE** | `if (!p[idx]) p[idx] = true;` — branch ⇒ **one StackMapTable entry per probe site** |
| **(c) READ_STORE_HOISTED** | as (b), array hoisted into a local once per method |
| *(bonus)* **BLIND_HOISTED** | blind store with the array hoisted — **this is what JaCoCo's `ProbeInserter` actually emits** |

A self-check (`ax.bench.g1.ArmSelfCheck`, `results/g1-selfcheck.md`) confirms every arm really
sets all 9 probes at runtime, so none of these numbers is measuring an eliminated store.

### Latency, ns/op (lower is better) — two independent full runs

**Run B** (the run archived in `results/`):

| arm | t=1 | t=4 | **t=10 (= #cores)** |
|---|---|---|---|
| baseline (no probe) | 7.734 ± 0.481 | 8.219 ± 0.315 | 11.675 ± 2.085 |
| **(a) blind store** | 7.454 ± 0.112 | 10.199 ± 0.078 | **18.143 ± 2.681** |
| **(b) read-then-store** | 7.922 ± 0.072 | 8.440 ± 0.063 | **10.701 ± 0.331** |
| **(c) read-then-store hoisted** | 7.992 ± 0.214 | 8.518 ± 0.194 | **10.507 ± 0.149** |
| *(bonus)* blind hoisted | 7.495 ± 0.141 | 13.606 ± 2.039 | 18.193 ± 2.579 |

**Run A** (earlier identical run, kept because the run-to-run spread is itself a finding):

| arm | t=1 | t=4 | t=10 |
|---|---|---|---|
| baseline | 7.236 ± 0.056 | 8.068 ± 0.096 | 9.805 ± 1.009 |
| (a) blind store | 7.093 ± 0.129 | 12.262 ± 3.945 | 16.706 ± 1.503 |
| (b) read-then-store | 7.827 ± 0.208 | 8.428 ± 0.064 | 9.911 ± 0.505 |
| (c) read-then-store hoisted | 7.775 ± 0.122 | 8.430 ± 0.063 | 9.855 ± 0.369 |
| (bonus) blind hoisted | 7.286 ± 0.222 | 10.956 ± 1.324 | 16.352 ± 0.548 |

### Per-probe cost (delta vs baseline ÷ 9 probes)

| arm | t=1 | t=4 | **t=10** |
|---|---|---|---|
| (a) blind store | ≈ 0 (below noise floor) | +0.22 … +0.47 ns | **+0.72 … +0.77 ns** |
| (b) read-then-store | +0.02 … +0.07 ns | +0.02 … +0.04 ns | **≈ 0 (−0.11 … +0.01 ns)** |
| (c) read-then-store hoisted | +0.03 … +0.06 ns | +0.03 … +0.04 ns | **≈ 0 (−0.13 … +0.01 ns)** |
| (bonus) blind hoisted | ≈ 0 | +0.32 … +0.60 ns | **+0.73 ns** |

### G1 result — **arm (b) read-then-store wins**

1. **Single-threaded, the blind store is free and read-then-store costs ~0.02–0.07 ns/probe.**
   That is the only regime in which PLAN-v2's blind-store choice looks right, and it is the regime
   that does not matter.
2. **Under contention the ordering inverts hard.** At 10 threads the blind arms cost
   **+0.72–0.77 ns per probe** (≈ 1.55× the uninstrumented workload) while the read-then-store arms
   are **statistically indistinguishable from uninstrumented**. This is textbook
   test-and-test-and-set: the read leaves the line **Shared**, the blind store forces **Exclusive**
   (RFO) on *every* call from *every* core, forever.
3. **The blind arms are also the unstable ones.** Blind at t=4 swung 10.199 → 12.262 between runs
   (±3.945 within one run); the read arms held ±0.06. Wakart's ~2.5× false-sharing variance is
   visible here, and it is visible again in G2 at t=10 (min 539 / max 1341 ns/op in one fork set).
4. **(c) buys nothing over (b) at Tier-1 density.** At one probe per method they are within noise
   of each other (10.701 vs 10.507 at t=10), and **(c) cannot be applied to arbitrary pre-compiled
   methods** — parking the array in a new local means rewriting every pre-existing `StackMapTable`
   frame in the method (JaCoCo shifts all locals up by one to do this). (c) only pays off at ≥2
   probes per method, i.e. branch coverage, which is **Tier-3 / DEFERRED**.
5. **The bonus arm is a warning about prior art.** `BLIND_HOISTED` is JaCoCo's shape, and it is
   just as bad as the plain blind store under contention (18.193 vs 18.143 at t=10). JaCoCo's
   hoisting is a *size* optimisation, not a *contention* one. Copying JaCoCo's probe shape does
   not inherit a contention property JaCoCo never had.

### The cost side — class-file size and load-time verification

`results/g1-size.md`. Class with 100 leaf methods + `work()`:

**1 probe per method (Tier-1 shape):**

| arm | class bytes | vs none | Code bytes | StackMapTable bytes | frames |
|---|---|---|---|---|---|
| NONE | 5878 | — | 1485 | 0 | 0 |
| **(a) BLIND** | 7034 | **+19.7 %** | 2388 | **0** | **0** |
| **(b) READ_STORE** | 9069 | **+54.3 %** | 3493 | 909 | 101 |
| **(c) READ_STORE_HOISTED** | 9380 | +59.6 % | 3291 | 1414 | 101 |
| (bonus) BLIND_HOISTED | 7244 | +23.2 % | 2590 | 0 | 0 |

**4 probes per method (what branch coverage would look like):**

| arm | class bytes | vs none | Code bytes | StackMapTable bytes | frames |
|---|---|---|---|---|---|
| NONE | 5878 | — | 1485 | 0 | 0 |
| (a) BLIND | 10007 | +70.2 % | 5361 | 0 | 0 |
| (b) READ_STORE | 15915 | +170.8 % | 10039 | 1209 | 401 |
| (c) READ_STORE_HOISTED | 13826 | +135.2 % | 7437 | 1714 | 401 |
| (bonus) BLIND_HOISTED | **9017** | **+53.4 %** | 4363 | 0 | 0 |

Per-probe bytecode, measured from the emitted `Code` attributes (leaf method, 8 bytes clean):

| arm | +bytes per probe site |
|---|---|
| (a) blind | **+8** |
| (bonus) blind hoisted | +10 at 1 probe/method, amortises to ~+4 at 4 |
| (c) read-then-store hoisted | +16 at 1 probe/method, amortises to ~+12 |
| (b) read-then-store | **+18** |

**Load-time verification CPU** — define + link 400 classes × 51 methods = **20,400 probed
methods**, fresh child JVM per arm, `Class.forName(…, initialize=true)` so linking (and therefore
`StackMapTable` checking) actually happens:

| arm | wall ms | **process-CPU ms** | Δ CPU vs none | total class bytes |
|---|---|---|---|---|
| NONE | 11.6 | 46.9 | — | 1,185,490 |
| (a) BLIND | 13.4 | 56.2 | **+9.3 ms (+19.8 %)** | 1,467,490 |
| (bonus) BLIND_HOISTED | 14.7 | 58.6 | +11.7 ms | 1,508,290 |
| (b) READ_STORE | 17.0 | 68.4 | **+21.5 ms (+45.8 %)** | 1,879,490 |
| (c) READ_STORE_HOISTED | 17.4 | 68.5 | +21.6 ms | 1,940,690 |

### The trade, stated plainly

Choosing **(b) read-then-store over (a) blind store** costs **≈ +12 ms of one-time verification
CPU per 20,400 methods** (≈ **+30 ms per 50,000 methods**) and **+35 pp of class-file size**.
It buys away a **~1.55× steady-state throughput penalty on every contended hot class, for as long
as the probe is installed**. Against G4's measured 539 ms of transform CPU, 30 ms is noise.

**Recommendation: reverse PLAN-v2's blind-store decision for Tier-1, pending the x86_64 re-run.**
The E2 finding that motivated the blind store (no branch ⇒ no stack map frame ⇒ cheaper
verification) is **real and confirmed here** — it is just an order of magnitude smaller than the
contention cost it was traded against. The blind store is only defensible if **Tier-1b
de-instrumentation strips probes fast enough that steady state is never reached**, which makes the
whole Tier-1 cost posture depend on Tier-1b's rate limiter. That dependency was not visible before
this gate.

---

## G2 — inlining regression *(the most important gate)*

### Subject

A javac-compiled, small-method-heavy call graph (`ax.bench.g2.chain`): 5-byte getters, an 8-deep
delegation chain of 12-byte methods, a wide fan-out over 8 short helpers, methods deliberately
sized either side of `MaxInlineSize=35`, and one lukewarm (1-in-32) branch. The **same bytes** are
either loaded unchanged or run through the real ASM probe installer and loaded under an identical
child-first classloader, so `-XX:+PrintInlining` output is line-comparable.

### Static fact: what the probe does to bytecode size (this transfers to any arch)

`results/g2-inlining.md`. Of 32 methods in the chain:

- **11 of 32 methods cross `MaxTrivialSize=6`** with the 8-byte blind probe — every getter
  (5 → 13), `Mixer.inc` (4 → 12), `Mixer.neg` (3 → 11), `Engine.s0/s1/s5` (6 → 15).
- **2 of 32 cross `MaxInlineSize=35`** with the blind probe: `Mixer.mix` 31 → 39,
  `Engine.pad` 29 → 38.
- **2 more cross 35 only with read-then-store**: `Point.sum` 20 → 40, `Mixer.fold` 23 → 41.
  Read-then-store's 18-byte probe roughly doubles the threshold-crossing population.

### Scenario S1 — hot call sites, stock C2 flags

`-XX:-TieredCompilation -XX:-BackgroundCompilation`, 400,000 iterations.

```
baseline      99.9 ns/op
instrumented 129.5 ns/op       +29.6 %
methods that STOPPED being inlined: 0
inline-success call sites: baseline 28, instrumented 28
```

Only verdict change:

| method | bytes clean → probed | baseline | instrumented |
|---|---|---|---|
| `Point::getX/getY/getZ/getW` | 5 → 13 | `accessor` | `inline (hot)` |

**Finding: on HotSpot 17 C2, at hot call sites, the probe costs ZERO inlines.** C2 judges any
sufficiently-executed call site frequent and raises the budget from `MaxInlineSize=35` to
**`FreqInlineSize=325`**, which swallows an 8–18-byte probe whole. Even `Point::scaled` at 40 bytes
(already over 35 before probing) inlines. The four getters lose their `accessor` fast-path
classification — the `MaxTrivialSize=6` crossing is real and observable — but they still inline.

**This partially falsifies VALIDATION A2's framing.** "The dominant cost is lost JIT inlining" is
not what HotSpot 17 does to *hot* code. The +29.6 % here is the probe stores themselves, not lost
inlining.

### Scenario S2 — lukewarm call sites, `-XX:FreqInlineSize=35`

Collapsing the frequency exemption onto `MaxInlineSize` models the call sites that are warm enough
to be compiled but not frequent enough to earn the 325-byte budget — i.e. most of a real
application's code, and the regime a benchmark loop can never produce naturally.

```
baseline     134.6 ns/op
instrumented 198.8 ns/op       +47.7 %
methods that STOPPED being inlined: 2
inline-success call sites: baseline 26, instrumented 24
```

| method | bytes clean → probed | baseline | instrumented |
|---|---|---|---|
| `Mixer::mix` | 31 → 39 | `inline (hot)` | **`too big`** |
| `Engine::pad` | 29 → 38 | `inline (hot)` | **`too big`** |

**Finding: the A2 mechanism is real and reproduces the instant the frequency exemption stops
applying.** Two methods that were inlined stop being inlined, purely because an 8-byte probe pushed
29 and 31 bytes past 35. The throughput penalty rises from +29.6 % to +47.7 %, so roughly **18
percentage points of the slowdown in S2 is attributable to the lost inlines**, on top of the
probe's own cost.

### End-to-end throughput (JMH, default tiered compiler — what production runs)

| arm | t=1 | **t=10** |
|---|---|---|
| uninstrumented | 64.526 ± 6.707 | 283.071 ± 27.969 |
| blind store | 78.033 ± 11.998 (**+20.9 %**) | 556.554 ± 81.977 (**+96.6 %**) |
| read-then-store | 63.999 ± 9.894 (−0.8 %, within noise) | 339.231 ± 98.027 (**+19.8 %**) |

Run A's t=10 pass was worse still for the blind store: 262.610 → **728.126 ± 504.074** (+177 %),
with individual iterations from 539 to 1341 ns/op — a **2.5× run-to-run swing** on the blind arm
alone. The read-then-store arm on the same run: 298.348 (+13.6 %).

### G2 verdict

- **The single largest cost of instrumentation in this harness is not lost inlining — it is the
  blind store's cache-line invalidation under contention (up to +177 %).** G1 and G2 agree.
- **Lost inlining is real but second-order on HotSpot 17, and invisible in hot code.** It costs
  ~18 pp where it bites. It will not show up in any benchmark whose call sites are all hot, which
  is exactly the trap A2 warned about — and the same trap catches the *fix*: a JMH loop over-states
  how well HotSpot tolerates the probe.
- **Probe size is still a first-class design constraint**, because it decides which methods cross
  35. Read-then-store's 18-byte probe doubles that population versus blind's 8 bytes. Every byte
  saved matters: G3 shows we can drop 3 of them (below).
- **De-instrumentation (Tier-1b) restores both properties at once** — the stores and the inlines.
  G3 proves it works.

---

## G3 — condy install / strip correctness

`results/g3.log`. Run as `java -javaagent:ax-bench.jar=g3 -cp ax-bench.jar ax.bench.g3.G3Main`.
Two transformers per E1/PLAN-v2: `ProbeInstaller` registered **`canRetransform=false`**,
`ProbeStripper` registered **`canRetransform=true`** and driven only by our own
`retransformClasses()`. Probe indices assigned from a **sorted `name+descriptor` key** (C7), not
ASM visit order. `<clinit>` is never probed.

**Result: 26/26 assertions pass. `== G3 PASS ==`, exit 0.**

| assertion | result |
|---|---|
| installer ran at initial class load and published a probe index | ok |
| probe array materialised through the condy BSM, sized from the manifest (7) | ok |
| calling `alpha()` and `gamma()` sets exactly probes `<init>`, `alpha`, `gamma` | ok — `1100001` |
| `beta`, `delta`, `epsilon` probes stay clear | ok |
| `retransformClasses` → stripper removed **7 of 7** probes | ok |
| **`beta()` called AFTER the strip records NOTHING** (`1100001` → `1100001`) | **ok** |
| every method still returns the right value after stripping | ok (`beta`=22, `delta(7)`=21, `epsilon`="eps", `field3()`="three", static `field2`=9) |
| `new Subject()` still constructs after stripping | ok |
| **no field or method added or removed** — reflection member set identical across retransform | **ok** |
| **no field or method added or removed** — class-file member set identical across retransform | **ok** |
| zero transformer failures (`ax_transform_failures_total` = 0) | ok |

Both "schema unchanged" checks are made: `getDeclaredFields/Methods/Constructors` before vs after
the live retransform, **and** an ASM walk of the exact bytes the installer emitted vs the exact
bytes the stripper emitted. The subject class carries instance, static and private fields so the
check is not vacuous.

### New finding — the `CHECKCAST` is not needed on JDK 17

```
[installer] gt/bench/g3/SubjectRaw ... condyDescriptor=[Z (no CHECKCAST)
RAW-DESCRIPTOR PROBE: WORKS on this JDK
```

JaCoCo declares the condy as `Ljava/lang/Object;` and follows it with `CHECKCAST [Z` to work around
**JDK-8216970**. On **Temurin 17.0.18 / aarch64 that workaround is unnecessary**: a condy declared
directly as `[Z` verifies, resolves and records correctly. That removes the 3-byte `CHECKCAST`,
taking the blind probe from **8 bytes to 5** and the read-then-store probe from **18 to 12** — which
directly reduces the `MaxTrivialSize=6` / `MaxInlineSize=35` crossing population measured in G2.
**Not yet proven on JDK 11**, which is where JDK-8216970 lived; keep the workaround behind a
per-classfile-version switch until 11 is tested.

### JDK coverage — stated plainly

**Only JDK 17 was actually exercised. JDK 11 and JDK 21 were NOT run; JDK 17 is the only JDK
installed on this machine.** Gate G3 in PLAN-v2 requires 11/17/21 and is therefore **one third
complete**.

The harness is already structured for the other two: `run-gates.sh` reads a colon-separated list of
`JAVA_HOME`s from **`AX_BENCH_JDKS`** and runs G3 once per entry, printing the VM name, version and
arch it actually ran on, skipping (loudly) any path without a `bin/java`:

```bash
AX_BENCH_JDKS=/path/to/jdk11:/path/to/jdk17:/path/to/jdk21 ./run-gates.sh g3
```

Condy requires class-file major ≥ 55, so JDK 11 is the floor; JDK 8 hosts need the field+`$axInit`
fallback (C33), which this harness does **not** implement or test.

---

## G4 — startup cost

`results/g4.md`. **5,000 generated classes × 10 methods = 50,000 methods**, loaded by
`Class.forName(…, initialize=true)` in a child JVM. Four configurations, 5 reps each, the
**median-whole-JVM-CPU rep** reported.

| config | class-load wall ms | class-load CPU ms | whole-JVM wall ms | **whole-JVM CPU ms** | classes seen | transformed |
|---|---|---|---|---|---|---|
| **OFF** (no agent) | 148.8 | 266.2 | 180 | **335.4** | — | — |
| **NAMEONLY** (name-prefix check only, never parses) | 149.1 | 273.1 | 186 | **352.4** | 5260 | 0 |
| **FULL** (name-only first pass, then parse + condy probe) | 268.8 | 788.5 | 306 | **874.3** | 5260 | 5000 |
| **PARSE** (parse *every* class to decide, then probe) | 312.5 | 934.4 | 383 | **1128.4** | 5258 | 5000 |

Deltas:

| comparison | wall | whole-JVM CPU |
|---|---|---|
| NAMEONLY − OFF — the transformer-callback tax alone | **+0.3 ms (+0.2 %)** | **+17.0 ms (+5.1 %)** |
| FULL − OFF — the production shape | **+120.0 ms (+80.6 %)** | **+538.9 ms (+160.7 %)** |
| PARSE − OFF | +163.7 ms (+110.0 %) | +793.0 ms (+236.4 %) |
| **FULL − NAMEONLY** — cost of parsing + probing our own 5,000 classes | **+119.7 ms** | +521.9 ms |
| **PARSE − FULL** — cost of *not* doing name-only matching first | **+43.7 ms** | **+254.1 ms** |

Per-unit: **≈ 23.9 µs wall / 104.4 µs CPU per class**, **≈ 2.4 µs wall / 10.4 µs CPU per method**.
Instrumented class bytes: **3,328,890 → 5,053,890 (+51.8 %)**.

### What this says

1. **Registering a transformer is nearly free; parsing is not.** Being handed all 5,260 class loads
   and doing a `String.startsWith` on each costs **+0.3 ms of wall and +17 ms of CPU**. That
   validates PLAN-v2's "name-only matching in the first pass" as a non-negotiable — and quantifies
   the alternative: **parsing the 258 classes we were always going to reject costs +254 ms of CPU**,
   nearly half the cost of instrumenting all 5,000 classes we *do* want. Those 258 are JDK classes
   loaded early, parsed before ASM itself is JIT-compiled. **Never parse to decide.**
2. **>>> The CPU/wall ratio is the real risk, and it is 4.5×.** FULL adds **+120 ms of wall but
   +539 ms of CPU** on a 10-core box, because the transform work parallelises across GC and JIT
   compiler threads. **Under a 1-CPU cgroup limit that 539 ms of CPU becomes ~539 ms of wall** —
   a 4.5× amplification of the startup cost, arriving exactly when a k8s liveness probe is counting.
   **This is A11, and it is the number the CPU-limit gate exists to measure.** We could not measure
   it (see below).
3. Scaled honestly: 50,000 methods is the plan's own target size, and the agent costs about
   **half a second of CPU** to install probes into them. That is a one-time cost, and it is the cost
   Tier-1b must also pay again in reverse when it strips.

### NOT MEASURED — the Kubernetes half of G4

**The full gate — time-to-healthy on Spring PetClinic at 0.5 / 1 / 2 CPU limits — was not run.
Docker is not installed on this machine (`docs/TOOLCHAIN.md`: Docker —, kubectl —).** There is
therefore **no local evidence at all** about CFS throttling, and A11's headline precedents (OTel
Spring Boot 8.2 s → 22.5 s, +174 %; a custom ByteBuddy agent 30 s → 10 min) remain unreproduced.
The 4.5× CPU/wall ratio above is the closest proxy this machine can produce and it is not a
substitute.

---

## G6 — the sampled runtime call-edge tier *(SCOPE-v3)*

### What is being measured

A generated class with `work(int)` plus 8 small leaf methods. `work()` is a **tier-2 boundary
method, i.e. a sampling root**; the leaves are callees. So **9 instrumented methods and 18 call
sites per op**, the same shape G1 used, and the arms call **the real
`io.auxin.agent.runtime.EdgeRuntime` out of the shipped agent jar** rather than a copy in the
bench module — whatever HotSpot decides about inlining it is what these numbers contain.

`ax.bench.g6.EdgeSelfCheck` runs first and asserts that each arm records exactly the edges its
name claims (0 / 0 / 0 / 0 / 32768 / 32 / 0 for 4096 ops). A benchmark of instrumentation that
silently records nothing would report precisely the number we want to see.

```
arm                        rate              edges   expected
NONE (control)             1-in-1                0          0  PASS
EDGES, tier off            1-in-1024             0          0  PASS
EDGES, never sampled       never                 0          0  PASS
ROOT_ONLY, never sampled   never                 0          0  PASS
EDGES, every root          1-in-1            32768      32768  PASS
EDGES, 1-in-1024           1-in-1024            32         32  PASS
CALLEES_ONLY, foreign trace 1-in-1               0          0  PASS
```

### ns/op, 9 instrumented methods / 18 call sites per op

| arm | t=1 | t=4 | **t=10** |
|---|---|---|---|
| **a0** uninstrumented (control) | 7.316 ± 0.112 | 8.051 ± 0.042 | 9.484 ± 0.020 |
| **a1** calls in the bytecode, **tier off** (the default) | 7.522 ± 0.038 | 7.911 ± 0.308 | 10.419 ± 0.100 |
| **a2** tier ON, **this root not sampled** | 7.564 ± 0.035 | 8.875 ± 0.096 | 12.548 ± 0.207 |
| **a3** root instrumented only, not sampled | 7.543 ± 0.022 | 7.872 ± 0.134 | 9.779 ± 0.051 |
| **a4** every root sampled (stress, not a config) | 55.393 ± 0.440 | 686.624 ± 12.184 | 1921.877 ± 39.201 |
| **a5** the default **1-in-1024** | 8.082 ± 0.825 | 11.090 ± 0.227 | 22.551 ± 1.632 |
| **a6** another thread is inside a trace | 11.737 ± 0.197 | 17.804 ± 0.214 | 37.552 ± 1.925 |

Derived:

| quantity | t=1 | t=4 | t=10 |
|---|---|---|---|
| **unsampled, per instrumented method** (a2−a0)/9 | **0.028 ns** | **0.092 ns** | **0.340 ns** |
| unsampled, per call site (a2−a0)/18 | 0.014 ns | 0.046 ns | 0.170 ns |
| gate only, tier off (a1−a0)/9 | 0.023 ns | ~0 (−0.016) | 0.104 ns |
| the root's sampling decision (a3−a0), per root entry | +0.227 ns | ~0 (−0.179) | +0.295 ns |
| **per edge actually recorded** (a4−a0)/8 | **6.0 ns** | 84.8 ns | 239.0 ns |
| **effective overhead at 1-in-1024** (a5−a0) | **+0.77 ns/op (+10.5%)** | +3.04 (+37.8%) | +13.07 (+137.8%) |
| while another thread traces, per call site (a6−a0)/18 | 0.246 ns | 0.542 ns | 1.559 ns |

**Allocation (`-prof gc`, t=4):** control `≈10⁻⁴ B/op`, unsampled `≈10⁻⁴ B/op`, **every-root
sampled `0.023 ± 0.668 B/op`**. Zero per-call allocation on both paths, measured, not asserted —
the per-thread `State` + `int[]` is one allocation per thread for the life of the thread.

**Ring behaviour, printed per trial so a saturated measurement cannot masquerade as a fast one:**
at t=1 the sampled arm drained **1.02 billion edges with `droppedFull=0` and
`droppedContended=0`** — the 6.0 ns/edge figure was measured against a ring that never filled. At
t=4 and t=10 the same arm shows 3–7% `droppedContended` (a failed CAS is a drop by design, C27)
and still no `droppedFull`.

### Reading these numbers

1. **The unsampled path is the decision, and it is effectively zero**: 0.028 ns per instrumented
   method at one thread, 0.34 ns at ten. For scale, G1 measured the **tier-1 blind probe** at
   **+0.72–0.77 ns/probe at ten threads** and rejected it for that. The edge tier's unsampled cost
   is under half of what was already judged too expensive, and unlike tier-1 it is **off by
   default**.
2. **The t=10 figure is not all gate.** a1 (identical bytecode, tier off, gate still read on every
   call) already costs +0.935 ns/op there, so most of it is two extra call sites per method —
   code size and inlining, the G2 effect — not the volatile load. On a 10-thread run of a 9 ns op
   this is measurable; it is also the regime where the harness itself is least trustworthy (10
   measured threads on 10 logical cores).
3. **`a4` is a stress arm, not a configuration.** Sampling every root entry at 145 M edges/s is
   three orders of magnitude past what 1-in-1024 does to a real boundary method, and its t=4/t=10
   degradation is A7's MPSC contention curve (14.6 ns/op 1P1C → 189 ns/op 6P6C) reproducing itself,
   not something new.
4. **The +137.8% at t=10 and 1-in-1024 is real and misleading, in that order.** The denominator is
   a synthetic root that costs **9.5 ns**; a real tier-2 boundary method is a request handler
   costing microseconds to milliseconds. The transferable form of that row is the **absolute**
   one: +13 ns per op covering 9 instrumented calls, of which ~1.9 ns is the 1/1024 sampled trace
   itself and the rest is the gate being up for other threads while it runs.
5. **The one genuine design cost to disclose is a6.** While *any* thread is inside a sampled
   trace, *every other* thread's `enter`/`exit` falls through the gate into a `ThreadLocal` read
   that finds nothing: 0.25 ns per call site at one thread, 1.56 ns at ten. At 1-in-1024 with
   short traces the gate is down almost always (a5 is the composite), but a deployment with many
   threads and long traces would sit closer to a6 than to a2. That is the number to re-measure on
   x86_64 Linux before widening the rate.

Same architecture hazard as every other gate here: **aarch64, and the gate is a shared `volatile
int` that is written when a trace starts and ends.** On x86_64 a volatile int read is a plain
`mov`; on aarch64 it is an `ldar`. If anything these numbers are pessimistic, but "if anything" is
not a measurement.

---

## Summary table

| gate | status | headline |
|---|---|---|
| **G1** | **RUN — result contradicts PLAN-v2** | **read-then-store (b) wins.** Blind store costs +0.72–0.77 ns/probe and ~1.55× at 10 threads; read-then-store is indistinguishable from uninstrumented. Cost: +12 ms verification CPU per 20k methods, +35 pp class size. |
| **G2** | **RUN** | **0 lost inlines at hot call sites** (C2's `FreqInlineSize=325` absorbs the probe) but **+29.6 %** throughput. With the frequency exemption removed, **2 methods stop inlining** (`Mixer::mix` 31→39, `Engine::pad` 29→38) and the penalty rises to **+47.7 %**. 11 of 32 methods cross `MaxTrivialSize=6`. |
| **G3** | **PASS on JDK 17 only** | 26/26 assertions. Install → correct probes flip → retransform-strip → post-strip call records nothing → all methods work → **no field or method added or removed**. Bonus: the `CHECKCAST` workaround is unnecessary on 17. |
| **G4** | **RUN — partial** | Name-only matching: **+0.2 % wall**. Full instrumentation of 50k methods: **+120 ms wall / +539 ms CPU (+161 %)**. Parsing-to-decide costs **+254 ms CPU** more. **4.5× CPU/wall ratio ⇒ a 1-CPU pod would feel the CPU figure, not the wall figure.** |
| **G5** | **NOT BUILT** | Out of scope for this task. (Built since, in `g5/`: 972 assertions.) |
| **G6** | **RUN — the unsampled path is effectively zero** | **0.028 ns per instrumented method at 1 thread, 0.34 ns at 10** (vs the +0.72–0.77 ns/probe that got G1's blind store rejected), **0 B/op allocated on both the unsampled and the sampled path**, **6.0 ns per edge recorded** with an unsaturated ring. Effective overhead at the default 1-in-1024: **+0.77 ns/op (+10.5%) at 1 thread, +13.1 ns (+138%) at 10** — against a synthetic 9.5 ns root, so read the absolute figure, not the percentage. Cost to disclose: while any thread traces, every other thread pays 0.25–1.56 ns per call site. |

---

## Honest list of what could not be measured

1. **x86_64 Linux — the whole point.** Every latency number here is aarch64 with a 128-byte cache
   line. G1's result *is* a false-sharing result, and false sharing is precisely what does not
   transfer. **Re-run G1 and G2 on x86_64 Linux before acting on the arm recommendation.**
2. **JDK 11 and JDK 21 for G3.** Only 17 is installed. G3 in PLAN-v2 demands 11/17/21; we have one
   of three. The JDK-8216970 `CHECKCAST` finding specifically needs JDK 11 to be meaningful.
3. **JDK 8 hosts.** The field + `$axInit` fallback below class-file major 55 (C33) is neither
   implemented nor tested here.
4. **Kubernetes CPU limits / CFS throttling / Spring PetClinic time-to-healthy (the real G4).**
   Docker is not installed. No container, no cgroup, no throttling evidence.
5. **p99 / p99.9 latency.** PLAN-v2 C5 asks for added p99/p99.9 on a multi-threaded macro
   benchmark. These are `AverageTime` means with JMH's error bars; `SampleTime` percentiles were
   not collected. The tail is where cache-line transfers actually hurt, so this is a real gap.
6. **Real application code.** G2's chain is a synthetic call graph shaped like real code, not real
   code. Spring PetClinic under traffic would exercise megamorphic call sites, reflection, proxies
   and lambdas — none of which appear here, and all of which change inlining.
7. **Tier-1b at rate.** G3 proves one class can be stripped. Nothing here measures the CPU cost of
   stripping thousands of classes under a budget, or what retransform storms do to a running JVM.
   That gap matters more now, because G1 makes the blind store's viability *depend* on it.
8. **Agent interaction (G5).** No JaCoCo, no OTel, no second agent in the JVM. The E1 ordering
   result is assumed, not re-proven under a third party.
9. **Machine hygiene.** No CPU pinning, no isolated cores, macOS scheduler, M-series P/E-core
   asymmetry, uncontrolled thermal state. The high-variance arms (blind store at t=4 and t=10) are
   the ones most affected; their central estimates should be treated as ranges, not points.
10. **`hw.cachelinesize` = 128 was read from the OS, but the probe arrays here are 9–22 bytes** —
    they fit in one line on *both* architectures. What differs between archs is the coherence
    protocol cost, not the sharing topology. A wide-class test (>64 probes, spanning lines
    differently on 64 B vs 128 B) was **not** run.

---

## Reproducing

```bash
cd bench
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home mvn -q clean package
./run-gates.sh            # all five gates, ~22 minutes (G6 is ~13 of them)
# G6 measures the SHIPPED agent runtime, so build it first:
#   mvn -q -f ../modules/ax-agent/pom.xml clean package
./run-gates.sh g6         # the call-edge tier alone: self-check, 1/4/10 threads, -prof gc
./run-gates.sh g1         # or one at a time: g1 | g2 | g3 | g4 | g6

AX_BENCH_JDKS=/jdk11:/jdk17:/jdk21 ./run-gates.sh g3   # multi-JDK G3
GT_BENCH_THREADS="1 4 32" ./run-gates.sh g1            # override thread counts
GT_BENCH_G4_CLASSES=20000 ./run-gates.sh g4            # override synthetic class count
```

Raw output lands in `bench/results/` (JMH JSON + logs + the markdown fragments quoted above).
