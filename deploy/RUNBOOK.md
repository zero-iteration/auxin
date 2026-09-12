# RUNBOOK — Auxin agent injection

Written for whoever gets paged, and for whoever is about to file a bug that is not a bug.

Nothing here has been executed against a cluster — there is no Docker or kubectl on the
build host. Commands are written for you to run; expected outputs are specifications.

---

## 0. THE ONE EVERYONE FILES AS A BUG

### `Picked up JAVA_TOOL_OPTIONS: -javaagent:/ax-agent/ax-agent.jar ...`

**This line is printed by the JVM itself, to stderr, in every instrumented pod. It is not
an error. It is not our code. There is no flag to turn it off.**

```
Picked up JAVA_TOOL_OPTIONS:  -javaagent:/ax-agent/ax-agent.jar -Dax.collector.url=... 
```

What it means: HotSpot prints this whenever `JAVA_TOOL_OPTIONS` is non-empty, so that an
externally-injected flag can never be invisible. It is a deliberate anti-surprise feature
of the JVM TI specification. You will see it:

* on the first line of every container's logs, before any application output;
* **once per JVM started in the container — not once per pod.**

That second point is the one that generates tickets:

> **`JAVA_TOOL_OPTIONS` is read by EVERY JVM that starts in the container.**
> Not just your application. Also: `mvn`, `gradle`, `keytool`, `jshell`, `jar`, `jcmd`,
> `jstack`, `jmap`, any `java -version` in a healthcheck, any wrapper or entrypoint script
> that shells out to a JVM, any build step that still runs inside the runtime image.

Consequences to expect and NOT to treat as incidents:

| symptom | cause | action |
|---|---|---|
| `Picked up JAVA_TOOL_OPTIONS` in the output of a `keytool` init container | keytool is a JVM | none — cosmetic |
| A script that does `if [ -z "$(java -version 2>&1)" ]` breaks | the extra stderr line | fix the script's parsing, not the env var |
| `mvn` in a debug shell prints the line and attaches the agent | mvn is a JVM | harmless; the agent finds no matching packages and does nothing |
| The agent appears attached to a short-lived helper JVM | same variable, same container | harmless, but it does cost that JVM a few ms of startup |
| The line is absent in a pod you expected to be instrumented | env var not set, or JVM ran setuid | go to §2 |

Two real exceptions worth knowing:
* `JAVA_TOOL_OPTIONS` is **ignored when the JVM is started setuid/setgid** — a deliberate
  security property. A setuid-launched JVM will be silently uninstrumented.
* Precedence, later wins: `JAVA_TOOL_OPTIONS` -> command line -> `_JAVA_OPTIONS`. An app
  that sets `_JAVA_OPTIONS` overrides everything we do, silently.

**Why we use this variable anyway, and not the quieter alternatives:**

| variable | why not |
|---|---|
| `_JAVA_OPTIONS` | undocumented, highest precedence — it stomps the application's own flags. Never use. |
| `JDK_JAVA_OPTIONS` | honoured only by the `java` **launcher**. Invisible to Tomcat, Jetty, JBoss and every embedded/exec'd JVM. Would silently instrument nothing in exactly the deployments we care about. |
| `JAVA_TOOL_OPTIONS` | the only one honoured by all of them. Noisy, and specified to be noisy. |

If you want the line gone, the answer is to stop injecting into that container (remove the
`auxin.dev/inject` label), not to change the variable.

---

## 1. Did the pod get instrumented?

Three sources, in order of durability:

```sh
# 1. The annotation we stamp on the pod (durable, survives restarts):
kubectl get pod POD -o jsonpath='{.metadata.annotations.auxin\.io/injected}{"\n"}'
kubectl get pod POD -o jsonpath='{.metadata.annotations.auxin\.io/skip-reason}{"\n"}'

# 2. The pod spec itself:
kubectl get pod POD -o jsonpath='{.spec.initContainers[*].name}{"\n"}'
kubectl get pod POD -o jsonpath='{range .spec.containers[*]}{.env[?(@.name=="JAVA_TOOL_OPTIONS")].value}{"\n"}{end}'

# 3. Warning Events (garbage-collected after ~1h):
kubectl get events --field-selector involvedObject.name=POD | grep Auxin
```

`auxin.dev/injected: "false"` always carries a `skip-reason`. The four reasons:

1. **`JAVA_TOOL_OPTIONS uses valueFrom`** — we cannot read it at admission time, and adding
   a second env entry with the same name would let the kubelet's last-wins resolution
   silently destroy the app's own value. Fix: inline the value, or accept no instrumentation.
2. **`limits.cpu < 1`** — see §3.
3. **no `auxin.dev/include-packages` annotation** — scope is default-deny.
4. *(no annotation at all)* — the pod never reached the webhook. Label missing, namespace
   excluded, Kyverno down (failurePolicy `Ignore` means pods are admitted silently), or the
   `auxin-inject-config` ConfigMap is missing so every rule was skipped.

---

## 2. Pod started but the agent did nothing

Symptoms: no `Picked up JAVA_TOOL_OPTIONS` line, or the line is there but no coverage
arrives at the collector.

```sh
# Is the jar actually in the shared volume?
kubectl exec POD -c CONTAINER -- ls -l /ax-agent/

# Did the initContainer succeed?
kubectl logs POD -c auxin-agent-init

# Agent health counters (CONTRACTS.md §2 agentHealth is mandatory for a reason):
kubectl exec POD -c CONTAINER -- sh -c 'curl -s localhost:9464/metrics | grep ^gt_'
```

Check in this order:

| check | meaning |
|---|---|
| `ax_transform_failures_total > 0` | our transformer threw. **The JVM silently ignores transformer exceptions and continues the class load** — without this counter the failure is invisible and the affected methods look dead. Never publish a dead-code verdict from a window with a non-zero value. |
| `ax_classes_skipped_total{reason="noManifestEntry"}` high | the manifest does not match the running build. Identity is `(buildSha, className, methodDesc)`; a stale manifest means no probes. |
| `degraded: true` in the flush payload | circuit breaker tripped (startup CPU budget) or clock degraded. **A degraded window is never evidence of death.** |
| classes loaded before the agent installed | permanently out of scope. They report `unknown`, never dead. |

---

## 3. CrashLoopBackOff shortly after enabling injection

This is the documented failure mode and it is almost never the agent's logic.

**Mechanism:** transform work at startup burns CPU -> the container hits its CFS quota ->
everything slows -> the `livenessProbe` misses its **1-second default timeout** -> kubelet
restarts the container -> the restart re-does all the transform work with a cold JIT ->
fails again. It is self-reinforcing and, once entered, mathematically unlikely to recover.

Confirm it:

```sh
kubectl describe pod POD | grep -E 'Liveness|Startup|Restart|Killing|Unhealthy'
# and the throttling query from deploy/PREFLIGHT.md §2
```

Fix, in order of leverage:

1. **Burstable QoS** — `limits.cpu >= 2 x requests.cpu`. Biggest single lever (a documented
   incident went 90s -> 30s). The policy already does this; if the pod does not have it,
   the mutation did not run.
2. **`startupProbe`** with `periodSeconds >= 10`, `failureThreshold >= 30`,
   `timeoutSeconds >= 5` — buys 300s and suppresses liveness AND readiness until ready.
   Necessary but not sufficient on its own.
3. **`livenessProbe.timeoutSeconds >= 5`, `failureThreshold >= 5`** — the policy raises
   both. If you see `timeoutSeconds: 1` on an instrumented pod, the mutation did not run.
4. Narrow `ax.include.packages`. Scope is the dominant overhead lever, not probe
   cleverness: a production JaCoCo install scoped with `includes=` measured **0.03%**
   overhead; the 10-25-74% numbers in the literature are all unscoped.
5. `-XX:TieredStopAtLevel=3` via `extraJvmArgs` — helps warmup, costs peak throughput.
   A tourniquet, not a fix.

**Immediate mitigation (no redeploy of the policy):**

```sh
# Per-workload: stop injecting, keep everything else.
kubectl patch deploy DEPLOY --type=json \
  -p='[{"op":"add","path":"/spec/template/metadata/labels/auxin.io~1inject-optout","value":"true"}]'
kubectl rollout restart deploy DEPLOY
```

---

## 4. Kill switches, biggest hammer last

| scope | action | blast radius |
|---|---|---|
| one pod template | add label `auxin.dev/inject-optout: "true"` | that workload, next restart |
| one workload | remove label `auxin.dev/inject` | that workload, next restart |
| all new pods | `kubectl delete clusterpolicy auxin-inject` | nothing running changes; no new pod is instrumented |
| all new pods, reversibly | delete the `auxin-inject-config` ConfigMap — every mutate rule's context fails to resolve, rules are skipped, pods admitted unmodified | same, and trivially reversible |
| running JVMs | the agent's own three-level kill switch (agent / tier / package) | in-process, no restart |

Note what is **not** a kill switch: deleting the policy does not remove the agent from
running pods. Pods keep their injected spec until they restart.

---

## 5. Coverage is missing for a workload — before you conclude "dead code"

Missing coverage and dead code are the same shape in the data. Rule them out in order:

1. Was the pod instrumented at all (§1)?
2. Was the window degraded (`agentHealth.degraded`, `transformFailures`, `ringDropped`)?
3. Was the class ever **loaded**? `classesLoaded` exists precisely so that "never loaded"
   is distinguishable from "loaded but never invoked". A class a pod never loaded is
   `unknown`, not dead.
4. Did the window cover the required business phases (month-end, quarter-end, year-end
   close, peak, DR drill)? A 90-day window that missed year-end close is not 90 days of
   evidence.
5. Are the pods you sampled exchangeable? Sticky sessions, shard affinity, leader election,
   singleton/cron pods and region-pinned traffic each systematically hide live code.
   Pod-subset coverage is a sound lower bound on live code, **never evidence of death**.
6. Is the method in `.auxin/suppress.txt`? Break-glass code looks dead by construction.
7. Is it public API surface? Our runtime data says nothing about someone else's classpath.

---

## 6. Quick reference

```sh
# Everything we injected, for one pod:
kubectl get pod POD -o json | jq '{
  injected: .metadata.annotations["auxin.dev/injected"],
  skip:     .metadata.annotations["auxin.dev/skip-reason"],
  scope:    .metadata.annotations["auxin.dev/scope"],
  init:     [.spec.initContainers[]?.name],
  volumes:  [.spec.volumes[]?.name],
  jto:      [.spec.containers[].env[]? | select(.name=="JAVA_TOOL_OPTIONS") | .value],
  liveness: [.spec.containers[] | {n:.name, t:.livenessProbe.timeoutSeconds, f:.livenessProbe.failureThreshold}],
  startup:  [.spec.containers[] | {n:.name, p:.startupProbe.periodSeconds, f:.startupProbe.failureThreshold}],
  cpu:      [.spec.containers[] | {n:.name, req:.resources.requests.cpu, lim:.resources.limits.cpu}]
}'

# Every pod the policy refused, cluster-wide:
kubectl get pods -A -o json | jq -r '.items[]
  | select(.metadata.annotations["auxin.dev/injected"]=="false")
  | "\(.metadata.namespace)/\(.metadata.name)\t\(.metadata.annotations["auxin.dev/skip-reason"])"'
```
