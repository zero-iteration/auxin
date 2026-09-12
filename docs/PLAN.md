# auxin — production ground truth for what code actually runs

**Working name:** auxin (module prefix `gt`)
**Status:** DRAFT — NOT GREEN-LIT. Every assumption below carries an ID and must be
internet-validated before any implementation begins.

---

## 1. What we are building

A JVM production-telemetry system that answers, per method and per branch:

1. **Was it ever executed in production?** (dead / dormant code)
2. **Which branch arms never execute?**
3. **How hot is it, what is its error rate, what is its p90?** (boundary methods only)
4. **What calls it?** (static call graph + runtime edges)
5. **Is this release regressing?** (canary verdict)

Surfaced in the IDE, via MCP to coding agents, and as a service topology.

**We record NO values.** No arguments, no return values, no request bodies, no payloads.
Only: invocation booleans, counters, exception class names, and normalized shapes
(route templates, SQL shapes, collection/topic names). This is a hard product boundary,
not a phase-1 limitation.

---

## 2. The governing constraint

> **Added latency per request must be in the low microseconds. No CPU spikes. Ever.**

This constraint drives every design decision below. It is not a goal, it is a gate:
any module that cannot demonstrate its cost under JMH does not ship.

### Latency budget (per request)

| item | count/request | budget each | total |
|---|---|---|---|
| Tier-1 coverage probe (all in-scope methods) | ~200 | <= 2 ns | 400 ns |
| Tier-2 boundary metrics (controllers, DB, MQ) | ~5 | <= 150 ns | 750 ns |
| Transport handoff (amortized) | ~1 | <= 100 ns | 100 ns |
| **Total** | | | **~1.25 us** |

Hard ceiling: **p99 added latency <= 5 us/request, p99.9 <= 20 us.**
Steady-state CPU overhead target: **< 1%** (excluding optional sampling profiler).

### The three cost regimes we must separately defend

1. **Steady-state hot path** — the per-method-call cost. Budget above.
2. **Startup transform cost** — instrumenting N classes at load time burns CPU exactly
   when the pod is starting. Under a Kubernetes CPU limit this causes CFS throttling,
   which delays readiness and can fail liveness probes. THIS IS THE LIKELIEST INCIDENT
   VECTOR AND IS OFTEN OVERLOOKED. (assumption A11)
3. **Flush/snapshot cost** — the periodic aggregation must be bounded, allocation-free
   where possible, off the app threads, and must never stop the world.

---

## 3. Architecture

```
BUILD TIME                        RUNTIME (in-JVM)                 OUT OF PROCESS
-----------                       ----------------                 --------------
ax-static-analysis                ax-agent-core                    gt-collector
  ASM scan of artifact              premain -> ClassFileTransformer   ingest batches
  - method inventory                - per-class static boolean[]      merge probe arrays
  - static call graph               - probe idx resolved at           union across pods
  - framework entry points            TRANSFORM time (constant)     gt-store
  - source line table               - zero allocation on hot path      SQLite (dev)
        |                           - tier-2: LongAdder + histogram    ClickHouse (prod)
        |                                   |                        gt-analysis
        |                           ax-agent-transport                 dead = static-unreachable
        |                             JCTools MPSC bounded queue         AND runtime-unobserved
        |                             daemon drain thread                cascade over call graph
        |                             drop-on-full, never block          observation windows
        |                             circuit breaker                  gt-api -> gt-mcp
        |                                   |                                 -> gt-ide-vscode
        +---------- artifact manifest ------+---------------------------------> joined by build sha
```

### Module boundaries (strict, one-way dependencies)

| module | language | depends on | must NOT depend on |
|---|---|---|---|
| `ax-agent-core` | Java 8 | ASM/ByteBuddy (shaded) ONLY | transport internals, any JSON lib, any logging framework |
| `ax-agent-transport` | Java 8 | JCTools (shaded) | agent-core internals; talks via a narrow SPI |
| `ax-static-analysis` | Java 17 | ASM | anything runtime |
| `gt-collector` | Python or Go | gt-store via interface | agent internals |
| `gt-store` | - | - | callers (it is a port) |
| `gt-analysis` | Python | gt-store interface | collector transport details |
| `gt-api` | Python | gt-analysis | storage engine specifics |
| `gt-mcp` | Python | gt-api | everything below it |
| `gt-ide-vscode` | TypeScript | gt-api HTTP contract | everything else |

