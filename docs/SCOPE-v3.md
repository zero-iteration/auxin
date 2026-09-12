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
