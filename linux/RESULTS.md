# Linux gates — native arm64 Linux (colima), 2026-09-12

**Host:** Ubuntu 24.04.4, kernel **6.8.0-117-generic**, aarch64, 4 CPU / 5.77 GiB.
**NOT emulated** — colima runs a native arm64 VM on Apple Silicon.

> x86_64 is deliberately NOT run here. QEMU emulation destroys cache and timing fidelity, and
> G1 is specifically a false-sharing measurement. Emulated numbers would look authoritative and
> be wrong — the exact failure mode this project exists to prevent.

---

## L1/L2 — clocksource + nanoTime on a real Linux kernel (A4)

```
clocksource: arch_sys_counter          <- ARM generic timer, the healthy case (TSC-equivalent)
JDK 17:  median pair = 41 ns   p99 = 42 ns   amortised = 15 ns
JDK  8:  median pair = 42 ns                 amortised = 39 ns
```

### >>> FINDING: the clock calibration measures GRANULARITY, not COST, on arm64
The agent calibrates with a **median of back-to-back `nanoTime()` pairs**. On arm64 the generic
timer ticks at ~41.67 ns, so a pair can **never** read below one tick — the median reports **41-42 ns
regardless of how cheap the call actually is**. The amortised cost on JDK 17 is **15 ns**, i.e. the
median over-reports by ~2.7x.

On x86 with TSC, granularity is ~1 ns and median ~= cost, so the same code is honest there.
**The calibration is architecture-dependent**, and the `>60 ns -> sampled timing` threshold sits
uncomfortably close to a *healthy* arm64 machine's 41 ns. A slightly lower threshold, or a slower
arm64 part, would silently downgrade tier-2 to 1-in-64 sampling on a machine with nothing wrong.
The agent already reports `max(median, amortised)`, which is conservative but still publishes 42 ns
when the true cost is 15 ns.
**Recommendation: decide the timing mode on the AMORTISED figure, and report the median separately
as granularity.** (Also confirms macOS/arm64 reading 0 ns was a tick-quantisation artefact, not a
free clock.)

### Cross-version: nanoTime is materially more expensive on JDK 8 (39 ns vs 15 ns amortised).

---

## L3 — cgroup v2 / CFS (A11) — PARTIAL, my test was too weak
`cpu.max` reads correctly at every quota (`200000 100000`, `100000 100000`, `50000 100000`) and
`cpu.stat`'s `throttled_usec` is readable. **But `java -version` is far too trivial to be throttled**
(16-18 ms at every quota, `throttled_usec 0`), so this proves cgroups are *readable*, not that
throttling *occurs*. A real A11 measurement must run the **G4 5,000-class transform workload** under
quota. Stated as incomplete rather than claimed.

---

## L4 — JDK 8 attach: **CLOSED**, end to end
Temurin **1.8.0_502** (arm64 Linux). Target compiled at classfile **52**, so this exercises the
**sub-55 field + `<clinit>` fallback**, not condy.

```
WARN  bootstrap bridge unavailable (jdk8NoPrivateLookupIn): falling back to ProbeHolder
INFO  clock: median 42ns, amortised 39ns, clocksource=arch_sys_counter, tier2 timing mode=full
INFO  armed in 14ms: probeMode=readthenstore manifestClasses=1 livenessEvidence=true
APP_OK a=10
```
Flushed payload (gzip, CONTRACTS **v2**, no percentiles):
```json
{"schemaVersion":2,"agentHealth":{"transformFailures":0,
 "classesSkipped":{"notDynamicallyObservable":1,"bridgeUnavailable":1},
 "clockNs":42,"environment":"production","livenessEvidence":true,"testRunnerDetected":false},
 "classesLoaded":["T"],"coverage":[{"class":"T","probes":"Cg=="}],"tier2":[]}
```
`Cg==` = `0b00001010` -> bits 1 and 3:

| idx | method | probe | truth |
|---|---|---|---|
| 0 | `<init>` | not probed | non-observable (C51) |
| 1 | `alpha` | **set** | called |
| 2 | `beta` | clear | **never called** |
| 3 | `gamma` | **set** | called |
| 4 | `T.main` | clear | never called (driver is `Loop.main`) |

**Proven on JDK 8:** premain attach, sub-55 field fallback, graceful bridge fallback (logged once,
never thrown), correct probe semantics, and a valid v2 flush.

### Side finding: C51 is aggressive, and correctly so
The first attempt used `return 1;`-style methods. **All four were classified
`notDynamicallyObservable` and got zero probes** — TOSEM 2022's "single-instruction methods cannot be
covered dynamically", enforced. The system refused to emit evidence it could not honestly collect.
Worth knowing before anyone demos with a toy class and reports "it found nothing".
