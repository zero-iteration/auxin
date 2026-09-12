# Field trial — a real 690-class Spring Boot 2.2 production service

First run of auxin by someone other than its author, on a real service. This file records what
was **proven**, what was **refuted**, and the adoption cliffs it exposed. Every number here is
theirs, from a real run — not a local benchmark.

---

## >>> PROMOTED FROM "NEVER ASSUME" TO VERIFIED

`docs/PLAN-v2.md` and the README both listed Spring Boot fat jars as *"must be tested and claimed,
never assumed."* **It is now tested and claimed.**

| | |
|---|---|
| host | Spring Boot **2.2** fat jar, real production service |
| scope | **690 classes / 7,294 methods** |
| bridge | **`selfbsm` over `LaunchedURLClassLoader`** — the F2 self-BSM design working on the real thing |
| armed in | **255 ms** |
| boot impact | **+5-10 s on a 32 s boot** |
| steady-state | **no search-latency impact observed** |

This is the single most valuable line in the trial: the `java.lang.$Auxin` + self-BSM bridge was
built against a `URLClassLoader` with a null parent and an embedded Felix framework. A real Spring
Boot fat jar is the case A10 predicted would be hardest, and it worked **unmodified**.

## >>> DESCRIPTOR-LEVEL TRACKING BEAT A HUMAN WITH grep
Under-sold in the docs, and a genuine differentiator over search-based dead-code hunting. It
distinguished **three overload pairs** the reporter's own grep could not:
- `getConnector(String)` vs `getConnector(Provider)`
- 3-arg vs 4-arg `calculatePromoPriceSlasherDetail`
- 3-arg vs 2-arg `getConvFeeByAirline`

Grep matches a *name*. Auxin keys on `(class, name, descriptor)`, so an overload that is genuinely
dead is distinguishable from its live sibling. **A search-based tool structurally cannot do this.**

## >>> EVIDENCE THAT THE UNKNOWN SET IS NOT A TRAFFIC ARTEFACT
The obvious objection to coverage-based dead-code detection is *"you just didn't send enough
traffic."* The reporter tested it: **107 distinct production request shapes replayed.**

- coverage went **flat after ~45 requests** — the last 47 shapes added only **7 methods**
- the candidate set was **identical before and after**: **18 candidates, 0 revoked, 0 added**

On a 690-class production service. That is real evidence that the unknown set is a property of the
code, not of the replay volume — and it is exactly the window-sensitivity measurement
`VALIDATION.md` noted **nobody has ever published**.

## The `--tier2` budget refusal, quoted approvingly
> *"It rejected 565 > 250 with a per-pattern breakdown and the ~80ns rationale. Keep that pattern."*

Refusing loudly with the arithmetic, rather than silently truncating, is now a house style worth
repeating elsewhere.

---

## Adoption cliffs it exposed (all filed and fixed)

| # | finding | severity |
|---|---|---|
| **#22b** | **The C50 gate reads as total failure on first run.** With `ax.environment` unset the collector 403'd every window; the allowlist was hardcoded with no flag. *"I got 0 windows stored and had to mislabel a laptop as production to see anything. This is the single biggest barrier to anyone trying it."* | **adoption blocker** |
| **#24** | **Exception class names never reached the server.** `ErrorIds` maintained the 1-254 name table and `Tier2Aggregator` kept `errorCounts[]` by id — and `Batch.java` wrote only the scalar `errors`. `grep -rn errorClass modules/ax-server/src/` -> **zero hits.** The README's *"what does it throw"* was never delivered. The data existed in the agent and was discarded at the wire. | **advertised feature absent** |
| **#25** | **The clock check is flaky.** Same machine, two boots: one `disabled` (250ns pair), the next `sampled`. Root cause already known (A4: median-of-pairs measures arm64 timer *granularity*, not cost). Worse, the consequence was invisible: `calls` and `errors` still work when timing is disabled — *"the difference between 'tier-2 is dead on this host' and 'you still get counts'"* — discoverable only by reading the source. | real |
| **#26** | `ax.edges.sample.rate=1024` yields **zero edges** at dev volume while the summary still says `tierArmed: true`. **Reads as broken.** | real |
| **#27** | `requires-python = ">=3.14"` blocks install on essentially every current box. Runs fine on 3.12. | trivial, total blocker |
| **#28** | **28% of candidates were compiler/Lombok output** — 5 of 18: enum `values()`/`valueOf()`, and `equals`/`hashCode`/`toString` on `@Data` classes. A default suppression list is a free precision win. | precision |
| **#29** | **`ax-static` needs JDK 17 to build while the agent is release 8 — undocumented.** The reporter had to install a second JDK to get started. | docs |

### On #29, the toolchain split, now stated
- **`ax-agent` targets Java 8 bytecode** so it can attach to a JDK 8+ host. Verified on a real JDK 8.
- **`ax-static` targets JDK 17** because it uses modern ASM and language features. It is a
  **build-time tool**; the manifest it emits is plain JSON, so **the generator's JDK is entirely
  independent of the JVM under test.** Run `ax-static` with any JDK 17+, instrument a JDK 8 app.
- **`ax-server` is Python** (>=3.12 after #27), stdlib only.

You need JDK 17 to *build*. You do not need it to *run against*.
