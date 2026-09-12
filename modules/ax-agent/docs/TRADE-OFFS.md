# The trace tier — what it gives up, what that buys, and what it costs

Read this before turning `ax.trace.enabled=true` on anything you care about.

> **SCOPE-v3.1 (2026-09-13), owner's decision.** This was `modules/ax-trace`, a **second**
> `-javaagent`. It is now a **tier inside `ax-agent`, shipped in the one `ax-agent.jar`, off by
> default.** The owner took the trade explicitly: *"we can have one agent only, its fine if it
> adds overhead for some requests."* The ledger below is unchanged — the module boundary was
> never what made the invariants hold — but §2 is: the mutual exclusion that used to **refuse**
> now **disables Tier-1b's auto-strip for the intersection of the two scopes**, automatically,
> loudly, and only there. Sections marked *(pre-merge)* describe the separated design and are
> kept because the reasoning still explains why each piece is shaped the way it is.

auxin's stated invariants (`docs/SCOPE-v3.md`, "Invariants that do NOT change") are:

> Zero allocation on the app hot path. Tier-1b strips to literally zero steady-state overhead.
> Fail-open absolutely. Default-deny scope. **No values recorded — ever.**

**This tier breaks two of those outright and one structurally.** That is not an oversight and
it is not a tuning parameter; it is the design. It therefore keeps its own master switch (default
**off**), its own endpoint, its own document and its own contract file — and it is still never
folded into tier-1, tier-1b, tier-2 or the call-edge tier as a *behaviour*. What it no longer
keeps is its own jar and its own premain.

**Why the separation was dropped, honestly.** The separate jar bought one real thing: a trace
probe could not reach tier-1's cost profile by accident. It cost two real things: a second
`-javaagent` to deploy and reason about, and a Tier-1b resolution that depended on
*`-javaagent` ordering* — one premain setting a system property and hoping the other had not read
it yet, verified reflectively three seconds after startup and escalated to
`stripConflictUnresolved` when it had not. One premain deletes that whole class of bug. The
protection the separation gave is now a scope check, which is stronger: it is evaluated per class,
on the same `Scope` objects the transformer uses.

---

## 1. The ledger

| invariant | status | why |
|---|---|---|
| **No values recorded — ever** | **BROKEN, deliberately** | The whole point is value-bearing capture. Narrowed to a closed set of *structural* observations (§4): sizes, null-ness, enum names, primitive numbers, booleans, exception class names. |
| **Aggregate-only** | **BROKEN, deliberately** | One document per traced request, with a per-request frame tree. That is the product. |
| **Strippable (Tier-1b → zero steady state)** | **STRUCTURALLY IMPOSSIBLE** | §2. This is the one that cannot be bought back at any price. |
| **Zero allocation on the app hot path** | **HELD** — measured 0 B/op on every untraced arm at 1, 4 and 10 threads (§3). A *traced* request allocates, obviously. |
| **Fail-open absolutely** | **HELD** — one latch, one counter, one WARN; exercised by the smoke suite's RUN J. |
| **Default-deny scope** | **HELD, and harder than the coverage scope's** — there is no instrument-everything switch in this tier and there is not going to be one, because a wide scope here is a data-exposure surface and not merely a cost. |

---

## 2. The one that cannot be bought back: a trace probe is never strippable

Tier-1b's claim is that a coverage probe's steady-state cost reaches **literally zero**: once
every probe in a class has fired, the stripper retransforms the class and removes the probe
instructions, so the hot path is byte-for-byte the original code.

That works because a coverage probe is **done once it has fired**. A trace probe is never done:
it is needed on the one future request nobody has made yet, and you cannot know in advance which
request that will be. So for any class in the traced scope, **"steady state reaches zero" is not
slower — it is false.**

*(pre-merge: the `ax-trace.jar` manifest said `Can-Retransform-Classes: false`, because there
was nothing in it to retransform. The merged jar says `true`, because Tier-1b lives in it — which
is precisely why the per-class refusal above has to exist rather than a manifest flag.)*

### The failure mode this creates, and why it is the dangerous kind

Tier-1b would still *succeed*. It matches tier-1 probe shapes, and nothing this module emits
looks like one, so the stripper removes the coverage probes, increments `classesStripped`, and
reports a clean strip — for a class whose hot path still carries every trace probe. **A true
counter and a false conclusion.** An operator reads "overhead is now zero" and is wrong by
however much the trace probes cost.

