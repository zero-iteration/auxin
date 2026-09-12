# Classloader isolation — does `java.lang.$Auxin` actually work?

`./run-isolation.sh [--jdk 11|17|21|all] [--only <substring>]`

VALIDATION **A10** deferred this as "needs a real container". It did not. JPMS ships inside the
JDK and Apache Felix is one jar from Maven Central, so all three containers A10 names —
JPMS named modules, OSGi, and child-first fat-jar loaders — are reachable with no Docker and no
network at run time.

**Result: 750 assertions, 250 per JDK, identical on 11 / 17 / 21, all passing.**
Passing does not mean "no findings". It means every assertion below — including the seven that
assert a *defect* — reproduced exactly as written. The defects are in
[section 2](#2-findings-ranked), and **[F1](#f1--bug-confirmed) is an availability bug that fires
in Apache Felix's out-of-the-box configuration**, not a coverage gap.

---

## 1. What was run

| | |
|---|---|
| Agent under test | `modules/ax-agent/target/ax-agent.jar`, sha256 `38d41b19da3c26a6aae2…` |
| Implementation | `io.auxin.agent.runtime.BootstrapBridge`, `instrument/LoaderVisibility`, `instrument/ProbeEmitter` |
| JDK 11 | `openjdk 11.0.32.1 2026-08-18` (Homebrew) |
| JDK 17 | `openjdk 17.0.18 2026-01-20` (Temurin 17.0.18+8) |
| JDK 21 | `openjdk 21.0.12.1 2026-08-18` (Homebrew) |
| OSGi | Apache Felix Framework **7.0.5** (OSGi R8), sha256 `aba72932c5ffe52d1ae9…`, embedded via `FrameworkFactory` |
| Application class files | compiled `--release 11` → class file major **55**, so condy is available everywhere and the JDK is the only variable |
| Other dependencies | none. No Docker, no Spring Boot, no Tomcat, no network at run time |

Fifteen scenarios per JDK:

| # | scenario | container | expected probe delivery |
|---|---|---|---|
| 1a | `jpms-launch-app` | named module, `exports`, launched `-m` | condy |
| 1a | `jpms-launch-open` | named module, `exports`+`opens`, `-m` | condy |
| 1a | `jpms-launch-sealed` | named module, neither, `-m` | condy |
| 1b | `jpms-classpath-driver` | named module + class-path driver | condy |
| 1d | `jpms-custom-layer` | named module in a custom `ModuleLayer`, **bootstrap-parent loader** | **bridge** |
| 2 | `osgi-felix-implicit` | Felix, stock defaults | condy (**wrongly** — see F1) |
| 2 | `osgi-felix-strict` | Felix, `felix.bootdelegation.implicit=false`, no boot delegation | **bridge** |
| 2 | `osgi-felix-bootdelegation` | + `org.osgi.framework.bootdelegation=io.auxin.*` | **bridge** |
| 2 | `osgi-felix-bootdelegation-app` | + `org.osgi.framework.bundle.parent=app` | condy |
| 3 | `child-first-condy` | parent-last loader, agent reachable via parent | condy |
| 3 | `child-first-bridge` | parent-last loader **shadowing the agent jar** | **bridge** |
| 3c | `child-first-skip-bridge-disabled` | same, `ax.bridge.enabled=false` | skip |
| 3d | `rival-wins` | a second agent defines `java.lang.$Auxin` first | skip |
| 3e | `rival-loses` | ax-agent defines it first, the rival loses | **bridge** |
| 3f | `bridge-data-tamper` | a third party overwrites `java.lang.$Auxin.data` | **app-visible failure** |

---

## 2. Findings, ranked

### F1 — BUG CONFIRMED
**In Apache Felix's default configuration the agent emits a direct `io.auxin` call into a
bundle class and it fails to resolve inside application code.**

```
ClassNotFoundException: io.auxin.agent.runtime.Tier2Runtime
                        not found by iso.osgi.bundle [1]
    ... thrown from iso.osgi.Service.alpha(int), an application method
```
Reproduced identically on JDK 11, 17 and 21. Scenario `osgi-felix-implicit`, which uses **stock
Felix settings** — nothing is configured to make it fail.

**Mechanism.** Felix ships `felix.bootdelegation.implicit=true`. On a failed bundle class load
Felix walks the call stack, and if the first non-framework class on it was not loaded by a bundle,
it delegates to that class's loader. So the answer depends on *who is asking*:

```
info  bundle loader can resolve the agent's ProbeHolder?                      true
info  alpha() from an application thread threw: ClassNotFoundException: …Tier2Runtime…
info  the SAME bundle loader asked for Tier2Runtime by APPLICATION code:      FOUND (AppClassLoader@…)
```

`LoaderVisibility.agentVisibleFrom()` asks with `Class.forName(HOLDER, false, loader)`, executed
on the agent's own stack inside `transform()`. Felix sees a non-bundle caller, delegates, and
returns the agent's real `ProbeHolder` — so the check answers **visible**, `ProbeEmitter` takes
`ACCESS_CONDY`, and the bridge is never used. When the *instrumented class* later links the same
package, the first class on the stack is bundle code, no delegation happens, and the reference
fails.

Note the asymmetry that hides the bug: the `Class.forName` probe does not only mispredict, it
**pre-seeds the system dictionary** for the one name it asked about. `ProbeHolder` therefore
resolves later even though it should not have; `Tier2Runtime`, which nothing pre-seeds, does not.
So tier 1 accidentally survives and **tier 2 turns into an outage on the first call to every
boundary method in every bundle.**

This is precisely the failure class C33/C34 exist to prevent, reached through the front door: the
visibility check, not the bridge.

**Suggested fix (in ax-agent, not applied here).** Make visibility require *both* conditions:
1. `Class.forName(HOLDER, false, loader) == ProbeHolder.class` — the current identity test, which
   correctly catches a child-first loader shadowing the agent (proved by `child-first-bridge`); and
2. the loader's **parent chain structurally reaches `ProbeHolder.class.getClassLoader()`** —
   delegation that is a property of the loader graph, not of the call stack.

The suite measures that this rule is correct in all four Felix configurations:

```
implicit           chain: iso.osgi.bundle [1] -> bootstrap                      reaches agent loader = false
strict             chain: iso.osgi.bundle [1] -> bootstrap                      reaches agent loader = false
bootdelegation     chain: iso.osgi.bundle [1] -> bootstrap                      reaches agent loader = false
bootdelegation-app chain: iso.osgi.bundle [1] -> AppClassLoader -> … -> bootstrap reaches agent loader = TRUE
```

The `LoaderVisibility` javadoc rejects a parent-chain walk because "a parent-chain walk cannot see
a child-first loader's shadowing rules". True — but the walk is wanted as an **additional
necessary condition**, not a replacement. Child-first shadowing is caught by the identity test;
stack-dependent delegation is caught by the walk. A loader that delegates to the agent without
having it in its parent chain would then be demoted to the bridge, which costs nothing but works.

### F2 — the bridge path silently gives up condy, and with it interfaces and de-instrumentation
Confirmed in `osgi-felix-strict`, differentially against `osgi-felix-bootdelegation-app` (same
class, same container, only the delivery path differs):

| | bridge path | condy path |
|---|---|---|
| strip handle `ProbeHolder.loadedClass("iso.osgi.Service")` | `null` | `class iso.osgi.Service` |
| `probes("iso.osgi.Helper")` (interface, default + static methods) | `null`, skipped `interfaceNeedsField` | `11` — probed |
| tier-2 method | skipped `tier2NotBridgeable` | installed |

Consequences, each verified:
- **Tier-1b can never run** on a bridge-instrumented class. `DrainThread.stripCoveredClasses()`
  needs a `Class` handle, and that handle is captured only by the condy bootstrap method
  (`ProbeHolder.bootstrap` via `lookup.lookupClass()`). `ProbeInstaller` never registers one for
  the field path. The comment at `DrainThread:200` calls this "pre-55 field fallback: no strip
  handle, never stripped" — but in OSGi, JBoss Modules or a custom `ModuleLayer` this is not a
  rare legacy class, it is **the entire application**. Probes stay on the hot path for the life of
  the JVM, and C4's "steady-state cost → zero" does not hold in the containers the bridge exists for.
- **Interfaces get no coverage at all** in those containers (`ProbeEmitter` skips
  `ACCESS_FIELD_BRIDGE && isInterface` because an interface cannot hold a mutable static field).
  Every `default` and `static` interface method is invisible, and the collector cannot tell that
  apart from dead code without reading `ax_classes_skipped_total{reason=interfaceNeedsField}`.

This is a real divergence from the JaCoCo recipe A10 cites. JaCoCo's condy bootstrap method is
`$jacocoInit` **on the instrumented class itself**, which then does the `java.lang` dance — so
JaCoCo keeps condy *and* the bridge simultaneously. ax-agent's condy BSM points straight at
`ProbeHolder`, so choosing the bridge means abandoning condy. Making the BSM a synthetic
self-method that reads `java.lang.$Auxin` would recover interfaces and de-instrumentation
in exactly these containers. (Not attempted here — it is a ax-agent change.)

### F3 — HAZARD: `java.lang.$Auxin.data` is a JVM-global mutable hook
Scenario `bridge-data-tamper`. A second agent that loses the race for the *name* can still take
the *contents*: the field is `public static`, so anything in the JVM can overwrite it.

```
[rival] TAMPERED java.lang.$Auxin.data: BootstrapBridge$Data -> RivalAgent$RivalData
first use of the bridge-instrumented class threw:
    java.lang.ClassCastException: class java.lang.String cannot be cast to class [Z
```
The bridge prologue ends in `AALOAD; CHECKCAST [Z`, so a handler that does not replace `args[0]`
turns the first use of every bridge-instrumented class into `ExceptionInInitializerError` inside
application code. ax-agent can neither detect nor defend this today. JaCoCo's `java.lang.$JaCoCo`
has the identical exposure, so this is not a regression against the state of the art — but it is
the one way a third party can convert the bridge into an application-visible failure, and it
belongs in the risk register rather than being discovered in production.

### F4 — FORWARD-LOOKING: what makes JPMS safe today is exactly what C34 wants to remove
The reason a named module can link to `io.auxin.agent.runtime.ProbeHolder` at all is not
luck — it is documented JDK behaviour. When a JVMTI agent transforms a class in module `m`, the VM
calls `jdk.internal.module.Modules.transformedByAgent(m)`:

```java
/**
 * Called by the VM when code in the given Module has been transformed by
 * an agent and so may have been instrumented to call into supporting
 * classes on the boot class path or application class path.
 */
public static void transformedByAgent(Module m) {
    addReads(m, BootLoader.getUnnamedModule());
    addReads(m, ClassLoaders.appClassLoader().getUnnamedModule());
}
```
(`java.base/jdk/internal/module/Modules.java`, JDK 17 `src.zip`; identical on 11 and 21.)

Measured, in one JVM, with a never-transformed control module:

```
PASS  control module ax.iso.sealed (never transformed) does NOT read the unnamed module
PASS  the TRANSFORMED module reads the unnamed module (Modules.transformedByAgent, added by the VM)
```

The read edge is granted to the **boot** and **application** class loaders' unnamed modules — and
the agent runtime happens to live in the latter, because `-javaagent` appends the agent jar to the
system class path. **C34 wants the runtime moved into an isolated `AuxinClassLoader`.**
Nothing grants a read edge to that loader's unnamed module:

```
PASS  a named module does NOT read the unnamed module of a hypothetical isolated agent loader,
      even after being transformed
```

So implementing C34 as written would reintroduce exactly the JPMS `IllegalAccessError` A10 warns
about, for every named module, unless bridge delivery is used for named modules at the same time.
Worth recording in the C34 ticket, because today's green JPMS result would silently go red.

### F5 — OBSERVATION: two copies of one class share one probe array
Every child-first scenario loads `iso.child.Widget` **twice** — once by the application loader,
once by the parent-last loader — the Spring Boot fat-jar double definition A10 names.

Both copies are instrumented, no `LinkageError` occurs, and both run correctly. But `ProbeHolder`
keys arrays by **binary name only**, so the two distinct runtime classes share one array and the
reported coverage is their union. `probes=110110` in `child-first-bridge` is `alpha` from the
app-loader copy OR'd with `childOnly` from the child-first copy.

For a coverage union this is sound (A14: union is order-independent and idempotent) and it is what
JaCoCo does too. Two consequences to be aware of rather than fix:
- a class that is dead in one copy and live in the other reports as live;
- the strip handle belongs to whichever copy resolved its condy first, so Tier-1b de-instruments
  that copy and leaves the other one probed forever.

No assertion fails on this; it is recorded so nobody re-derives it from a confusing graph later.

### F6 — the two documented A10 breakages that did **not** reproduce
- **JPMS `IllegalAccessError` for boot-layer modules: did not reproduce, and cannot.** See F4 —
  the VM adds the read edge itself, before the class is linked, as a direct consequence of the
  transform. Exported, opened and fully sealed modules behave identically. A10's Elastic citation
  (`Module 'java.base' no access to: package 'java.lang'`) is about *defining a class into
  `java.lang`*, i.e. the bridge installation step, not about application modules — and that step
  succeeded on all three JDKs in all fifteen scenarios (`bridge=installed`).
- **OSGi boot delegation is genuinely unnecessary.** `osgi-felix-strict` runs with
  `felix.bootdelegation.implicit=false` and **no** `org.osgi.framework.bootdelegation` at all, and
  the bundle class is fully probed. A10's claim holds. Better: `osgi-felix-bootdelegation` shows
  that adding `org.osgi.framework.bootdelegation=io.auxin.*` — the property an operator
  would reach for — **does not help**, because Felix delegates boot-delegated packages to the
  *boot* class loader, which has never seen a jar on the application class path. Getting a
  class-path agent into a bundle needs `bundle.parent=app` as well. The bridge needs neither.

---

## 3. Constant-pool evidence

The A10 claim is that an instrumented class in an isolated loader names nothing but `java.lang`.
`iso.osgi.Service`, loaded by a Felix bundle loader with boot delegation completely off
(`out/dump/17-osgi-felix-strict/iso.osgi.Service.class`):

```
static {};
   0: getstatic     #93   // Field java/lang/$Auxin.data:Ljava/lang/Object;
   3: iconst_2
   4: anewarray     #4    // class java/lang/Object
   7: dup
   8: iconst_0
   9: ldc           #95   // String iso.osgi.Service
  11: aastore
  12: dup
  13: iconst_1
  14: bipush        8
  16: invokestatic  #100  // Method java/lang/Integer.valueOf:(I)Ljava/lang/Integer;
  19: aastore
  20: dup_x1
  21: invokevirtual #104  // Method java/lang/Object.equals:(Ljava/lang/Object;)Z
  24: pop
  25: iconst_0
  26: aaload
  27: checkcast     #105  // class "[Z"
  30: putstatic     #22   // Field $axProbes:[Z
  33: return
```
Every type named is in `java.base`: `java/lang/$Auxin`, `java/lang/Object`,
`java/lang/Integer`, `[Z`, plus the class's own `$axProbes`. `javap -v -p -c | grep -c io/auxin`
returns **0**. The same three assertions pass for `iso.layer.Target` (custom `ModuleLayer`,
bootstrap-parent loader) and `iso.child.Widget` (child-first loader shadowing the agent jar), on
all three JDKs.

For contrast, the condy path in the same suite carries
`io/auxin/agent/runtime/ProbeHolder.bootstrap` as its `BootstrapMethods` entry and no
`java/lang/$Auxin` at all — asserted explicitly so the two paths can never be confused.

`java.lang.$Auxin` itself is defined by the **bootstrap** loader into module **java.base**
(`java.lang` is exported by java.base unconditionally and every module reads java.base
implicitly), which is why a named module needs no read edge, no export and no open to reach it.
The JPMS launch scenarios read their own probe array back through the bridge from inside a fully
sealed module to prove it.

---

## 4. Fallback behaviour — does a failure ever reach application code?

The brief asked specifically whether the `agentNotVisible` fallback triggers rather than throws.

| scenario | bridge status | skip counters | app code |
|---|---|---|---|
| `child-first-skip-bridge-disabled` | `notAttempted` | `agentNotVisible=1` | correct |
| `rival-wins` | `alreadyDefinedByAnotherAgent` | `agentNotVisible=1, bridgeUnavailable=1` | correct |
| `rival-loses` | `installed` | none | correct |
| `bridge-data-tamper` | `installed` | none | **ClassCastException — see F3** |
| `osgi-felix-implicit` | `installed` | none | **ClassNotFoundException — see F1** |

- The fallback is a **decision, not an exception**: `transformFailures == 0` in every skip
  scenario, one `WARN` line per JVM, and the skipped class runs normally — it simply records
  nothing (`childOnly` ran in the skipped copy and its probe stayed `0`, which is the designed
  coverage hole).
- The rival-agent collision is handled cleanly in both directions. When the rival wins,
  `BootstrapBridge.alreadyDefined()` detects it and degrades. When ax-agent wins, the rival gets
  `LinkageError: loader 'bootstrap' attempted duplicate class definition for java.lang.$Auxin`
  and ax-agent is unaffected — the name is a single-owner JVM-global resource, and the loser must
  be prepared to lose.
- **Two paths do reach application code**, and neither is the bridge's own logic: F1 (a visibility
  mispredict, ax-agent's bug) and F3 (deliberate third-party tampering).

---

## 5. What did NOT work, or could not be tested

Honest list.

- **Real Spring Boot `LaunchedClassLoader` was not used.** Test 3 builds a parent-last loader with
  the same delegation order (java.* to the parent, then own URLs, then parent) and reproduces the
  double class definition, but it is not `org.springframework.boot.loader.LaunchedClassLoader` and
  does not exercise nested-jar URL handling or `spring-boot-loader`'s `JarFile` implementation.
  A real fat-jar test needs the Spring Boot build plugin and was out of scope.
- **Tomcat's `WebappClassLoader` was not used** either, for the same reason. The shape is
  reproduced; the implementation is not.
- **JBoss Modules was not tested.** A10 names it (`ClassNotFoundException` on bootstrap-loaded
  shaded classes). `jboss-modules` is a single jar on Maven Central and would fit this harness,
  but it needs a module repository layout on disk and was not attempted. The custom `ModuleLayer`
  with a bootstrap-parent loader is the closest analogue present and it exercises the same two
  gates (visibility and readability) — but it is an analogue, not the thing.
- **GraalVM native image was not tested.** A10 says impossible, not merely hard; nothing here
  disputes that.
- **JDK 8 was not tested** — not installed, and the bridge is JDK 9+ by construction
  (`hasPrivateLookupIn` → `jdk8NoPrivateLookupIn` → `ProbeHolder` fallback). The JDK-8 path is
  therefore unexercised by this suite.
- **Only one OSGi implementation.** Equinox delegates differently from Felix (notably no
  equivalent of `felix.bootdelegation.implicit`), so F1's trigger is Felix-specific even though
  the underlying flaw — a stack-dependent visibility probe — is not. Equinox would be a worthwhile
  second data point.
- **The strip gap (F2) is proved by mechanism, not by a timed strip.** The suite asserts that
  `ProbeHolder.loadedClass()` returns `null` for a bridge-instrumented class, which is what
  `DrainThread.stripCoveredClasses()` requires; it does not wait for a drain cycle and then verify
  the probes are gone. That would have made the suite timing-dependent for no extra certainty
  about the cause.
- **`felix.bootdelegation.classloaders`** (the other Felix escape hatch) was not exercised.
- **No concurrency stress.** Everything is single-threaded except the OSGi bundle's own thread.
  `LoaderVisibility`'s re-entrancy argument (asking a loader a question from inside its own
  `loadClass`) is not stress-tested here; no deadlock was observed in any run, but absence of a
  deadlock in fifteen scenarios is not evidence of its impossibility.