**Design principles (non-negotiable):**
- **Ports & adapters.** `gt-store` is an interface; SQLite and ClickHouse are adapters.
- **Agent has zero third-party runtime deps** beyond shaded ASM/ByteBuddy/JCTools.
  No SLF4J, no Jackson, no Guava. Those cause classloader conflicts in host apps.
- **Fail-open, always.** Any agent failure degrades to "no data", never to app impact.
  Every agent entry point is wrapped so a Throwable can never escape into app code.
- **The wire protocol is a versioned contract**, not a shared object model.
- **No shared mutable state between modules.** Snapshot-and-hand-off only.

---

## 4. The hot path design (the core IP)

### Tier 1 — coverage probe, all in-scope methods

Per instrumented class we inject a static field holding a `boolean[]`. Each instrumented
method gets a **constant int index resolved at transform time**. The injected code is:

```java
if (!probes[IDX]) probes[IDX] = true;    // IDX is a bytecode constant, not a lookup
```

Three properties that make this ~1-2 ns:
- **No map lookup, no String, no allocation.** GETSTATIC + constant index + array access.
- **Read-then-store, not blind store.** After first execution the line is read-only and
  shared across cores, so there is NO cache-line invalidation. A blind store (JaCoCo's
  approach) writes forever and causes false sharing across cores. (assumption A2)
- **Perfectly predicted branch** after warmup (always not-taken).

### Tier 2 — boundary metrics, allowlist only (~50-200 methods)

Controllers, service boundaries, DB/MQ wrappers. Per method:
- `LongAdder` calls, `LongAdder` errors (keyed by exception class name)
- latency histogram (HdrHistogram or DDSketch) (assumption A8)
- `System.nanoTime()` x2 (assumption A4)

Explicitly NOT applied to all methods. This is what keeps us inside the budget.

### Tier 3 — branch coverage, pod subset only

JaCoCo, on 1 pod in N (or canary pods only), rotating. Justification: "did this branch
ever execute" **unions across pods**, so we need *some* pod over *enough time*, not every
pod. This caps the documented 10-18% JaCoCo overhead to 1/N of the fleet. (assumption A3)

### Tier 4 — performance profile (optional, separate)

Sampling profiler (async-profiler / Pyroscope) at ~2-3% CPU for hot paths and flame
graphs. Sampling can NEVER prove absence, so it cannot feed dead-code detection. It is
strictly the "where is time going" source. (assumption A12)

### Transport

- Hot path writes to a **JCTools bounded MPSC queue**; on full, **drop and increment a
  dropped counter**. Never block an app thread, never allocate on the app thread.
- A single daemon thread drains, batches, compresses, and POSTs.
- Circuit breaker: on collector failure, back off exponentially and keep dropping.
- The agent's own memory is bounded and pre-allocated at startup. (assumption A7)

---

## 5. Dead code: the correctness rule

> **dead = static-unreachable AND runtime-unobserved. Everything else is "unknown".**

Static and runtime fail in opposite directions:
- Static misses reflection, Spring AOP proxies (JDK dynamic / CGLIB), annotation-driven
  entry points (@Scheduled, @EventListener, @Bean, @KafkaListener). (assumption A5)
- Runtime misses anything real but rare (quarterly batch, DR failover, Feb 29).

Requiring both to agree makes each cover the other's blind spot.

Additional safety, modeled on Meta's SCARF:
- Err toward false negatives.
- Text-search fallback across the repo before proposing a deletion.
- **Always** human code review. Never auto-delete.
- Always render the observation window (`not invoked in 90d`), never a bare "dead".
- Track deploy gaps: "not invoked" while the pod was not running is not evidence.
- Cascade over the static call graph: an unreachable entry point makes its exclusive
  subtree deletable. This is where the leverage is.

---

## 6. Open source we will stand on (do not rebuild)

