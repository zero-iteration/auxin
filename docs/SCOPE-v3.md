# Scope v3 — one agent, self-hosted

**Decision (owner):** auxin is used by its author on his own systems. Not distributed to other
companies. Therefore: **ONE agent in the runtime path, and that agent is auxin.**

## What this cancels
- The OTel **distro** (embedding OTel's agent) — cancelled. Licence, redistribution, customer
  fallback paths and OTel-internals fragility are all moot for self-hosted use.
- The **three-agent shipping shape** (auxin + JaCoCo + OTel) is no longer the target. `g5/` is
  retained as a **compatibility regression test** — proof that auxin does not break a host that
  already runs OTel or JaCoCo — but it is not how this is deployed.
- **Tier-3 branch coverage via JaCoCo** — already suspended (A3: no method-only mode, no runtime
  kill switch, 17-25%+ overhead). Now formally dropped from the runtime path.

## What this means auxin must now do itself
Previously answered with "run OTel alongside". That answer is gone, so:

| need | previous answer | v3 answer |
|---|---|---|
| TPS / rate / p90 per controller | Tier-2 allowlist (~200 methods) | **same mechanism, made easy to widen** — measured cost per added method, not guessed |
| "what calls what" at runtime | "use OTel spans" | **NEW: sampled runtime call-edge tier** |
| service topology across processes | OTel `servicegraph` connector | **out of scope** — single-process call graph only. Cross-process needs context propagation, which needs per-request allocation, which forfeits the zero-allocation and strippability properties. Not worth it for one owner's systems. |

## The new tier, and why it is affordable
Runtime call edges were originally cut because a thread-local caller stack costs a push/pop on
**every** method entry and exit (~10-20 ns/call), breaks zero-allocation, and — the real killer —
means nothing can ever be stripped, because the edges are needed forever.

**Sampling fixes all three.** Record edges for 1-in-N *entries into a root* (a tier-2 boundary
method), not for every call. Between sampled roots the agent does exactly what it does today.
Statistically real "what calls what", at 1/N of the cost, and Tier-1b keeps working because the
edge tier is independent of the coverage probes.

## Invariants that do NOT change
Zero allocation on the app hot path. Tier-1b strips to literally zero steady-state overhead.
Fail-open absolutely. Default-deny scope. No values recorded — ever.

## Postscript: the distro path was fragile, confirmed after cancelling
Research completed after the decision, and it supports it. In OTel 2.31.1:
- **No supported SPI provides `Instrumentation`.** `AgentListener` has exactly one method,
  `afterAgent(AutoConfiguredOpenTelemetrySdk)` — it imports `Instrumentation` only for a javadoc
  link. `BeforeAgentListener` also gives only the SDK and lives in `javaagent-tooling`.
- The static accessor that does exist, `InstrumentationHolder.getInstrumentation()`, is in
  `javaagent-bootstrap`, which OTel's own docs call *"internal and its APIs are considered
  unstable."*
- The public `InstrumentationModule` SPI exposes ByteBuddy's `AgentBuilder.Transformer`, **not** a
  `java.lang.instrument.ClassFileTransformer` — so it cannot register our retransform-incapable
  installer.
- The only remaining route is wrapping `OpenTelemetryAgent.premain`: mechanically possible (public,
  and the manifest check only null-checks `Premain-Class`), used by four shipping vendor distros,
  and **undocumented, outside every stability guarantee, and not exercised by OTel's own
  `examples/distro`.**
- Also: the extension SPI artifacts are published `-alpha`, and per OTel's VERSIONING.md *"NONE of
  the guarantees described above apply to alpha artifacts."*

=> Embedding OTel would have put our ordering guarantee — the one property E1 measured and the whole
Tier-1/Tier-1b design rests on — on top of an unsanctioned entry point. **Right call to cancel.**


---

# SCOPE v3.1 — one agent, tracing included (owner's decision, 2026-09-13)

> *"we can have one agent only, its fine if it adds overhead for some requests"*

The per-request tracer was built as a **separate** jar because it breaks an invariant: a trace probe
can never be stripped, so Tier-1b's "steady state -> zero" is false for any class in the traced
scope. That separation was the cautious answer. The owner has now taken the trade explicitly, so
the tracer folds into `ax-agent` and there is **one `-javaagent` jar, full stop.**

## Shipped (2026-09-13)
`modules/ax-trace/` is deleted. One jar, one `Premain-Class` (`io.auxin.agent.AuxinAgent`), one
ASM relocation (`io.auxin.shaded.asm`), one transformer pair. `TRADE-OFFS.md` and
`TRACE-CONTRACT.md` moved to `modules/ax-agent/docs/`. The 53-assertion trace suite moved to
`modules/ax-agent/smoke/run-trace-smoke.sh` and the script **asserts** the migrated count is still
53 so a lost assertion is a failure rather than a smaller number nobody reads. Its JMH bench is
gate **G7** in `bench/run-gates.sh`.

## What changes, and what does not
- **Tracing is off by default.** `ax.trace.enabled=true` opts in. A JVM that never enables it pays
  nothing: the merged jar's untraced cost is the same code path as before.
- **When tracing is on, Tier-1b auto-strip is disabled for the intersection of the traced and
  instrumented scopes** -- automatically, and **loudly**: a premain WARN, a line in the startup
  summary, and `tier1bDisabledByTrace` on the wire so the server and UI can render *why* steady-state
  overhead is not zero on this JVM.
- **Classes outside the traced scope still strip normally.** That is the entire point of scoping:
  you pay where you asked to and nowhere else. It is asserted, not assumed.
- `ax.trace.strip.conflict=disable-strip|refuse|allow` keeps the old refusing behaviour reachable.

## Why "loudly" is the load-bearing word
The danger was never the overhead -- it was that **Tier-1b would still *succeed*** on a traced
class. Nothing about a trace probe looks like a tier-1 shape, so the strip matches, the counter
increments, and you get a **true counter and a false conclusion**: a JVM that reports
`classesStripped` while keeping probes on the hot path forever. That is the exact signature of the
nine "reports success while doing nothing" bugs this project has already found. Taking the trade is
fine; taking it silently would not be.

## What this costs, stated plainly
- Traced classes keep their probes for the life of the JVM -- the zero-steady-state property is
  **given up**, not deferred, for that scope.
- While any thread is inside a trace, every other thread's probes fall through the gate into a
  `ThreadLocal` that finds nothing: **2.6-5.0 ns/site**, allocation-free, bounded by the rate cap.
- The untraced path remains **allocation-free and sub-nanosecond**. RE-MEASURED from the merged
  jar (`bench/run-gates.sh g7`, 2026-09-13): **0.17-0.76 ns/probe site at 0.00 B/op** across
  JDK 11/17/21 and 1/4/10 threads, against 0.17-0.83 ns pre-merge. **Merging did not move it.**
- One number is NOT re-quoted from the old run: the *contended* arm (a trace open on another
  thread) measured **7.2 ns/site at 10 threads on JDK 17** against the recorded 3.4. It is
  reproducible in this session but is not attributed to the merge — the `baseline` arm moved
  23.1 -> 16.8 ns/op in the same session, so the machine profile differs, and the code on that
  path is byte-identical. See `modules/ax-agent/docs/TRADE-OFFS.md` §3.