### Therefore: the trade is TAKEN, at startup, per scope, and it is loud

`StripConflict.detect()` runs in `premain` from the one `Options` object, computes the
**intersection** of `ax.trace.include.packages` and `ax.include.packages` (no overlap, no
conflict — tracing `com.acme.search` while coverage covers `com.acme.billing` leaves billing's
claim intact), and:

| `ax.trace.strip.conflict` | behaviour |
|---|---|
| **`disable-strip`** (DEFAULT since SCOPE-v3.1) | The trace tier arms. **Tier-1b's auto-strip is disabled for the intersection and for nothing else** — a class covered by `ax.include.packages` but outside the traced scope strips exactly as it always did. One WARN at premain naming the scope, a field in the startup summary, and `tier1bDisabledByTrace` + `tier1bTraceBlockedClasses` + `tier1bTraceScope` on the wire. |
| `refuse` (the pre-merge default, still reachable) | **The trace tier does not arm.** Nothing is instrumented for tracing. Tier-1b keeps every property it promised. The message names both settings, explains *why* it is a contradiction, and lists the ways out. |
| `allow` | Both arm; Tier-1b strips traced classes too. The operator has said explicitly that they accept a `classesStripped` count that does not mean zero overhead, and it is reported. |

**How the refusal is enforced.** `DrainThread.strippable()` asks
`StripConflict.blocksStrip(class)` before anything else, and so does the `stripNow()` break-glass
hook — a manual strip must not be the way this invariant gets bypassed one call at a time. Each
refusal increments `tier1bTraceBlockedClasses` (distinct classes, capped at 10 000 names) and says
so once per class.

**What the merge deleted, and it is worth naming.** The old `disable-strip` set
`ax.strip.enabled=false`, a **JVM-wide** switch, from one premain, hoping the other premain had
not read it yet; it then verified that reflectively after three seconds and escalated to
`health.stripConflictUnresolved`. That ordering assumption no longer exists — there is one
premain and one `Options` — so `stripConflictUnresolved` is now **structurally always 0**. It
stays on the wire for compatibility. And the suppression got *narrower*: JVM-wide became
scope-wide.

The trade is also printed in the startup line on every armed JVM, so nobody has to read this
file to learn that a property they were promised no longer holds:

```
[auxin/trace] INFO  TRADE-OFF, stated at startup: trace probes are NOT strippable, so for
traceapp the "steady state reaches zero overhead" property of Tier-1b does not apply while this
tier is armed. Tier-1b auto-strip is therefore DISABLED for that intersection and for nothing
else; classes covered by ax.include.packages but outside the traced scope still strip to zero.
Untraced requests still pay only one static load and one branch per probe site (measured; see
modules/ax-agent/docs/TRADE-OFFS.md). Master switch: ax.trace.enabled=false.
```

---

## 3. The measured cost

`bench/run-gates.sh g7` (**GATE G7**, migrated from `modules/ax-trace/bench/run-bench.sh`). JMH
1.37, `AverageTime`, 2 forks × (5 × 1 s warmup + 8 × 1 s measurement), `-prof gc`. The arms call
the **shipped** `io.auxin.trace.runtime.TraceRuntime` out of
`modules/ax-agent/target/ax-agent.jar`, so HotSpot's inlining decision about the shipped code is
what is reported.

