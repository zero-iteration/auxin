# auxin-demo — the integration target

A small, plain-Java application whose **dead code is known exactly**. It is the target for
gate **G5** (the three-agent harness: ax-agent + JaCoCo + the OTel javaagent, attached
simultaneously, under traffic) and for any test that needs a program where "this method
never ran" is a fact rather than an opinion.

No Spring. No dependencies at all — the HTTP server is `com.sun.net.httpserver.HttpServer`
from the JDK, so `mvn -o package` builds it offline with nothing but the plugins already
in `~/.m2`.

```sh
mvn -o package                       # or: ./run.sh (builds if needed)
./run.sh --seconds=30 --threads=4
./verify-auxin.sh              # proves this README is still true
```

Everything in the tables below is **measured** by `verify-auxin.sh`, not asserted:
class loading comes from `-Xlog:class+load=info`, execution from the app's own counters,
bridge methods from `javap`. The machine-readable copy is `ground-truth.json` — assert
against that file, not against this prose.

---

## How it runs

`App.main` starts the server on an ephemeral loopback port, then `TrafficDriver` hits it
from N threads until **both** the duration has elapsed **and** `--min-requests` have been
served. Every branch keys off a global request index, so the fixture is deterministic
under concurrency, and the minimum request count is what guarantees the rare methods fire
at least once. (A duration-only driver would make "the rare method did not run"
indistinguishable from "the agent missed it" — the one confusion this fixture exists to
prevent.)

| flag | default | meaning |
|---|---|---|
| `--seconds=N` | 10 | load duration |
| `--min-requests=N` | 600 | floor; guarantees every rare branch fires |
| `--max-requests=N` | ∞ | cap, for an exactly-reproducible run |
| `--threads=N` | 4 | concurrent drivers |
| `--port=N` | 0 | 0 = ephemeral |

Endpoints: `POST /orders?index=N` (hot), `GET /healthz` (every 10th request),
`GET /admin/status` (every 200th), `GET /admin/rebuild` (exactly once, at the end),
`GET /admin/threads` (**wired up and never requested**).

At exit the app prints one line:

```
[demo] summary {"placeOrder":104708,"rare.deepScan":209,"rare.refund":764, ... }
```

Absence of a `dead.*` key is the assertion that a dead method stayed dead.

---

## Ground truth

### 1. Never loaded — the class never enters the JVM

| class | statically reachable? | why |
|---|---|---|
| `repo.AuditRepository` | no | referenced by nothing, anywhere |
| `util.DeadUtils` | no | referenced by nothing |
| `service.LegacyReportService` | **yes** | referenced only from `DeadUtils`, which is itself never loaded |

These contribute **no probe array at all** — they are absent from the data, not present
and zero. The honest runtime verdict is `UNKNOWN`; only static corroboration can promote
them. `LegacyReportService` is there specifically because static reachability and runtime
loading are independent signals, and a design that conflates them gets it wrong.

`AuditRepository` also has a `<clinit>` that never runs.

### 2. Loaded but never invoked

| class | loaded by | what runs |
|---|---|---|
| `service.MaintenanceWindow` | `Class.forName` in `App.main` | `<clinit>` only |

No constructor, no method. This is the C10 distinction: the class appears in
`classesLoaded`, every method probe is unset, and these methods *are* legitimate
`DEAD_CANDIDATE`s — unlike §1, where the answer must be `UNKNOWN`. A pipeline that reports
the two cases identically is wrong.

(`MaintenanceWindow#*` is nevertheless listed in `.auxin/suppress.txt`, because
maintenance-window code is exactly the kind that looks dead and is not.)

### 3. Dead methods on live classes — the case that matters

