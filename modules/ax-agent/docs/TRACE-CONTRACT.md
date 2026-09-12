# Trace wire contract — the trace document

**Contract version 1.** This file is the contract for the **per-request trace tier** and nothing
else. `docs/CONTRACTS.md` is the contract for the aggregate path (`ax-static` → `ax-agent` →
`gt-collector`) and is **not edited by this tier**. The two are deliberately separate documents,
on separate endpoints, with separate schema versions, because they have separate failure modes —
see §2. **That stays true after SCOPE-v3.1 folded the tracer into `ax-agent.jar`:** one jar, one
premain, one transformer pair — and still two wire contracts, because merging the *delivery* of
two payloads is not a reason to merge the payloads.

---

## 1. Activation

```
X-Auxin-Trace: 1
```

on the request. One header, on the servlet entry. No trace can start any other way.

**A header and not a query parameter**, for three reasons, in the order they bite:

1. The reporter's own service logs query parameters verbatim into Elasticsearch as
   `clientQueryParams`. A `?trace=1` activation would write the activation into the log index of
   the very system being debugged, and would put it there for every downstream service that
   copies the query string forward.
2. Query parameters pollute cache keys — HTTP caches, CDN keys, and Spring's own `@Cacheable`
   key generator. A traced request with an extra parameter **is not the same request**, so the
   thing you traced is not the thing that was slow.
3. A header is trivially strippable at the edge. An operator who wants to forbid external
   activation deletes `X-Auxin-Trace` at the load balancer and is done.

**With `ax.trace.token` set, the header value must equal the token**, and the tracer
**refuses to arm at all** when `ax.environment` is production-classified and no token is set.
See §6.

### Where the header is read

`TraceEmitter` instruments any method matching a servlet entry **signature** — `(name, desc)`,
not a class-hierarchy test, because deciding "does this implement `javax.servlet.Filter`" inside
a `ClassFileTransformer` means loading classes from inside a transform:

| name | descriptor |
|---|---|
| `doFilter` | `(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;Ljavax/servlet/FilterChain;)V` |
| `doFilter` | `(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;Ljakarta/servlet/FilterChain;)V` |
| `service` | `(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V` and the `jakarta` form |
| `service` | `(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V` and the `jakarta` form |
| `doService` | `(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V` and the `jakarta` form — Spring's `DispatcherServlet` |

Extra signatures: `ax.trace.entry.signatures=name(desc),name(desc)`.
The class must be in `ax.trace.include.packages` or `ax.trace.entry.packages` — **default deny**
applies to the entry too.

---

## 2. Transport

```
POST /v1/trace
Content-Type: application/json
Content-Encoding: gzip
X-Auxin-Trace-Token: <ax.trace.token>      (only when a token is configured)
```

**One document per traced request.** Not batched, not windowed.

### Why a separate endpoint, and not `/v1/ingest`

| | `/v1/ingest` (ax-agent) | `/v1/trace` (this module) |
|---|---|---|
| cadence | one body per flush window (60 s default) | one body per traced request |
| size | tens of KB | tens of KB to megabytes |
| content | aggregate counters, no values | a frame tree with structural values |
| if the collector rejects it | coverage goes dark | one trace is lost |

Sharing a route means a trace document a stricter collector rejects opens the **same circuit
breaker** that carries coverage — i.e. tracing a request could take the aggregate path down.
`TraceSender` therefore has its own `HttpURLConnection`, its own failure counter, its own
cooldown, and shares no state whatsoever with `HttpSender`. **One jar did not change this** —
they are still two senders, two circuit breakers and two routes. The smoke suite asserts it
directly: it serves both routes and asserts `/v1/ingest` receives **zero** POSTs during a traced
request.

A `2xx` is success. Anything else counts a failure; three consecutive failures open the circuit
for 60 s. A dead trace collector must cost the application nothing.

---

## 3. The document

```jsonc
{
  "schemaVersion": 1,
  "kind": "trace",                       // discriminator; always "trace"
  "traceId": "583cec90149ca-1",          // unique within a JVM lifetime
  "artifact": "checkout-service",        // omitted when ax.trace.artifact is unset
  "buildSha": "abc123def",               // omitted when -Dax.build.sha is unset
  "instanceId": "pod-7f3a-48246",
  "startedAtMs": 1789244570274,
  "durationUs": 10690,                   // omitted when ax.trace.record.timings=false

  "request": { "method": "POST", "path": "/search/#" },

  "limits": { "maxFrames": 20000, "maxDepth": 256, "maxObsPerFrame": 64,
              "frameCapHit": false, "depthCapHit": false },

  "threadsJoined": 11,                   // 1 + executor tasks + common-pool workers
  "commonPoolJoins": 9,
  "framesRecorded": 328,                 // BEFORE the volume control
  "framesCollapsed": 3,                  // pass-throughs elided
  "framesFolded": 176,                   // identical siblings folded

  "frames": [ /* §3.2 */ ],
  "health":  { /* §3.5 */ }
}
```