- **The agent was not rebuilt.** The suite tests the jar as committed
  (`sha256 38d41b19da3c26a6aae2…`). Nothing in `modules/` was modified, per the brief.

---

## 6. Layout

```
isolation/
  run-isolation.sh                one command, per-assertion PASS/FAIL, non-zero exit on failure
  RESULTS.md                      this file
  lib/org.apache.felix.framework-7.0.5.jar
  src/jpms/ax.iso.app/            the authored module; open/sealed/layer variants are derived
                                  from it at build time by package rename, so the code under test
                                  is identical and only the module declaration differs
  src/cp/iso/child/Widget.java    the class loaded twice by two loaders
  src/osgi/iso/api/Work.java      service contract, exported from the system bundle
  src/osgi/iso/osgi/              bundle content: Service, Helper (interface), Worker, Activator
  src/harness/iso/check/          class-path drivers -- the only code that talks to the agent
  src/rival/rival/RivalAgent.java second agent; claims or tampers with java.lang.$Auxin
  out/                            build output, per-scenario class dumps, per-scenario logs
```

`out/logs/<jdk>-<scenario>.log` holds the full stdout of every JVM, and
`out/dump/<jdk>-<scenario>/` the exact bytes the agent handed the JVM for every instrumented
class — both are what the constant-pool assertions read.