| method | note |
|---|---|
| `model.Order#toLegacyCsv()` | one call site, inside the never-loaded `LegacyReportService` |
| `model.Order#toString()` | an override nobody calls |
| `model.Customer#name()` | trivial getter, no caller |
| `model.Customer#anonymize()` | compliance-shaped, never wired up |
| `repo.OrderRepository#deleteById(String)` | — |
| `repo.OrderRepository#findByCustomer(String)` | plausible query method, no caller |
| `repo.CustomerRepository#purgeInactive(long)` | shaped like a scheduled job |
| `service.OrderService#cancelAll()` | **break-glass** — suppressed, so the correct verdict is `UNKNOWN` |
| `service.PricingService#applyLegacyDiscount(int)` | used to run, then stopped |
| `service.ShippingService#internationalRate(Order,String)` | — |
| `service.FraudService#flagSuspicious(Order)` | **called from a method that DOES run**, behind a branch that is never taken |
| `util.Ids#shortId()` | — |
| `http.DemoServer#describe()` | — |
| `http.handler.AdminHandler#dumpThreads()` | **a wired-up HTTP route that is never requested** |

Two of these are the interesting ones:

* **`flagSuspicious`** is called from `deepScan` under `if (score > 90)`. Order amounts are
  1000..50999 cents, so `score()` is capped at 50 and the branch never executes. A static
  call graph sees the edge; a cascade that reasons "deepScan runs, therefore its callees
  run" deletes live-looking code or keeps dead code, depending which way it leans.
* **`dumpThreads`** is reachable from `AdminHandler.handle` through `/admin/threads`, a
  route that exists and is never requested. Entry-point enumeration alone marks it live.

### 4. Rare — executed, but only on an infrequent branch

| method | trigger | counter | min in a default run |
|---|---|---|---|
| `ShippingService#expressRate` | `index % 250 == 0` | `rare.expressRate` | 2 |
| `FraudService#deepScan` | `index % 500 == 0` | `rare.deepScan` | 1 |
| `FraudService#score` (private) | only via `deepScan` | `rare.score` | 1 |
| `OrderService#refund` | `index % 137 == 0` (error path) | `rare.refund` | 4 |
| `AdminHandler#status` | every 200th request | `rare.adminStatus` | 2 |
| `AdminHandler#rebuildIndex` | **exactly once**, after the load loop | `rare.adminRebuild` | 1 |

`rebuildIndex` is the guaranteed-once case: missing it is a bug, not a sampling artefact.

There is also one rare **branch** (not a method): the `customer == null` warm-up path in
`placeOrder`, taken exactly 25 times, at the very start. A window that opens after warm-up
never sees it. Branch granularity is out of scope for tier 1 by design.

### 5. Must NOT be probed, and must never appear as dead code

| method | kind |
|---|---|
| `OrderRepository#save(Object)` | bridge + synthetic |
| `OrderRepository#findById(String)Object` | bridge + synthetic (covariant return) |
| `CustomerRepository#save(Object)` | bridge + synthetic |
| `CustomerRepository#findById(String)Object` | bridge + synthetic |
| `PricingService#lambda$quote$0(int)` | synthetic (lambda body) |
| every `<clinit>` | never probed, never nominated |

CONTRACTS.md §1 skips synthetic/bridge/`<clinit>`/abstract/native. A probe on a bridge is a
bug; so is reporting one as dead code. `verify-auxin.sh` reads these straight out of
the class files, so the fixture breaks loudly if a javac upgrade changes what is emitted.

### 6. Runtime-generated classes

The run produces `$$Lambda` classes for `Counters`, `TrafficDriver`, `DemoServer` and
`PricingService`. They exist so the C9 generated-name exclusion has something real to
exclude — the failure mode being an app that generates classes indefinitely and turns a
188MB exec file into >2GB of heap.

---

## What this fixture deliberately does not have

* **No test suite.** Coverage of this app must come from traffic, not from tests. Coverage
  produced by a test suite is precisely the signal this product argues is not evidence of
  production liveness.
* **No framework.** Spring would add CGLIB proxies, annotation scanning and thousands of
  classes — every one of them a confounder when a probe does not fire. The G5 harness needs
  a target where an unexplained gap is a bug in the agent, not in the framework.
* **No randomness.** Amounts and branches are pure functions of the request index.

---

## Status

Built and run on JDK 17 (Temurin 17.0.18, aarch64). `verify-auxin.sh` passes.

**Not done here:** running it with any agent attached. ax-agent does not exist yet, and
there is no Docker on this machine, so G5 itself — our probe AND the JaCoCo line AND the
OTel span, simultaneously, under traffic — has not been executed. The fixture is ready for
it; the harness is not written.