| need | tool | owner |
|---|---|---|
| bytecode instrumentation | ByteBuddy / ASM | Winterhalter / OW2 |
| branch coverage probes | JaCoCo | EclEmma |
| lock-free queues | JCTools | Wakart |
| latency histograms | HdrHistogram / DDSketch | Azul / Datadog |
| sampling profiler | async-profiler / Pyroscope | Pangin / Grafana |
| service topology + RED | OTel Collector servicegraph + spanmetrics | CNCF |
| canary judge | Kayenta + Argo Rollouts | Netflix / CNCF |
| k8s agent injection | Kyverno policy | Nirmata / CNCF |
| storage | ClickHouse (prod), SQLite (dev) | ClickHouse Inc |
| benchmarking (the gate) | JMH | OpenJDK |
| static call graph | TBD — see A5 | - |

---

## 7. ASSUMPTIONS REQUIRING INTERNET VALIDATION

No code is written until every one of these has a verdict. **We assumed before and it
hurt us.**

| ID | assumption | why it matters | if false |
|---|---|---|---|
| **A1** | ByteBuddy `Advice.withCustomMapping().bind(...)` can inject a per-method **constant int** resolved at transform time | the entire zero-lookup probe design | drop to raw ASM MethodVisitor (JaCoCo's approach) |
| **A2** | `if(!p[i]) p[i]=true` beats a blind store in steady state by avoiding false sharing; and actual ns cost of both | tier-1 budget | revisit probe layout / padding |
| **A3** | JaCoCo ~10% / 18%-under-load figures are real; method-only probing is materially cheaper than branch; no runtime kill switch; offline instrumentation viable | tier-3 feasibility | branch coverage may be canary-only or dropped |
| **A4** | `System.nanoTime()` costs ~20-25ns and is the cheapest monotonic source on Linux/JDK17; no cheaper alternative (JFR? coarse clock?) | tier-2 budget | use a coarse clock / sampled timing |
| **A5** | A usable OSS static call graph builder exists for Java that handles Spring DI/AOP; and Spring Actuator `/mappings` is a legitimate entry-point source | the dead-code cascade | build a conservative ASM-only graph and widen "unknown" |
| **A6** | our agent + JaCoCo + OTel javaagent can coexist in one JVM; safe ordering exists | the whole stack | consolidate into a single agent |
| **A7** | JCTools bounded MPSC with drop-on-full is the correct zero-alloc handoff; known prod patterns exist | transport safety | ring buffer of our own / Disruptor |
| **A8** | HdrHistogram or DDSketch is mergeable across pods, bounded memory, cheap to record | tier-2 p90 | fixed log-bucket histogram |
| **A9** | Kyverno can inject javaagent + initContainer + JAVA_TOOL_OPTIONS reliably; correct failurePolicy; the JAVA_TOOL_OPTIONS-append problem has a known solution | deployment | write a real mutating webhook |
| **A10** | classloader isolation for agent classes is a solved problem (Elastic's invokedynamic approach); known breakages: OSGi, Spring Boot fat jars, GraalVM native image | agent robustness | restrict supported environments explicitly |
| **A11** | **startup transform CPU cost** under a k8s CPU limit is a real throttling risk; mitigations exist (narrow scope, offline instrumentation, lazy transform) | the likeliest incident vector | must add a startup budget + circuit breaker |
| **A12** | sampling profilers genuinely cannot prove absence, and 2-3% CPU is accurate | tier-4 role | adjust the tiering |
| **A13** | exception **class name** capture is genuinely cheap and is not considered PII/payload | product boundary | drop it |
| **A14** | boolean[] probe arrays merge correctly across pods/restarts and memory is fixed per class | correctness of coverage union | add per-pod reconciliation |

---

## 8. Build order (only after green light)

1. `ax-static-analysis` — inventory + call graph + entry points (no runtime risk, unblocks everything)
2. `ax-agent-core` — transformer + probes + **JMH benchmark proving the budget**
3. `ax-agent-transport` — queue + drain + backpressure
4. `gt-collector` + `gt-store` — ingest + merge
5. `gt-analysis` — the dead-code join + cascade
6. `gt-api` + `gt-mcp`
7. `gt-ide-vscode`
8. `deploy/` — Kyverno policy + helm

**Gate between 2 and 3: JMH must demonstrate the per-probe cost. If it misses budget,
the design changes before anything else is built.**
