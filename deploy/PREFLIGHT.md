# PREFLIGHT — run this before instrumenting anything

Four checks. Each one exists because skipping it produces a failure that looks like
"the Auxin agent broke our service" and is in fact a property of the node, the
kernel, or another agent.

Nothing in this document has been executed on the build host: there is no Docker, no
kubectl and no cluster here. Every command below is written to be run by you, on the
target cluster, and has not been run by us.

---

## 1. Node kernel version — CFS over-throttling

Kubernetes issue #67577 (open since 2018): the CFS bandwidth controller throttles
containers that are *under* their quota. Indeed's fix landed in these kernels:

| distro / series | minimum version |
|---|---|
| 4.14.x | **4.14.154** |
| 4.19.x | **4.19.84** |
| 5.3.x | **5.3.9** |
| RHEL/CentOS 7 | **3.10.0-1062.8.1** |
| RHEL/CentOS 8 | **4.18.0-147.2.1** |
| 5.4+ / 6.x | fixed |

Anything older over-throttles under bursty CPU — and class transformation at JVM startup
is exactly a CPU burst. On an unpatched kernel the agent will be blamed for a kernel bug.

```sh
# Every node, kernel version, sorted:
kubectl get nodes -o custom-columns=\
'NODE:.metadata.name,KERNEL:.status.nodeInfo.kernelVersion,OS:.status.nodeInfo.osImage' \
  | sort -k2

# Fail the preflight if any node is below the floor for its series.
```

If you cannot patch: pin instrumented workloads to patched nodes with a nodeSelector, or
run them with no CPU limit at all (no quota => no CFS throttling; you trade isolation for
startup predictability). Do not "fix" it by lowering the agent's work — the throttling is
not proportional to the work.

---

## 2. Alert on CFS throttling **during the startup window specifically**

A steady-state throttling alert will not fire for this failure mode. The burn is minutes
long, at startup, and then gone — averaged over an hour it disappears. The signal is
`container_cpu_cfs_throttled_periods_total` **restricted to the first minutes of a
container's life**.

```promql
# Throttled fraction, evaluated only while the container is younger than 10 minutes.
(
  rate(container_cpu_cfs_throttled_periods_total{container!="",container!="POD"}[2m])
  /
  clamp_min(rate(container_cpu_cfs_periods_total{container!="",container!="POD"}[2m]), 1)
)
and on (namespace, pod, container)
(
  (time() - on (namespace, pod) group_left() kube_pod_start_time) < 600
)
> 0.25
```

```yaml
# Alert rule. Threshold 25% of periods throttled, for 2 minutes, inside the startup window.
groups:
  - name: auxin-startup
    rules:
      - alert: AuxinStartupThrottling
        expr: |
          (
            rate(container_cpu_cfs_throttled_periods_total{container!="",container!="POD"}[2m])
            / clamp_min(rate(container_cpu_cfs_periods_total{container!="",container!="POD"}[2m]), 1)
          )
          and on (namespace, pod, container)
          ((time() - on (namespace, pod) group_left() kube_pod_start_time) < 600)
          > 0.25
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.namespace }}/{{ $labels.pod }} is CFS-throttled during startup"
          runbook: deploy/RUNBOOK.md#cfs-throttling-at-startup
```

Companion signals worth a panel next to it:
* `kube_pod_container_status_restarts_total` — a rising count with throttling is the
  self-reinforcing livenessProbe kill.
* time-to-ready per pod: `kube_pod_status_ready` minus `kube_pod_start_time`.
* The reference numbers to compare against: same app, 2 cores -> 10-15s ready;
  0.5 core -> ~40s; a real incident at 0.896 cores -> ~90s startup, 5xx served, fixed to
  750ms/30s by burstable QoS + startupProbe.

---

## 3. Confirm no AOT cache is in use — JEP 483 is mutually exclusive with us

JDK 24+ Ahead-of-Time Class Loading & Linking (`-XX:AOTMode`, `-XX:AOTCache`,
`-XX:AOTConfiguration`) **must not be combined with a JVMTI agent that rewrites
classfiles**. It is not a performance trade-off; the JVM refuses or the cache is invalid.
Datadog and Dynatrace are already blocked by this. AOT is worth ~42% off startup on
JDK 24 (PetClinic 4.486s -> 2.604s), so this is a real choice a customer may refuse to
make in our favour.

Check both the image and the effective runtime flags:

```sh
# 1. Any AOT flag in the pod spec, anywhere in the fleet:
kubectl get pods -A -o json | \
  grep -E -- '-XX:(AOTMode|AOTCache|AOTConfiguration)' && echo "AOT IN USE — STOP"

# 2. And in the environment (JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS / _JAVA_OPTIONS),
#    which is where it usually actually lives:
kubectl get pods -A -o jsonpath=\
'{range .items[*]}{.metadata.namespace}{"/"}{.metadata.name}{"\t"}{range .spec.containers[*]}{.env[?(@.name=="JAVA_TOOL_OPTIONS")].value}{" "}{.env[?(@.name=="JDK_JAVA_OPTIONS")].value}{" "}{.env[?(@.name=="_JAVA_OPTIONS")].value}{end}{"\n"}{end}' \
  | grep -i aot

# 3. From inside a running container, the authoritative answer:
kubectl exec <pod> -- sh -c 'java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -i aot'
```

Related, same family: **AppCDS** requires `-XX:+AllowArchivingWithJavaAgent`, and the JVM
itself warns that flag is "for testing purposes only... not... production". If a workload
depends on AppCDS, treat instrumenting it as a decision, not a rollout.

If AOT is in use: that workload is out of scope. Say so in the inventory rather than
injecting and letting the JVM fail at startup.

---

## 4. Agent ordering check

`ClassFileTransformer` ordering is decided by *capability*, not by `-javaagent` flag
order: retransformation-**incapable** transformers run first, and capable ones receive
their output. `ax-agent`'s installer registers as incapable, JaCoCo is incapable, the
OpenTelemetry javaagent is capable. So the intended order is:

```
JaCoCo (incapable) -> ax-agent ProbeInstaller (incapable) -> OTel (capable)
```

Verify on a canary pod, under traffic, before fleet rollout:

```sh
# a. What agents are actually attached (order as the JVM scanned them):
kubectl exec <pod> -- sh -c 'cat /proc/1/cmdline | tr "\0" "\n" | grep -- -javaagent'
kubectl exec <pod> -- printenv JAVA_TOOL_OPTIONS

# b. Did OUR transformer run and did it fail silently? The JVM ignores exceptions thrown
#    from a transformer, so absence of errors proves nothing. Read our counters instead:
kubectl exec <pod> -- sh -c 'curl -s localhost:9464/metrics | grep -E "ax_transform_failures_total|ax_classes_skipped_total"'

# c. Three-agent liveness (gate G5): after driving traffic, assert for the SAME class that
#    our probe fired AND JaCoCo reports the line AND an OTel span exists. Two of three is
#    a failure, not a partial pass. The OTel/SkyWalking conflict was invisible until the
#    first request triggered a retransform.
```

Known conflicts to look for explicitly:
* `LinkageError: loader 'app' attempted duplicate class definition` — OTel + Datadog.
* `UnsupportedOperationException: class redefinition failed: attempted to delete a method`
  — anything retransforming a JaCoCo-instrumented class (`$jacocoInit`/`$jacocoData`).
* Instrumentation that silently vanishes after the first request — a cached
  `TypeDescription` in a ByteBuddy-based agent. Symptom: spans/probes present at startup,
  gone under traffic.
* `org.jacoco.` is **not** in OTel's hard-coded ignore list. Ours must be.

Also confirm the multi-agent budget: every additional agent multiplies matcher evaluation
across every loaded class. A custom ByteBuddy agent with ~15 transformers took one app
from 30s to 10 minutes of startup. Count transformers, not agents.

---

## Preflight checklist

- [ ] Every node kernel is at or above the floor in §1.
- [ ] `AuxinStartupThrottling` alert is deployed and firing correctly against a
      deliberately under-provisioned test pod.
- [ ] No workload in scope uses `-XX:AOTMode` / `-XX:AOTCache` / AppCDS.
- [ ] Agent order verified on a canary under traffic; `ax_transform_failures_total` is 0
      and `ax_classes_skipped_total` is explainable.
- [ ] Kyverno is >= 1.13, running >= 2 replicas, with a PDB and pod anti-affinity
      (deploy/kyverno/kyverno-values.yaml).
- [ ] `auxin-system/auxin-inject-config` ConfigMap exists — without it every
      mutate rule is skipped and pods are silently admitted uninstrumented.
- [ ] Target namespaces are NOT in the excluded list, and target pods carry
      `auxin.dev/inject: "true"` plus `auxin.dev/include-packages`.