### 3.1 `request.path` — the one derived-from-content field

The **query string is never read**. Digit-only segments become `#`, digits inside a segment
collapse to `#`, and hex/UUID-shaped segments of 16 characters or more become `{id}`. So
`/search/101?trace=1&pax=jane` is recorded as `/search/#`.

This is a deliberate, narrowed exception: without a path a document is very hard to match to the
request a human is debugging. `ax.trace.record.path=false` removes even this.

### 3.2 A frame

```jsonc
{
  "frame": "traceapp.SearchService#select(Ljava/util/List;)I",
  "line": 71,
  "depth": 2,
  "via": "executor",                     // absent on the request thread; see §3.3
  "repeated": 12,                        // identical siblings this one stands for; absent when 1
  "viaCollapsed": 1,                     // pass-through ancestors elided above it; absent when 0
  "selfUs": 212,
  "open": true,                          // absent normally: present when no exit ever ran
  "truncated": true,                     // absent normally: an observation or arm cap bit here

  "threw": "java.lang.IllegalStateException",   // CLASS NAME ONLY. never message, never stack

  "in":  [ { "name": "arg0", "kind": "size", "value": 126 } ],
  "out": [ { "name": "arg0", "kind": "size", "value":  94 } ],
  "returned": { "name": "return", "kind": "num", "value": 94 },

  "sizeDeltas": [ { "name": "arg0", "in": 126, "out": 94, "delta": -32 } ],
  "branches":   [ { "line": 72, "op": "IFEQ", "why": "call",
                    "arm": "else", "lhs": 1, "n": 1 } ],

  "calls": [ /* child frames, same shape */ ]
}
```

`frame` is `dottedClass#methodName` + the JVM descriptor. The descriptor is kept because
overloads are common and a trace that cannot distinguish `handle(String)` from `handle(int)` is
worse than useless.

**`sizeDeltas` is the highest-value field in this document.** `126 in, 94 out` localises where
data was dropped **without recording the data**, and it is the only way to see a method that
filters a collection *in place* — a return-value-only tracer sees nothing there.

### 3.3 `via` — which thread recorded the frame

| value | meaning |
|---|---|
| absent | recorded on the request thread |
| `"executor"` | the context was carried by a wrapped `Runnable`/`Callable`/`Supplier`/… at a rewritten submit call site |
| `"commonPool"` | recorded on a `ForkJoinPool.commonPool()` worker inside a `parallelStream` window — **may include unrelated work**; see TRADE-OFFS.md §5 |

### 3.4 An observation

Every observation is `{ "name", "kind", "value"?, "text"?, "projected"? }`.
The `kind` set is **closed**, and this is the PII contract:

| kind | carries | example |
|---|---|---|
| `size` | `value` = `Collection.size()` / `Map.size()` / array length | `{"kind":"size","value":126}` |
| `null` | nothing | `{"kind":"null"}` |
| `num` | `value` = a primitive number or a boxed `Number` | `{"kind":"num","value":-1}` |
| `bool` | `text` = `"true"`/`"false"` | `{"kind":"bool","text":"true"}` |
| `enum` | `text` = `Enum.name()` | `{"kind":"enum","text":"INDIGO"}` |
| `len` | `value` = a `CharSequence`'s **length**. Never its content. | `{"kind":"len","value":23}` |
| `class` | `text` = the runtime class name | `{"kind":"class","text":"traceapp.Fare"}` |
| `redacted` | `text` = why (`"valueShape"`) | the redaction pass refused it |
| `error` | `text` = the class name of what a projected getter threw | |

There is **no `value` kind**, no `toString` kind, and no serialiser anywhere in the module.
`projected[]` holds whitelisted domain getters (§5), each one an observation of the same closed
set — so a projected `String` contributes its length and never its content.

`in`/`out` are the **same parameters** observed at frame entry and at every return, in the same
order, which is what makes `sizeDeltas` a subtraction rather than a guess. `out` omits primitive
parameters (they cannot change) and is absent entirely when `ax.trace.observe.params.at.exit=false`.