---

## 7. Every assertion

JDK 17 shown. JDK 11 and JDK 21 produce byte-identical assertion lists and identical results;
the only per-JDK differences anywhere in the logs are class-loader identity hash codes.

```
-- TEST 1a  jpms-launch-app -- java --module-path mods -m ax.iso.app/iso.app.Main
  PASS  running inside a NAMED module (ax.iso.app)
  PASS  Target.alpha(10) == 47 (got 47)
  PASS  Target.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  probe array reachable through java.lang.$Auxin from a named module
  PASS  probe array has 5 slots (got 5)
  PASS  probes fired exactly for the methods that ran (<init>,alpha,beta,touch; NOT never)
  PASS  jpms-launch-app: condy bootstrap is io/auxin ProbeHolder
  PASS  jpms-launch-app: no java.lang bridge reference (condy path)
-- TEST 1a  jpms-launch-open -- java --module-path mods -m ax.iso.open/iso.open.Main
  PASS  running inside a NAMED module (ax.iso.open)
  PASS  Target.alpha(10) == 47 (got 47)
  PASS  Target.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  probe array reachable through java.lang.$Auxin from a named module
  PASS  probe array has 5 slots (got 5)
  PASS  probes fired exactly for the methods that ran (<init>,alpha,beta,touch; NOT never)
  PASS  jpms-launch-open: condy bootstrap is io/auxin ProbeHolder
  PASS  jpms-launch-open: no java.lang bridge reference (condy path)
-- TEST 1a  jpms-launch-sealed -- java --module-path mods -m ax.iso.sealed/iso.sealed.Main
  PASS  running inside a NAMED module (ax.iso.sealed)
  PASS  Target.alpha(10) == 47 (got 47)
  PASS  Target.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  probe array reachable through java.lang.$Auxin from a named module
  PASS  probe array has 5 slots (got 5)
  PASS  probes fired exactly for the methods that ran (<init>,alpha,beta,touch; NOT never)
  PASS  jpms-launch-sealed: condy bootstrap is io/auxin ProbeHolder
  PASS  jpms-launch-sealed: no java.lang bridge reference (condy path)
-- TEST 1b  jpms-classpath-driver -- named module + unnamed-module driver
  PASS  iso.app.Target is defined in named module ax.iso.app
  PASS  ax.iso.app does NOT open iso.app (no deep reflection in)
  PASS  control module ax.iso.sealed (never transformed) does NOT read the unnamed module
  PASS  the TRANSFORMED module reads the unnamed module (jdk.internal.module.Modules.transformedByAgent, added by the VM)
  PASS  Target.alpha(10) == 47 (got 47)
  PASS  Target.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  probe array exists for iso.app.Target
  PASS  <init> probe SET
  PASS  alpha probe SET
  PASS  beta probe SET
  PASS  private touch() probe SET (called from <init>)
  PASS  never() probe NOT set (never called)
  PASS  the control module's class was never instrumented (outside ax.include.packages)
  PASS  a named module does NOT read the unnamed module of a hypothetical isolated agent loader, even after being transformed: moving the runtime off the application class path (C34) would reintroduce the JPMS IllegalAccessError unless bridge delivery is used for named modules
  PASS  java.lang.$Auxin installed under JPMS (status=installed)
  PASS  zero transform failures
  PASS  no class was skipped for agentNotVisible in a boot-layer module
  PASS  jpms-classpath-driver: condy bootstrap is io/auxin ProbeHolder
  PASS  jpms-classpath-driver: no java.lang bridge reference (condy path)
  PASS  jpms-classpath-driver: the control module was never dumped (never transformed)
  PASS  jpms-classpath-driver: control class iso.sealed.Target was never transformed
-- TEST 1d  jpms-custom-layer -- named module, custom layer, bootstrap-parent loader
  PASS  the layer's loader has the BOOTSTRAP loader as parent
  PASS  the layer's loader genuinely cannot resolve ProbeHolder
  PASS  iso.layer.Target is in named module ax.iso.layer
  PASS  that module lives in a NON-BOOT layer
  PASS  Target.alpha(10) == 47 in the custom layer (got 47)
  PASS  Target.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  probe array exists for the layer's class
  PASS  <init> probe SET
  PASS  alpha probe SET
  PASS  beta probe SET
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  nothing was skipped for agentNotVisible: the bridge carried it
  PASS  jpms-custom-layer: constant pool references java/lang/$Auxin.data
  PASS  jpms-custom-layer: probe array arrives through java/lang/Object.equals
  PASS  jpms-custom-layer: constant pool references NOTHING from io/auxin
-- TEST 2  osgi-felix-implicit
  PASS  Felix framework started
  PASS  test bundle started (activator ran)
  PASS  the bundle registered its iso.api.Work service
  PASS  iso.osgi.Service was defined by a Felix bundle class loader
  PASS  the bundle loader's PARENT CHAIN reaches the agent's loader only in bundle.parent=app (chain says false) -- structural delegation, unlike a Class.forName probe, is not stack-dependent and classifies this loader correctly in every mode
  PASS  Felix's DEFAULT felix.bootdelegation.implicit=true exposes a class-path agent to every bundle (no OSGi isolation at all out of the box)
  PASS  SAME LOADER, DIFFERENT ANSWER: the bundle loader resolves Tier2Runtime for application code and refuses it for the bundle's own code. A visibility probe run from the agent's stack cannot predict linkage inside the instrumented class.
  PASS  BUG CONFIRMED: under Felix's DEFAULT config the agent emitted a direct io.auxin.agent.runtime.Tier2Runtime call into a bundle class and it failed to resolve INSIDE APPLICATION CODE (java.lang.ClassNotFoundException: io.auxin.agent.runtime.Tier2Runtime not found by iso.osgi.bundle [1])
  PASS  Service.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  Service.viaInterface(5) == 25 (interface default + static, got 25)
  PASS  bundle-own-thread outcome recorded for the report: OK
  PASS  probe array exists for the bundle's class
  PASS  <init> probe SET (the activator built one)
  PASS  alpha probe SET
  PASS  beta probe SET
  PASS  where() probe SET
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  no bundle class was skipped for agentNotVisible
  PASS  framework stopped cleanly
  PASS  osgi-felix-implicit: condy bootstrap is io/auxin ProbeHolder
  PASS  osgi-felix-implicit: no java.lang bridge reference (condy path)
-- TEST 2  osgi-felix-strict
  PASS  Felix framework started
  PASS  test bundle started (activator ran)
  PASS  the bundle registered its iso.api.Work service
  PASS  iso.osgi.Service was defined by a Felix bundle class loader
  PASS  the bundle loader's PARENT CHAIN reaches the agent's loader only in bundle.parent=app (chain says false) -- structural delegation, unlike a Class.forName probe, is not stack-dependent and classifies this loader correctly in every mode
  PASS  strict OSGi: the bundle cannot resolve the agent at all
  PASS  Service.alpha(10) == 47 inside the bundle (got 47)
  PASS  Service.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  Service.viaInterface(5) == 25 (interface default + static, got 25)
  PASS  a thread the BUNDLE started ran an instrumented class correctly (OK)
  PASS  probe array exists for the bundle's class
  PASS  <init> probe SET (the activator built one)
  PASS  alpha probe SET
  PASS  beta probe SET
  PASS  where() probe SET
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  no bundle class was skipped for agentNotVisible
  PASS  BRIDGE COST (a): no strip handle for the bundle class, so Tier-1b can NEVER de-instrument it -- its probes stay on the hot path for the life of the JVM
  PASS  BRIDGE COST (b): the bundle's INTERFACE was skipped (interfaceNeedsField); default and static interface methods get no coverage in OSGi
  PASS  BRIDGE COST (c): the tier-2 method degraded to tier-1 (tier2NotBridgeable), which is the designed fail-open
  PASS  framework stopped cleanly
  PASS  osgi-felix-strict: constant pool references java/lang/$Auxin.data
  PASS  osgi-felix-strict: probe array arrives through java/lang/Object.equals
  PASS  osgi-felix-strict: constant pool references NOTHING from io/auxin
-- TEST 2  osgi-felix-bootdelegation
  PASS  Felix framework started
  PASS  test bundle started (activator ran)
  PASS  the bundle registered its iso.api.Work service
  PASS  iso.osgi.Service was defined by a Felix bundle class loader
  PASS  the bundle loader's PARENT CHAIN reaches the agent's loader only in bundle.parent=app (chain says false) -- structural delegation, unlike a Class.forName probe, is not stack-dependent and classifies this loader correctly in every mode
  PASS  org.osgi.framework.bootdelegation=io.auxin.* alone does NOT expose the agent: it delegates to the BOOT loader, which has no agent jar
  PASS  Service.alpha(10) == 47 inside the bundle (got 47)
  PASS  Service.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  Service.viaInterface(5) == 25 (interface default + static, got 25)
  PASS  a thread the BUNDLE started ran an instrumented class correctly (OK)
  PASS  probe array exists for the bundle's class
  PASS  <init> probe SET (the activator built one)
  PASS  alpha probe SET
  PASS  beta probe SET
  PASS  where() probe SET
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  no bundle class was skipped for agentNotVisible
  PASS  BRIDGE COST (a): no strip handle for the bundle class, so Tier-1b can NEVER de-instrument it -- its probes stay on the hot path for the life of the JVM
  PASS  BRIDGE COST (b): the bundle's INTERFACE was skipped (interfaceNeedsField); default and static interface methods get no coverage in OSGi
  PASS  BRIDGE COST (c): the tier-2 method degraded to tier-1 (tier2NotBridgeable), which is the designed fail-open
  PASS  framework stopped cleanly
  PASS  osgi-felix-bootdelegation: constant pool references java/lang/$Auxin.data
  PASS  osgi-felix-bootdelegation: probe array arrives through java/lang/Object.equals
  PASS  osgi-felix-bootdelegation: constant pool references NOTHING from io/auxin
-- TEST 2  osgi-felix-bootdelegation-app
  PASS  Felix framework started
  PASS  test bundle started (activator ran)
  PASS  the bundle registered its iso.api.Work service
  PASS  iso.osgi.Service was defined by a Felix bundle class loader
  PASS  the bundle loader's PARENT CHAIN reaches the agent's loader only in bundle.parent=app (chain says true) -- structural delegation, unlike a Class.forName probe, is not stack-dependent and classifies this loader correctly in every mode
  PASS  bootdelegation + bundle.parent=app DOES expose the agent to the bundle
  PASS  Service.alpha(10) == 47 inside the bundle (got 47)
  PASS  Service.beta("hello") == HELLO:5 (got HELLO:5)
  PASS  Service.viaInterface(5) == 25 (interface default + static, got 25)
  PASS  a thread the BUNDLE started ran an instrumented class correctly (OK)
  PASS  probe array exists for the bundle's class
  PASS  <init> probe SET (the activator built one)
  PASS  alpha probe SET
  PASS  beta probe SET
  PASS  where() probe SET
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  no bundle class was skipped for agentNotVisible
  PASS  condy path DOES capture a strip handle (class iso.osgi.Service)
  PASS  condy path DOES probe the interface (probes=11)
  PASS  condy path carries tier-2 into the bundle
  PASS  framework stopped cleanly
  PASS  osgi-felix-bootdelegation-app: condy bootstrap is io/auxin ProbeHolder
  PASS  osgi-felix-bootdelegation-app: no java.lang bridge reference (condy path)
-- TEST 3  child-first-condy
  PASS  app-loader Widget.alpha(10) == 47
  PASS  the first copy really is defined by the application class loader
  PASS  without shadowing the child-first loader resolves the agent's own ProbeHolder
  PASS  the second copy is defined by the child-first loader
  PASS  two DISTINCT runtime classes now share the binary name iso.child.Widget (the Spring Boot fat-jar double definition)
  PASS  child-first Widget.alpha(10) == 47 (got 47)
  PASS  child-first Widget.childOnly(4) == 11 (got 11)
  PASS  probe array exists for iso.child.Widget
  PASS  alpha probe SET
  PASS  childOnly probe SET -- it ran ONLY in the child-first copy, so this bit proves the second copy's probes reached the AGENT's ProbeHolder
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  app-loader copy still correct afterwards
  PASS  child-first copy still correct afterwards
  PASS  child-first-condy: condy bootstrap is io/auxin ProbeHolder
  PASS  child-first-condy: no java.lang bridge reference (condy path)
-- TEST 3  child-first-bridge
  PASS  app-loader Widget.alpha(10) == 47
  PASS  the first copy really is defined by the application class loader
  PASS  the child-first loader SHADOWS ProbeHolder with its own copy (Class.forName succeeds but the class is not the agent's)
  PASS  the second copy is defined by the child-first loader
  PASS  two DISTINCT runtime classes now share the binary name iso.child.Widget (the Spring Boot fat-jar double definition)
  PASS  child-first Widget.alpha(10) == 47 (got 47)
  PASS  child-first Widget.childOnly(4) == 11 (got 11)
  PASS  probe array exists for iso.child.Widget
  PASS  alpha probe SET
  PASS  childOnly probe SET -- it ran ONLY in the child-first copy, so this bit proves the second copy's probes reached the AGENT's ProbeHolder
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  app-loader copy still correct afterwards
  PASS  child-first copy still correct afterwards
  PASS  child-first-bridge (child-first copy, dumped last): constant pool references java/lang/$Auxin.data
  PASS  child-first-bridge (child-first copy, dumped last): probe array arrives through java/lang/Object.equals
  PASS  child-first-bridge (child-first copy, dumped last): constant pool references NOTHING from io/auxin
-- TEST 3c  child-first-skip-bridge-disabled -- ax.bridge.enabled=false
  PASS  app-loader Widget.alpha(10) == 47
  PASS  the first copy really is defined by the application class loader
  PASS  the child-first loader SHADOWS ProbeHolder with its own copy (Class.forName succeeds but the class is not the agent's)
  PASS  the second copy is defined by the child-first loader
  PASS  two DISTINCT runtime classes now share the binary name iso.child.Widget (the Spring Boot fat-jar double definition)
  PASS  child-first Widget.alpha(10) == 47 (got 47)
  PASS  child-first Widget.childOnly(4) == 11 (got 11)
  PASS  the bridge is NOT installed in this JVM (status=notAttempted)
  PASS  the child-first class was SKIPPED and counted as agentNotVisible
  PASS  the skip was a decision, not a transform failure (0 transform failures)
  PASS  the app-loader copy of Widget IS still instrumented (alpha probe set)
  PASS  childOnly ran only in the SKIPPED copy and recorded nothing: a coverage hole, which is the designed fail-open, not an outage
  PASS  app-loader copy still correct afterwards
  PASS  child-first copy still correct afterwards
  PASS  child-first-skip-bridge-disabled: agent reports bridge=notAttempted
-- TEST 3d  rival-wins -- a second agent owns java.lang.$Auxin
  PASS  app-loader Widget.alpha(10) == 47
  PASS  the first copy really is defined by the application class loader
  PASS  the child-first loader SHADOWS ProbeHolder with its own copy (Class.forName succeeds but the class is not the agent's)
  PASS  the second copy is defined by the child-first loader
  PASS  two DISTINCT runtime classes now share the binary name iso.child.Widget (the Spring Boot fat-jar double definition)
  PASS  child-first Widget.alpha(10) == 47 (got 47)
  PASS  child-first Widget.childOnly(4) == 11 (got 11)
  PASS  the bridge is NOT installed in this JVM (status=alreadyDefinedByAnotherAgent)
  PASS  the child-first class was SKIPPED and counted as agentNotVisible
  PASS  the skip was a decision, not a transform failure (0 transform failures)
  PASS  the app-loader copy of Widget IS still instrumented (alpha probe set)
  PASS  childOnly ran only in the SKIPPED copy and recorded nothing: a coverage hole, which is the designed fail-open, not an outage
  PASS  app-loader copy still correct afterwards
  PASS  child-first copy still correct afterwards
  PASS  rival-wins: the rival agent really did define java.lang.$Auxin first
  PASS  rival-wins: ax-agent detected the collision (bridge=alreadyDefinedByAnotherAgent)
  PASS  rival-wins: the fallback was logged once, as a warning, not an exception
-- TEST 3f  bridge-data-tamper -- ADVERSARIAL: a third party overwrites java.lang.$Auxin.data
  PASS  app-loader Widget.alpha(10) == 47
  PASS  the first copy really is defined by the application class loader
  PASS  the child-first loader SHADOWS ProbeHolder with its own copy (Class.forName succeeds but the class is not the agent's)
  PASS  the second copy is defined by the child-first loader
  PASS  two DISTINCT runtime classes now share the binary name iso.child.Widget (the Spring Boot fat-jar double definition)
  PASS  ax-agent installed the bridge before the tamper (status=installed)
  PASS  HAZARD CONFIRMED: overwriting the public static java.lang.$Auxin.data breaks application code (java.lang.ClassCastException: class java.lang.String cannot be cast to class [Z (java.lang.String and [Z are in module java.base of loader 'bootstrap')). The field is a JVM-global mutable hook; ax-agent cannot detect or defend this today. Same exposure as JaCoCo's java.lang.$JaCoCo.
  PASS  classes NOT routed through the bridge (condy path) are unaffected by the tamper
  PASS  bridge-data-tamper: the tamper really happened
-- TEST 3e  rival-loses -- ax-agent claims the name first, the rival must not break anything
  PASS  app-loader Widget.alpha(10) == 47
  PASS  the first copy really is defined by the application class loader
  PASS  the child-first loader SHADOWS ProbeHolder with its own copy (Class.forName succeeds but the class is not the agent's)
  PASS  the second copy is defined by the child-first loader
  PASS  two DISTINCT runtime classes now share the binary name iso.child.Widget (the Spring Boot fat-jar double definition)
  PASS  child-first Widget.alpha(10) == 47 (got 47)
  PASS  child-first Widget.childOnly(4) == 11 (got 11)
  PASS  probe array exists for iso.child.Widget
  PASS  alpha probe SET
  PASS  childOnly probe SET -- it ran ONLY in the child-first copy, so this bit proves the second copy's probes reached the AGENT's ProbeHolder
  PASS  never() probe NOT set
  PASS  zero transform failures
  PASS  app-loader copy still correct afterwards
  PASS  child-first copy still correct afterwards
  PASS  rival-loses: the rival lost the race and survived it
  PASS  rival-loses: ax-agent still owns a working bridge
  PASS  rival-loses (child-first copy): constant pool references java/lang/$Auxin.data
  PASS  rival-loses (child-first copy): probe array arrives through java/lang/Object.equals
  PASS  rival-loses (child-first copy): constant pool references NOTHING from io/auxin
```

**Totals — JDK 17: 250/250. JDK 11: 250/250. JDK 21: 250/250. Suite: 750/750.**
