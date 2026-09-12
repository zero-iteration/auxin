# deploy/ — Kyverno injection policy + Helm chart

Injects the Auxin Java agent into opted-in pods, and hardens those pods against the
failure mode that class-transforming agents actually cause in production: a startup CPU
burn, CFS throttling, a missed 1-second liveness probe, and a CrashLoopBackOff that cannot
recover.

> ## Not tested. Read this first.
>
> **The policies are now EXECUTED, not just parsed.** `kyverno apply` evaluates a policy
> against real resources with no cluster and no Docker. `./verify.sh` runs it and asserts
> the **mutated pod specs**, field by field, against `test/pods.yaml` (12 fixtures) and
> `test/deployments.yaml` (3 fixtures). 60 live assertions plus 137 structural ones.
>
> That first run found four real bugs, three of which silently disabled the product:
>
> | | bug | effect |
> |---|---|---|
> | 1 | quoted keys in a JMESPath multi-select hash (`{'initialDelaySeconds': ...}`) | `SyntaxError`, policy would not load |
> | 2 | `parse_quantity()` is not a Kyverno JMESPath function | R10 CPU guard refused **every** pod |
> | 3 | `spec.webhookConfiguration.namespaceSelector`/`objectSelector` are not Kyverno fields | policy silently dropped; `kyverno apply` printed `error: 0`, exit 0 — which is how 1 and 2 hid behind 133 green static checks |
> | 4 | `gtInitPresent == 0` on every rule | gt-01 created the initContainer, which made the guard false for gt-02..gt-14: pods got an emptyDir and an initContainer and **nothing else** |
>
> Still **unverified** — `kyverno apply` is an offline engine, not a cluster:
> * that the API server admits these CRs (the CLI uses a bundled copy of the CRD schema);
> * that the generated `MutatingWebhookConfiguration` registers and scopes as expected;
> * that the webhook admits pods when Kyverno is down (R1/R2's actual claim);
> * that the `generate` rules create Events (needs the background controller plus the RBAC
>   in `kyverno/kyverno-rbac-events.yaml`);
> * that the agent image exists or that the jar lands in the emptyDir at runtime;
> * anything about performance or Kyverno HA under load.
>
> Next step for all of the above: a kind cluster.

---

## Layout

```
deploy/
  helm/auxin-inject/
    Chart.yaml  values.yaml
    templates/    configmap.yaml, policies.yaml, kyverno-pdb.yaml, NOTES.txt
    files/        ax-inject-policy.yaml    <- CANONICAL policy, plain YAML
                  ax-events-policy.yaml    <- async Warning Events
                  ax-rollout-policy.yaml   <- progressDeadlineSeconds on Deployments
  kyverno/
    kyverno-values.yaml                    <- values for the UPSTREAM kyverno chart (HA)
    kyverno-rbac-events.yaml               <- lets the background controller create Events
    kyverno-configmap-webhooks.yaml        <- fallback selectors for Kyverno < 1.13
    auxin-inject-config.example.yaml <- the ConfigMap, for the raw-apply path
  examples/demo-deployment.yaml            <- the demo/ app, opted in + a counter-example
  PREFLIGHT.md  RUNBOOK.md  verify.sh
```

### Why the policies are not Helm-templated

Kyverno's variables and Helm's are both `{{ }}`. Sharing a file means escaping every
Kyverno expression, and a policy one mis-escaped brace away from silently not matching is
not a policy you want on an admission path. So:

* `files/*.yaml` are **plain YAML**, shipped verbatim by `.Files.Get` (which does not run
  the Go template engine). They are the single source of truth and they are directly
  parseable by `verify.sh`.
* Everything installation-specific lives in a ConfigMap the policy **reads at admission
  time** (`context: axcfg`). Kyverno serves it from an informer cache, so it is not an API
  round trip.
* `verify.sh` cross-checks that the chart and the policy agree about that ConfigMap's name,
  namespace and keys.

One consequence, stated plainly: the excluded-namespace list is static, because **Kyverno
does not allow variables in `match`/`exclude` blocks**. If your collector namespace is not
`auxin-system`, edit `files/ax-inject-policy.yaml` (webhook selector + every rule's
`exclude` block) and `files/ax-rollout-policy.yaml`.

### Install

```sh
helm upgrade --install kyverno kyverno/kyverno -n kyverno --create-namespace \
  -f deploy/kyverno/kyverno-values.yaml            # >=2 replicas, PDB, anti-affinity
kubectl apply -f deploy/kyverno/kyverno-rbac-events.yaml
helm upgrade --install auxin-inject deploy/helm/auxin-inject \
  -n auxin-system --create-namespace --set config.createNamespace=true
```

Opt a workload in on the **pod template**:

```yaml
labels:      { auxin.dev/inject: "true" }
annotations: { auxin.dev/include-packages: "com.acme.",
               auxin.dev/artifact: "checkout-service",
               auxin.dev/build-sha: "abc123def" }
```

---

## Requirement trace

Each row names the failure it prevents. `verify.sh` asserts each one against the parsed
manifests.

| # | requirement | where | what it prevents |
|---|---|---|---|
| **R1** | `failurePolicy: Ignore` | `spec.failurePolicy` on all three policies | Kyverno's `Fail` default turns an unreachable webhook into a cluster-wide pod-creation outage. The OTel Operator uses `Ignore` on its pod webhook for exactly this reason. |
| **R2** | never return `allowed: false` | mutate/generate only; no `validate`, no `deny`, no `Enforce` anywhere | `failurePolicy: Ignore` covers **transport** failure only. A webhook that returns HTTP 200 with `allowed:false` blocks pods anyway — kyverno#6873, on a java-agent injection policy, worked on 1.7.x and broke on 1.9.1. |
| **R3** | `kube-system`, `kube-public`, `kube-node-lease`, `kyverno`, collector ns excluded | every rule's `exclude.resources.namespaces` (engine-side) + `kyverno/kyverno-configmap-webhooks.yaml` (API-server-side, cluster-wide) | a webhook that can wedge the namespace running the webhook, or the control plane. |
| **R4** | opt-in via pod **label** `auxin.dev/inject: "true"`, plus explicit opt-out | every rule's `match.resources.selector.matchLabels` + `exclude` | a label is cheap to filter; an annotation read inside the webhook means every pod in the cluster pays a round trip to be rejected. **Note:** this is enforced engine-side, not by a per-policy `objectSelector` — that field does not exist in Kyverno's schema (see `test/README.md` BUG 3). The API-server-side pre-filter is available as a `webhookConfiguration.matchConditions` overlay, documented in the policy header; it is a performance filter, not a safety control. |
| **R5** | `webhookTimeoutSeconds: 2`; Kyverno >= 2 replicas + PDB + anti-affinity | `spec.webhookTimeoutSeconds`; `kyverno/kyverno-values.yaml` | the 10s default turns a wedged Kyverno into a fleet-wide pod-creation stall. HA matters even at `Ignore`: a Kyverno outage silently produces uninstrumented pods, and missing coverage looks exactly like dead code. |
| **R6** | idempotency on **both** the initContainer AND the `-javaagent:` substring | `gtInitPresent` on gt-01 and gt-12/13/14 **only**, plus a per-rule guard on every other rule; `contains(...)` on `JAVA_TOOL_OPTIONS`; strategic-merge keys | an object can legitimately reach a webhook more than once. Guarding on only one of the two lets a second pass double the agent. **The pod-level guard must not go on the shared anchor:** Kyverno re-binds `request.object` to the patched resource between rules, so gt-01 creating the initContainer disables every rule after it (`test/README.md` BUG 4). |
| **R7** | `JAVA_TOOL_OPTIONS`: absent → set **with a leading space**; literal → **append**; `valueFrom` → **skip the pod + warn** | rules gt-03 / gt-04 / gt-12 + the events policy | JVM TI: *"the variable should not be overwritten, instead, options should be appended"*. Never a second env entry with the same name: the kubelet resolves duplicates last-wins, which is how SkyWalking's injector silently discards the application's own value. |
| **R8** | `JAVA_TOOL_OPTIONS` only | asserted by `verify.sh` against the parsed env patches | `_JAVA_OPTIONS` is undocumented and has the highest precedence (it stomps the app); `JDK_JAVA_OPTIONS` is launcher-only and invisible to Tomcat/Jetty/JBoss/embedded JVMs. |
| **R9** | `startupProbe` (period >= 10, failureThreshold >= 30, timeout >= 5); `livenessProbe.timeoutSeconds >= 5` **and** `failureThreshold >= 5`; burstable QoS (`limits.cpu >= 2x requests.cpu`); raised `progressDeadlineSeconds` | rules gt-05 (floor) → gt-06/gt-07 (create) → gt-08..gt-10 + the rollout policy. **Order is load-bearing** — see `test/README.md`. | the 1-second liveness default is the self-reinforcing kill: throttled pod misses the probe → restart → more backlog → fail again. Burstable QoS is the biggest single lever measured (90s → 30s). |
| **R10** | refuse to inject when `limits.cpu < 1` (or warn, explicitly) | `gtMinCpuLimit` precondition + skip annotation + Warning Event; `cpuGuardMode` | same app, varying only the limit: 2 cores → 10-15s ready, 0.5 core → ~40s; a real incident at 0.896 cores gave ~90s startup and 5xx. |
| **R11** | initContainer + `emptyDir` delivers the agent jar | rule gt-01 + gt-02 | no agent in the app image, no init logic in the app's entrypoint. |

### Design notes worth knowing before you edit anything

* **Mutate-only is the R2 mechanism.** Kyverno mutate rules have no code path that denies.
  The loud warnings are `generate` rules in a separate policy, executed by the background
  controller *after* the admission response has been sent. An Audit-mode `validate` rule
  would also work, and is one field (`failureAction: Enforce`) away from blocking every pod
  in the cluster — a field that gets flipped during incidents. There is no `validate:` and
  no `deny:` anywhere in `deploy/`, and `verify.sh` enforces that.
* **Everything fails open.** Missing ConfigMap → context variable does not resolve → the
  mutate rule is skipped → the pod is admitted unmodified. Missing annotation → no
  injection. Missing label → the API server never calls us. The cost of a mistake is an
  uninstrumented pod, never a pod that will not start. The corollary is that failures are
  **silent**, which is why every skip is also written to the pod as a
  `auxin.dev/skip-reason` annotation.
* **`valueFrom` skips the whole pod, not just the container.** Partial injection would
  produce coverage from some containers and not others, with nothing in the data saying so.
* **Probe floors use `max()`**, so the policy never narrows a setting the app chose itself.
  Every one of them is written `max([to_number(to_string(x)), <floor>])`: `go-jmespath`'s
  `max()` type-asserts every element to float64 and rejects an integer straight off the
  resource with `Invalid type for: [2 10]`.
* **Burstable QoS mutates resources.** When `requests.cpu` is absent it is set to
  `limits.cpu / 2` (which changes scheduling); when both are present, `limits.cpu` is
  raised to `2x requests.cpu` (which does not — limits are not scheduled on). If your
  platform team owns resource policy, turn this off and let them own it.
* **`progressDeadlineSeconds` is a Deployment field**, so a pod webhook physically cannot
  set it. Hence the separate `auxin-rollout` policy, which matches Deployments via a
  precondition on the pod template label (an `objectSelector` would match the Deployment's
  own labels and filter it out — and Kyverno has no per-policy `objectSelector` anyway).
* **`startupProbe` needs a handler we cannot invent.** We copy the container's own
  `livenessProbe` handler, or its `readinessProbe` handler when there is no liveness. A
  container with neither gets no startupProbe: with no liveness probe there is no
  throttle-induced restart vector to protect against.

---

## verify.sh

```sh
./deploy/verify.sh                        # PHASE 1 live (kyverno) + PHASE 2 static
KYVERNO_BIN=/path/to/kyverno ./deploy/verify.sh
AX_VERIFY_NO_YAML=1 ./deploy/verify.sh    # exercise the degraded grep path
```

**PHASE 1 — LIVE. This is the phase that matters.** It shells out to the real `kyverno`
CLI and asserts, in order:

1. every policy file **loads** — a non-zero applied-rule count. `kyverno apply` exits 0
   and prints `pass: 0, fail: 0, error: 0` for a policy it silently refused to load, so
   the rule count is the only reliable signal;
2. `error: 0` across every rule and every fixture;
3. the **mutated output**, field by field — the leading space on a created
   `JAVA_TOOL_OPTIONS`, the surviving `-Xmx512m` on an appended one, the skip reasons,
   the probe floors, the QoS rewrite, the untouched opt-out pod.

It also **self-tests the load detector**: it re-injects the exact invalid field that
caused the original masking into a temp copy and asserts the load check catches it. A
check that cannot fail is not a check.

If the `kyverno` binary is missing the phase is skipped with a loud banner and the script
exits `INCOMPLETE` (non-zero). A phase that did not run is not a pass.

**PHASE 2 — STATIC.** Parses every shipped manifest with PyYAML under a
**duplicate-key-rejecting loader** (YAML silently keeps the last duplicate, which in a k8s
manifest means a field you wrote is simply gone), then asserts R1-R11 against the object
graph. It carries explicit regression guards for all four bugs above. It is a
cross-check; on its own it proves nothing, which is the whole lesson of this directory.

If no PyYAML is available, phase 1 degrades to grep assertions **over the mutated output**
(still meaningful) and phase 2 is skipped, loudly.

It cannot check semantics. See the box at the top.

---

## Related

* `PREFLIGHT.md` — node kernel floor, startup-window CFS throttling alert, AOT/JEP-483
  check, agent-ordering check.
* `RUNBOOK.md` — the `Picked up JAVA_TOOL_OPTIONS` line the JVM prints in every pod (and
  in every `mvn`, `keytool` and `jshell` in the container), skip reasons, CrashLoopBackOff
  triage, kill switches.
* `../demo/` — the target application with a known dead-code ground truth (gate G5).