> ### RE-MEASURED FROM THE MERGED JAR (2026-09-13)
>
> The pre-merge numbers are kept below, greyed by this note rather than deleted, because the
> comparison is the point. **The untraced claim survived the merge**; one number did not, and it
> is named rather than quoted from the old run.
>
> | arm | sites | t=1 | t=4 | t=10 | B/op |
> |---|---|---|---|---|---|
> | `baseline` | 0 | 7.972 ± 0.243 | 9.991 ± 0.427 | 16.780 ± 0.264 | **0.00** |
> | `enterExit` | 2 | 8.386 ± 0.024 | 11.500 ± 0.157 | 18.370 ± 0.318 | **0.00** |
> | `fullProbeSet` | 6 | 9.051 ± 0.065 | 12.184 ± 0.271 | 19.885 ± 0.434 | **0.00** |
> | `obsRefOnly` (standalone) | 1 | 0.592 ± 0.012 | 0.719 ± 0.005 | 1.074 ± 0.010 | **0.00** |
> | `wrapUntraced` (standalone) | 1 | 0.560 ± 0.003 | 0.660 ± 0.004 | 0.898 ± 0.024 | **0.00** |
> | `fullProbeSetWhileAnotherThreadTraces` | 6 | 23.389 ± 0.709 | 32.587 ± 0.285 | 60.260 ± 0.878 | **0.00** |
>
> **Per probe site, untraced, JDK 17:** `enterExit` **0.207 / 0.755 / 0.483 ns** and
> `fullProbeSet` **0.180 / 0.366 / 0.428 ns** at 1 / 4 / 10 threads. JDK 11 t=1: **0.173 /
> 0.244 ns**. JDK 21 t=1: **0.180 / 0.211 ns**. **0.00 B/op on every untraced arm on every JDK.**
> That is at or below the pre-merge figure everywhere, so **merging did not move the untraced
> cost**.
>
> **The number that DID move, stated rather than hidden.** The contended arm at 10 threads on
> JDK 17 measured **60.3 ns/op (7.2 ns per probe site)** against the pre-merge **43.5 ns/op
> (3.4 ns/site)** — reproduced on a second run. It is **not** attributed to the merge, because
> the code on that path (`TraceRuntime`'s `ThreadLocal` miss) is byte-identical and the
> **`baseline` arm moved 23.074 → 16.8 ns/op in the same session**, i.e. the whole machine
> profile differs from the pre-merge run. The honest statement is: *this session measures the
> contended path higher than the recorded figure, the cause is unresolved, and the recorded
> figure is not being re-quoted as if it had survived.* The single-thread contended figures
> (1.33 ns on JDK 11, 2.55 ns on JDK 21, 2.57 ns on JDK 17) match the old run closely, which is
> what makes machine state the leading explanation.

*(Everything below this point is the PRE-MERGE measurement, from `ax-trace.jar`.)*

> **Hazard, the same one `docs/TOOLCHAIN.md` #2 states for ax-agent's gates.** This is aarch64
> (Apple M4, 10 cores, 128-byte line). Production is x86_64 Linux. A volatile int read is an
> `ldar` here and a plain `mov` there, so **the gate numbers are if anything pessimistic** — but
> they are provisional until re-run on x86_64 Linux. `bench/run-bench.sh` is one command there.

### JDK 17.0.18, aarch64

| arm | sites | t=1 ns/op | t=4 ns/op | t=10 ns/op | B/op |
|---|---|---|---|---|---|
| `baseline` (uninstrumented) | 0 | 8.579 ± 0.553 | 10.355 ± 0.224 | 23.074 ± 0.348 | **0.00** |
| `enterExit` | 2 | 9.755 ± 0.564 | 11.482 ± 0.227 | 24.740 ± 0.740 | **0.00** |
| `fullProbeSet` | 6 | 10.759 ± 0.833 | 13.409 ± 0.168 | 27.019 ± 0.785 | **0.00** |
| `obsRefOnly` (standalone) | 1 | 0.591 ± 0.009 | 0.881 ± 0.017 | 1.333 ± 0.035 | **0.00** |
| `wrapUntraced` (standalone) | 1 | 0.558 ± 0.010 | 0.815 ± 0.011 | 1.035 ± 0.029 | **0.00** |
| `fullProbeSetWhileAnotherThreadTraces` | 6 | 24.080 ± 0.542 | 40.067 ± 1.888 | 43.478 ± 1.516 | **0.00** |

**Per probe site, untraced:**

| | t=1 | t=4 | t=10 |
|---|---|---|---|
| `enterExit` | **0.588 ns** | **0.563 ns** | **0.833 ns** |
| `fullProbeSet` | **0.363 ns** | **0.509 ns** | **0.658 ns** |
| standalone (absolute, includes JMH's own floor) | 0.56–0.59 ns | 0.82–0.88 ns | 1.04–1.33 ns |

### JDK 11.0.32.1 and 21.0.12.1, aarch64, t=1

| arm | sites | JDK 11 ns/op | JDK 17 ns/op | JDK 21 ns/op | B/op |
|---|---|---|---|---|---|
| `baseline` | 0 | 8.748 ± 0.057 | 8.579 ± 0.553 | 7.849 ± 0.096 | **0.00** |
| `enterExit` | 2 | 9.257 ± 0.295 | 9.755 ± 0.564 | 8.181 ± 0.035 | **0.00** |
| `fullProbeSet` | 6 | 10.227 ± 0.099 | 10.759 ± 0.833 | 9.050 ± 0.026 | **0.00** |
| `fullProbeSetWhileAnotherThreadTraces` | 6 | 16.839 ± 0.281 | 24.080 ± 0.542 | 23.717 ± 1.005 | **0.00** |

Per probe site, untraced: **0.25 ns (JDK 11)**, **0.36–0.59 ns (JDK 17)**, **0.17–0.20 ns
(JDK 21)**. Contended: 1.35 / 2.58 / 2.65 ns. Zero allocation on every arm on every JDK.

(The standalone `obsRefOnly` / `wrapUntraced` arms measure a bare probe call with no baseline
work, so their absolute figure includes JMH's own per-op floor, which differs by JDK — 1.68 ns on
11 against 0.56–0.64 ns on 17 and 21. The **relative** arms are the ones to read.)

### The verdict, plainly

**The claim holds, before and after the merge. 0.17–0.83 ns per probe site pre-merge and
0.17–0.76 ns post-merge, across JDK 11/17/21 and 1/4/10 threads, allocation-free everywhere.** An untraced request pays one `volatile int` load and one perfectly-predicted branch
per probe site, and nothing else — no thread-local read, no clock, no allocation.

For scale: G1 rejected ax-agent's blind tier-1 store at **+0.72–0.77 ns/probe** as too expensive.
This module's untraced probe site is at or below that number, and unlike the blind store it does
not take a cache line Exclusive, which is why it does not degrade with thread count the way the
blind store did (blind: 7.45 → 18.14 ns/op from t=1 to t=10; `fullProbeSet` here tracks the
baseline).

### The number that is NOT near-zero, and it is not hidden

**While ANY thread is inside a traced request, every OTHER thread's probes fall through the gate
into a `ThreadLocal.get()` that finds nothing: 2.6–5.0 ns per probe site pre-merge, and up to
7.2 ns/site at 10 threads in the post-merge session (see the re-measurement note above).** That is
4–17× the untraced cost.

This is the same trade `EdgeRuntime` documents for the call-edge tier, and it is bounded by the
same two things: the rate cap (5 traces/minute default) and `max.concurrent=1`. A trace of a
10 ms request leaves that window open for 10 ms out of every 12 seconds — about 0.08 % of wall
clock. A deployment that traces continuously sits at the higher figure instead, and should raise
the cap or narrow the scope. It is still allocation-free.

### Class-file cost, measured (`javap`, smoke target)

`traceapp.SearchService`: 6,259 → **7,577 bytes (+21 %)**, 18 → **31 stack map frames**.

The frame count is **exactly one new frame per throw handler** (13 handlers over 14 instrumented
methods — `<init>` gets none, §6) and **not one anywhere else**: no probe adds a local, no probe
adds a branch, and the operand duplication for a branch arm is balanced before the next
instruction. Verified per-JDK by `javap` in the smoke suite.

---

## 4. Values: what is recorded, and why it is PII-safe by construction

The closed set (`TRACE-CONTRACT.md` §3.4): `size`, `null`, `num`, `bool`, `enum`, `len`,
`class`, `redacted`, `error`.

**There is no `value` kind, no `toString` kind, and no serialiser anywhere in this module.** That
is a claim about the code's *shape*, not about a filter's coverage, and it is the reason this is
safe to point at a service nobody has audited:

- a `String` contributes its **length**, never its content — a `String` is where free text lives;
- a `List` contributes its **size**, never an element — the first element of a list of 126 fares
  is a fare;
- an exception contributes its **class name**, never its message and never a stack trace
  (`docs/VALIDATION.md` line 821: OTel's semantic conventions warn on `exception.message`, not on
  `exception.type`). The smoke suite asserts the message — which contains an e-mail address —
  does not appear in the document;
- no field reflection anywhere. Only methods a human named in the projection config.

`Collection.size()` at frame entry **and at exit** is the single highest-value signal: "126 in,
94 out" localises where data was dropped without recording the data, and it is the only way to
see a method that filters a collection *in place*.

### The redaction pass: two mechanisms, because they fail differently

1. **A field-name denylist at config load** (`Redaction.deniedName`). Normalised
   (`get`/`is` stripped, non-alphanumerics removed, lower-cased) **substring** matching against
   ~60 terms. Substring matching over-blocks — `getKeyCount` is refused because it contains
   `key` — and that is the correct direction for a denylist whose failure mode is a leak.
   `ax.trace.redact.allow` exists for the specific false positive.
2. **A value-shape check at capture** (`Redaction.deniedShape`). 12–19 digit runs (cards, IBAN
   tails, Aadhaar), 10-digit runs beginning 6–9 (Indian mobiles), `@`-with-a-dot, PAN-shaped
   `AAAAA9999A`. It exists because the name gate cannot see through `getRef`, `getCode`, `getNum`.

**There was no redaction denylist anywhere in the auxin tree before this tier** — checked with
`grep -rn 'redact|denylist|PII|scrub'`, which matched only prose in `docs/`. This is the first
one, so it is documented here rather than adopted.

Redaction is the **second** line of defence, for the one place an operator can aim the tracer at
a real value: the projection config. It is not the reason the structural capture is safe.

### Production safety, table stakes

- **Master switch, default OFF.** Not instrumented, not armed, not registered. The smoke suite's
  RUN A asserts 0 frame sites and 0 documents from three requests carrying the header.
- **Rate cap**, sliding-window (`n` in any 60 s, not `n` per calendar minute — a fixed window
  lets `2n` through across a boundary). Default 5/min; `0` means never grant, which is a kill
  switch distinct from `enabled=false` because the instrumentation stays in place.
- **Refuses to arm in production without a token.** An unauthenticated trace header is an
  amplification vector (any caller makes the service do 10–100× the work) *and* a data-exposure
  vector (any caller asks for a structural trace of their own request). Both are named in the
  refusal message.
- **`max.concurrent=1`** by default.
- The trace document is built and sent on a **min-priority daemon thread**, never on the request
  thread — a trace must not add its own serialisation cost to the latency it is measuring.

---

## 5. `parallelStream` — the named trap, and exactly how far it is handled

`Collection.parallelStream()` runs its pipeline on `ForkJoinPool.commonPool()`. **No `Runnable`
and no `Callable` crosses an instrumented call site** — `java.util.stream` hands the work to the
pool itself — so call-site wrapping has nothing to wrap.

OpenTelemetry solves this by instrumenting `ForkJoinTask` inside the JDK. That needs the probe
resolvable from the **bootstrap** loader, and ax-agent has already established the limit there:
a *constant* can be bridged through `java.lang.$Auxin`, a per-invocation *call* cannot, because
every `Object.equals`-based hop allocates an `Object[]`. That is why `tier2NotBridgeable` exists,
and every trace probe is a call.

### What is actually done

The emitter detects a `parallelStream()` / `.parallel()` call in an instrumented method and
brackets that whole method with `commonPoolArm()` / `commonPoolDisarm()`. While the window is
open, a probe running on a **common-pool worker** with no thread-local cursor may join the trace.
The pipeline's lambda bodies are synthetic methods on the application class and are already
instrumented — what they were missing was a context.

**Measured:** the smoke suite's traced request records `score(Fare)` and `lambda$scoreAll$0` with
`via: "commonPool"`, across 9 distinct workers, and with the window off records none.

### The honest cost of that, stated

- **Any other code using the common pool during the window is attributed to this trace.** The
  window is refused outright when more than one trace is in flight (counted as
  `commonPoolWindowRefused`), every frame arriving this way is tagged `via: "commonPool"` so a
  reader can discount it, and `ax.trace.parallelstream.window=false` turns it off. It is a
  narrow, labelled over-capture, not a correctness claim.
- **A custom `ForkJoinPool` is not covered** — only the common pool is checked, because only the
  common pool is the one `parallelStream` uses.

### What is NOT handled, plainly

1. **A submission made from library code.** Wrapping happens at the *call site*, in a class
   inside `ax.trace.include.packages`. If the application calls a framework and the framework
   submits to a pool, that call site is not rewritten and the task's frames are lost.
2. **A `ForkJoinTask` subclass submitted directly.** Wrapping by delegation needs an interface;
   `ForkJoinTask` is an abstract class with a `protected exec()`. Handling it needs
   `ForkJoinTask.doExec` instrumented in the JDK, which this module does not do.
3. **Any class whose loader cannot resolve `io.auxin.trace.runtime.TraceRuntime`** — OSGi, JBoss
   Modules, a JPMS custom layer, some fat jars. Such classes are **skipped and counted**
   (`agentNotVisible`), never broken, because there is no bridge for a per-invocation call. This
   is the same posture tier-2 takes. Since the merge it is also the *same question*: one jar means
   `ProbeHolder` and `TraceRuntime` share a loader, so `LoaderVisibility` answers for both and the
   tracer's own copy of that logic was deleted. The consequences still differ — tier-1 reaches an
   invisible loader through `java.lang.$Auxin` and this tier structurally cannot.

---

## 6. Other known limitations

- **Constructors get no throw handler.** A `catch (Throwable)` inside `<init>` must merge against
  a state where `this` may be `uninitializedThis`. Entry, exit, observations and branch arms all
  work there; an exception escaping a constructor leaves the frame open and the next exit repairs
  it. Counted as `throwCaptureUnsupportedInit`.
- **Class file version exactly 50 gets no throw handler.** A StackMapTable is optional at 50 and
  adding one makes HotSpot's type-checker fail over to the inference verifier, per class — the
  same reason ax-agent's tier-1 keeps the frameless shape there. Counted as
  `throwCaptureUnsupportedV50`. **Untested**: no available JDK can emit class file 50 (`-target
  1.6` was removed in JDK 12+), so this path is reasoned, not exercised.
- **A servlet entry that cannot carry a handler is not instrumented as an entry at all**, loudly.
  `TraceGate.end()` is what decrements the global gate; an entry that could leak it would make
  every probe in the JVM pay a thread-local read for ever.
- **Trivial accessors are not traced.** `docs/CONTRACTS.md` §1's C51 rule, re-derived from the
  instruction list. *(Post-merge the manifest is often present — the tracer still re-derives,
  because its scope is independent of `ax.include.packages` and a traced class need not be in the
  manifest at all.)* Measured: `Fare#getStops`,
  `Fare#isRefundable` and `Fare$Carrier#values` alone were **519 of 865 frames** in the first
  document the smoke suite produced. Counted as `trivialAccessor`.
- **Branch selection is a heuristic.** The default `predicates` mode records a conditional whose
  operands came from a call or a field read within the same basic block, plus every null check,
  and skips loop counters, `hasNext()`/`hasMoreElements()` loop headers, and `ARRAYLENGTH` bounds
  checks. It under-records rather than over-records, `ax.trace.branches=all` exists, and
  `why` says which rule admitted each arm. `ax-agent`'s and JaCoCo's own `BALOAD`+`IFNE` coverage
  probe is refused at **every** mode, including `all` — asserted in the both-agents run.
- **Exit-side parameter re-reads assume javac does not reuse a parameter slot** for a local of a
  different type. It does not, and the value is passed as `Object` with every decision made at
  runtime, so the worst case is an uninteresting observation rather than a type error. A
  non-javac frontend with aggressive slot reuse is untested.
- **Dynamic attach is refused**, for the whole agent: probes install at initial class load only,
  so an attached tracer would report an armed gate and capture nothing.
- **Volume is bounded, not solved.** For one request against a five-method service the document
  is **66 KB / 149 emitted frames** from 328 recorded (176 folded, 3 collapsed). Both halves of
  the volume control — pass-through elision and identical-sibling folding — run on the dispatcher
  thread and cost the application nothing, but a genuinely branchy request on a 690-class service
  will still produce a large document. The caps (`max.frames`, `max.depth`,
  `max.obs.per.frame`, `max.arms.per.frame`) bound it and report when they bite; they do not make
  it small.

---

## 7. Verification actually run

| what | result |
|---|---|
| `modules/ax-agent/smoke/run-trace-smoke.sh` on **JDK 11.0.32.1** | **72/72** (53 migrated + 19 new) |
| `modules/ax-agent/smoke/run-trace-smoke.sh` on **JDK 17.0.18** | **72/72** (53 migrated + 19 new) |
| `modules/ax-agent/smoke/run-trace-smoke.sh` on **JDK 21.0.12.1** | **72/72** (53 migrated + 19 new) |
| — the migrated count is **asserted** to still be 53, not claimed | the script fails if it is not |
| — of which, trace-document assertions | 41/41 per run |
| — including **all four tiers in one JVM from one `-javaagent`** (tier-1 + tier-2 + edges + trace, real manifest from ax-static) | no VerifyError, document produced, coverage probe not mistaken for a branch |
| `modules/ax-agent/smoke/run-smoke.sh` on 11 / 17 / 21 | 165×4, 161, 182, 163 + **38/38** javap — unchanged by the merge except one strengthened frame assertion |
| `modules/ax-agent/smoke/run-negative.sh` on 11 / 17 / 21 | **159/159** |
| `isolation/run-isolation.sh --jdk all` | **1146 / 0** |
| `g5/run-g5.sh` | **972 / 0** |
| `bench/run-gates.sh g7` at 1 / 4 / 10 threads, JDK 17 | §3; **0 B/op on every untraced arm** |
| `bench/run-gates.sh g7` at 1 thread, JDK 11 and JDK 21 | §3; **0 B/op on every untraced arm** |

### Three real bugs the suite found, listed because they are the point of running it

1. **`LinkageError: attempted duplicate class definition for TraceIgnoreRules`** — the class
   holding the hard-veto list was first reached from *inside* `transform()`, which is a
   re-entrant load on the same thread. It presented as "premain failed, the tracer is disabled"
   *after* the transformer was already registered — a half-armed agent. Fixed by resolving the
   whole transform path (including a full ASM round-trip) before `addTransformer`.
2. **`VerifyError: Type top (current frame, locals[0]) is not assignable to
   'traceapp/SearchFilter'`, with both agents installed.** The throw handler's frame declared
   **zero locals**, copying `ProbeEmitter.emitEdgeRoot`'s argument. That is sound in isolation —
   and wrong the moment another agent's handler range covers your handler block, because `top` is
   assignable-*to*, not assignable-*from*. `emitEdgeRoot` gets away with it only because it is
   emitted last within one emitter; a separate agent cannot assume it is last. Fixed by declaring
   the receiver and the descriptor's arguments, which is what composes.

   **The merge made this ProbeEmitter's problem too, and it is now fixed there as well.** The
   trace tier is emitted LAST, so the edge root's handler block sits *inside* the trace tier's
   protected range — the identical configuration, with the roles reversed. `emitEdgeRoot` no
   longer declares zero locals; it declares the receiver and the descriptor's arguments, and
   nothing after them (tier-2's timing local is not yet assigned at the top of the edge range, so
   declaring `LONG` there would be a lie the verifier catches). `run-smoke.sh` asserts zero
   `locals = []` frames and six `locals = [ class smoke/EdgeTarget, int ]` frames; the trace suite
   asserts the same shape on a method carrying all four tiers, on all three JDKs.
3. **The tracer observed itself.** `Fare` is in the traced scope, so `Fare.getCarrier()` carries
   trace probes — and the projection *calls* `getCarrier()` reflectively, pushing a frame whose
   parameters are observed, which may project again. 1,328 of the first run's common-pool frames
   were the tracer reflecting on its own reflection. Fixed with a per-cursor re-entrancy depth
   that costs nothing on any path not already inside a trace.

A fourth, smaller one: the branch heuristic recorded `for (int i = 0, n = xs.size(); i < n; i++)`
as a predicate, because a fixed-length lookback walked out of the loop condition and into the
loop *body* and found its `INVOKEVIRTUAL`. Fixed by stopping the walk at the basic-block boundary.

### Not verified

- **x86_64 Linux.** Every number in §3 is aarch64. `bench/run-gates.sh g7` is one command there.
- **Why the contended arm at 10 threads moved.** Named in §3; unresolved, and not attributed to
  the merge on the evidence available.
- **Class file version 50**, and **JDK 8 as a host** — no available JDK produces either.
- **A real servlet container.** The smoke target uses stub `javax.servlet` interfaces, which
  produce byte-for-byte the real descriptors, so the **signature match and the activation
  mechanics** are proven. That a real Tomcat, Jetty or Spring Boot calls those methods where this
  module assumes it does is **not** proven.
- **A real OSGi / JBoss / JPMS container.** `agentNotVisible` is reasoned from ax-agent's
  measured isolation results, not re-measured here.
- **Sustained production traffic**, of any kind.