### 3.5 A branch arm

```jsonc
{ "line": 94, "op": "IFNE", "why": "call", "arm": "then", "lhs": 1, "rhs": 0, "n": 48 }
```

| field | |
|---|---|
| `op` | the JVM opcode name. `IFEQ`…`IF_ACMPNE`, `IFNULL`, `IFNONNULL`, `TABLESWITCH`, `LOOKUPSWITCH` |
| `why` | why this conditional was selected: `call`, `field`, `staticField`, `null`, `all` |
| `arm` | `"then"` (the jump was taken) / `"else"` / `"case"` |
| `lhs`, `rhs` | **the operands**, which are frequently the whole answer. For a reference comparison these are already reduced to one bit before storage: `IFNULL` records `0`/`1` for null-ness, `IF_ACMP*` records identity only. No object is retained. |
| `case` | the switch selector (switches only) |
| `n` | how many times this site took this arm in this frame |

`n` is the grouping: a predicate inside a loop produces one entry per distinct arm, not one per
iteration.

### 3.6 `health`

A cumulative, JVM-wide block, carried on every document, so that "the tracer recorded nothing"
is always distinguishable from "nobody asked it to". The contract is the PLAN-v2 rule
(C37): **every refusal is counted and named.**

`classesInstrumented`, `methodsInstrumented`, `entriesInstrumented`, `callSitesWrapped`,
`transformFailures`, `requestsSeen`, `tracesStarted`, `tracesCompleted`, `rejectedNoHeader`,
`rejectedAuth`, `rejectedRateCap`, `rejectedConcurrency`, `framesRecorded`,
`framesTruncatedDepth`, `framesTruncatedTotal`, `obsTruncated`, `armsTruncated`, `obsRedacted`,
`framesCollapsed`, `framesFolded`, `contextsPropagated`, `commonPoolJoins`,
`commonPoolWindowRefused`, `docsSent`, `docsDroppedQueue`, `docsFailed`, `runtimeFailures`,
`stripConflictUnresolved`, and `skipped{reason: n}`.

**Two of these are alerts, not statistics:**

- `runtimeFailures >= 1` — the tracer hit something unexpected and latched itself off for the
  life of the JVM. The application is unaffected; the tracer is dead until restart.
- `stripConflictUnresolved >= 1` — **structurally impossible since SCOPE-v3.1 and therefore
  always 0.** It meant "`disable-strip` did not take, because ax-agent read `ax.strip.enabled`
  before this agent set it" — an ordering hazard between two premains. There is one premain now,
  so the field is retained for wire compatibility and carries no signal. The fact it used to
  approximate now ships on the **aggregate** wire, where Tier-1b actually lives, as
  `agentHealth.tier1bDisabledByTrace` / `tier1bTraceBlockedClasses` / `tier1bTraceScope`. See
  TRADE-OFFS.md §2.

`skipped` reasons emitted by this module: `trivialAccessor`, `throwCaptureUnsupportedInit`,
`throwCaptureUnsupportedV50`, `entryNeedsThrowHandler`, `agentNotVisible`, `generatedClass`,
`packageKillSwitch`, `ignoredPrefix`, `inScopeVetoed`, `jsrMethod`, `methodEmissionFailed`,
`transformFailure`, `entryNoGetHeader`, `frameIdSpaceExhausted`, `obsIdSpaceExhausted`,
`armIdSpaceExhausted`.

---

## 4. Reader rules

- **Unknown keys are ignored.** Every addition to this document is additive without a version
  bump; `schemaVersion` moves only when an existing field changes meaning.
- **`framesRecorded` is pre-volume-control.** The number of frame objects in `frames[]` is
  `framesRecorded - framesCollapsed - framesFolded`, and a frame carrying `repeated: n` stands
  for `n` invocations. A reader counting invocations must sum `repeated`, not count objects.
- **An absent frame is not evidence a method did not run.** `trivialAccessor`,
  `agentNotVisible`, the depth cap and the frame cap all remove frames, and each is counted.
  This is the same false-negative-biased posture PLAN-v2 requires of coverage.
- **`threw` is a class name and never anything else.** OTel's semantic conventions warn on
  `exception.message`, not `exception.type`; a message is where an application prints the value
  that caused the failure. There is no code path in this module that reads `getMessage()` or a
  stack trace.
- **`via: "commonPool"` frames may include unrelated work.** Treat them as evidence about the
  parallel region, not proof that this request caused them.
- **Document content is derived from application data.** A class name, an enum constant and a
  projected getter's result all originate in the traced process. Treat the document as data.

