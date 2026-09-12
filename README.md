# auxin

**Production ground truth for what code actually runs.** A JVM agent that records, per method:
was it ever executed, how hot is it, what does it throw — and joins that against a build-time
static inventory to answer *"is this code dead?"* honestly enough to act on.

Records **no values**: no arguments, no return values, no request bodies. Only invocation
booleans, counters, exception class names, and normalized shapes. That is a permanent product
boundary, not a phase-one limitation.

## Scope: one agent

Auxin is the **only** agent in the runtime path. It is not a distro, it does not embed
OpenTelemetry, and it does not need a second or third agent attached to be useful. See
`docs/SCOPE-v3.md` for the decision and what it rules out.

It still *coexists* with OTel and JaCoCo if you already run them — that is a verified compatibility
property (gate `g5/`, 972 assertions: all three attached under traffic, 4,430 spans, and 71 methods
compared between two coverage engines with **0 disagreements**) — but it is not the deployment shape.

| question | answer |
|---|---|
| was this method ever executed? | **yes** — every in-scope method, one bit, near-zero cost |
| which branch arms never ran? | no — dropped from the runtime path (see A3) |
| TPS / error rate / p50-p90-p99 | **yes** — automatically on every detected entry point (controllers, `@Scheduled`, listeners, `main`, SPI), plus a glob allowlist, under an enforced budget |
| what calls what, at runtime? | **yes, sampled** — decided once per boundary-method entry, so unsampled paths cost zero |
| what calls what, statically? | **yes** — CHA graph, every edge labelled `exact \| cha \| unresolved`, and measured ~61% unsound, so it only ever corroborates |
| request flow across services | **no.** Needs context propagation, which needs per-request allocation, which forfeits zero-allocation and strippability. Out of scope by choice |

## Why this can be done without hurting the host

| concern | answer |
|---|---|
| per-call cost | 5 instructions, no branch, no allocation, no stack map frame |
| steady-state cost | **zero** — once a method's probes are set, they are removed by retransformation |
| startup CPU | one transformer, name-only first-pass matching, hard CPU budget + circuit breaker |
| scope | **default-deny**; unset `ax.include.packages` instruments nothing |
| failure mode | fail-open everywhere; a Throwable can never escape into application code |
| coexistence | registers retransform-**incapable**, so it runs before OpenTelemetry and its probes survive OTel's retransform batch |

Calibration: Picnic ran JaCoCo — heavier, branch-level probes — in production Kubernetes at
**0.03%** overhead with package scoping. Scoping is the dominant lever, not probe cleverness.

## Layout

```
docs/      PLAN.md (v1, historical) · PLAN-v2.md (current) · VALIDATION.md · CONTRACTS.md · TOOLCHAIN.md
modules/
  ax-manifest   shared contract types + zero-dependency JSON   (Java 8)
  ax-static     ASM inventory, probe-index assignment, entry points, CHA call graph (Java 17)
  ax-agent      ProbeInstaller + ProbeStripper, condy probes, long[] ring, drain thread (Java 8)
  ax-server     store · collector · analysis · api · mcp        (Python 3, stdlib only)
bench/     JMH gates G1-G4
deploy/    Kyverno injection policy + Helm chart + PREFLIGHT + RUNBOOK
demo/      dependency-free target app with MEASURED dead-code ground truth
```

## How it decides something is dead

```
dead = runtime-unobserved
   AND static-unreachable          (corroborating only - NEVER a cascade)
   AND NOT suppressed              (.auxin/suppress.txt, checked into the repo)
   AND NOT public-API-surface      (downstream consumers are invisible to us)
   AND NOT short-circuitable       (a warm @Cacheable method never runs)
   AND dynamically-observable      (constants and single-instruction methods cannot be covered)
   AND window covers required phases
   AND the class was observed LOADED
otherwise -> UNKNOWN
```

`UNKNOWN` is the default. A verdict is a **standing claim**, re-derived every run and silently
revoked by any new observation. Nothing is ever auto-deleted.

## Honest posture

The closest published analogue to this architecture (CQSE: static reachability + a runtime
loaded-class list, applied iteratively) measured **72% precision**, of which about half were
actually removable — roughly **1 in 3 flagged items genuinely deletable**. JShrink's held-out
test: **~15% still break on unseen executions** even with static and dynamic combined. Nothing
published anywhere exceeds ~88% precision.

**We do not claim a precision number.** We claim a false-negative-biased posture with the
observation window attached, and we measure Google Tricorder's *effective false positive* rate
— "any report where a user chooses not to take action" — with auto-disable at 25%.

## Why bother

Meta measured dead-code removal at **odds ratio 5.2, a 90% decrease in SEV-causing diffs**
(76% -> 24%), and ~41% faster diff authoring. Significant for dead-code removal, and *not* for
the other refactoring practices studied. Separately, ICPC 2011: for **over 70%** of entirely
unused features, *it surprised the stakeholders that they were not used at all.*

## Not supported

GraalVM native image (**impossible** — no bytecode at runtime). JDK 24+ AOT cache (JEP 483) is
**mutually exclusive** with any classfile-rewriting agent; Datadog and Dynatrace are already
blocked by this. OSGi / WildFly / Spring Boot fat jar / JPMS must be **tested and claimed**,
never assumed.

## Status

See `docs/VALIDATION.md` — 14 assumptions validated against primary sources, 59 design changes,
gates E1/E2 proven locally on JDK 17. Every "not verified" is stated as such.