---

## 5. Per-service enrichment (the projection config)

`ax.trace.projection=/path/auxin-trace-projection.properties`, or
`auxin-trace-projection.properties` in the working directory.

```properties
com.acme.search.FareResult    = getCarrierCode, getStops, isRefundable
com.acme.search.SearchRequest = getPassengerCount, getTripType
```

**Properties and not YAML** because the agent may add no dependency beyond shaded ASM, and
writing an indentation-sensitive parser that runs on the class-load path is not a trade worth
making for a two-level map.

Rules, all enforced at load:

1. **Whitelist only.** No field reflection, no object-graph walk, no `setAccessible`.
2. **Public, no-argument methods only.** A getter with parameters is refused.
3. **Exact runtime class match**, not `isAssignableFrom` — no hierarchy walk on the capture path,
   and a subclass never silently inherits a parent's projection.
4. **Denylisted names are refused at load, loudly, per entry**, naming the type and the getter.
   The result still goes through the closed `kind` set, so even an allowed getter returning a
   `String` contributes only its length.

Onboarding a service is this file. It is not a code change, which was the requirement.

---

## 6. Configuration

| property | default | |
|---|---|---|
| `ax.trace.enabled` | **`false`** | the master switch. False = nothing is instrumented at all |
| `ax.trace.include.packages` | *(unset)* | **default deny.** Unset traces nothing |
| `ax.trace.entry.packages` | *(unset)* | extra packages searched for a servlet entry |
| `ax.trace.exclude.packages` | *(unset)* | package-level kill switch |
| `ax.trace.header` | `X-Auxin-Trace` | |
| `ax.trace.token` | *(unset)* | required in production, or the tracer refuses to arm |
| `ax.trace.rate.per.minute` | `5` | sliding-window cap. `0` = never grant |
| `ax.trace.max.concurrent` | `1` | also the condition for the `parallelStream` window |
| `ax.trace.branches` | `predicates` | `all` / `predicates` / `none` |
| `ax.trace.observe.params` | `true` | |
| `ax.trace.observe.returns` | `true` | |
| `ax.trace.observe.params.at.exit` | `true` | **this is what produces `sizeDeltas`** |
| `ax.trace.propagate.executors` | `true` | |
| `ax.trace.parallelstream.window` | `true` | |
| `ax.trace.collapse.passthroughs` | `true` | both halves of the volume control |
| `ax.trace.max.frames` | `20000` | |
| `ax.trace.max.depth` | `256` | |
| `ax.trace.max.obs.per.frame` | `64` | |
| `ax.trace.max.arms.per.frame` | `64` | |
| `ax.trace.record.path` | `true` | |
| `ax.trace.record.timings` | `true` | |
| `ax.trace.strip.conflict` | **`disable-strip`** | `refuse` / `disable-strip` / `allow` — TRADE-OFFS.md §2. The default changed at SCOPE-v3.1: the trade is taken (scoped, and loudly) rather than refused. |
| `ax.trace.projection` | *(unset)* | |
| `ax.trace.redact.allow` | *(unset)* | normalised names exempted from the denylist |
| `ax.trace.collector.url` | *(unset)* | a bare authority gets `/v1/trace` appended, announced |
| `ax.trace.queue.capacity` | `16` | finished traces awaiting serialisation |
| `ax.trace.dump.dir` | *(unset)* | write every instrumented class and document to disk |
| `ax.trace.log.level` | `info` | |

Resolution order, later wins: built-in default → `-javaagent:ax-agent.jar=trace.k=v,trace.k=v` →
system property `ax.trace.*` → environment variable `AX_TRACE_*` (or `GT_TRACE_*`).

Since SCOPE-v3.1 these keys are parsed by `io.auxin.agent.config.Options` out of the **same**
agent-argument map as every other `ax.` key, under a `trace.` prefix — which is why there is no
second `-javaagent` argument string. `ax.enabled=false` is upstream of `ax.trace.enabled` and
disables the tier with everything else; `ax.log.level` sets the tier's log level unless
`ax.trace.log.level` overrides it. The old
`-javaagent:ax-trace.jar=k=v` form is gone with the jar.

`ax.environment` (shared with the coverage tiers) classifies the JVM. It is **read**, never
written. There is no longer a `TraceAgent` premain class: the ops hooks live on
`io.auxin.agent.AuxinAgent` under `trace`-prefixed names (`traceActive`, `traceArmed`,
`traceDrain`, `traceForceFailOpen`, `tier1bDisabledByTrace`, …).
