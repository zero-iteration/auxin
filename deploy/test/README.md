# Kyverno policy tests — RUN THESE, they found four real bugs

`kyverno` CLI (1.19.1, `brew install kyverno`) evaluates policies **without a cluster**.
This is how the "unproven JMESPath" gap gets closed. `deploy/verify.sh` phase 1 runs all
of this for you; the commands below are for poking at it by hand.

```sh
cd deploy
kyverno apply helm/auxin-inject/files/ax-inject-policy.yaml \
  --resource test/pods.yaml --values-file test/values.yaml -o /tmp/out

kyverno apply helm/auxin-inject/files/ax-rollout-policy.yaml \
  --resource test/deployments.yaml -o /tmp/outd

kyverno apply helm/auxin-inject/files/ax-events-policy.yaml \
  --resource test/pods.yaml --values-file test/values.yaml

# per-rule verdicts — the fastest way to see WHICH rule skipped and why
kyverno apply helm/auxin-inject/files/ax-inject-policy.yaml \
  --resource test/pods.yaml --values-file test/values.yaml --detailed-results -t

# why did a policy not load? the loader only says at -v 6
kyverno apply <policy> --resource test/pods.yaml -v 6 2>&1 | grep -i "unknown field"
```

---

## Bugs this harness found (2026-09-12)

### BUG 1 — FIXED. Quoted keys in a JMESPath multi-select hash
`merge(element.livenessProbe, {'initialDelaySeconds': ...})` ->
`SyntaxError: Expected tQuotedIdentifier or tUnquotedIdentifier`. Multi-select hash keys
must be **unquoted**. 10 occurrences. Regression guard: `verify.sh` R9
`multi-select hash keys are UNQUOTED`.

### BUG 2 — FIXED. `parse_quantity()` is not a Kyverno JMESPath function
```
$ kyverno jp query "parse_quantity('2')"
Error: error evaluating JMESPath expression: unknown function: parse_quantity
```
`kyverno jp function` lists every function Kyverno adds. `parse_quantity` is not one of
them (`add subtract multiply divide modulo round compare semver_compare ...` are).

An unknown function fails the **whole context variable**, so Kyverno fell back to
`default: 0`, and `0 < 1` made the R10 CPU guard refuse **every pod**. `case-a-no-jto`
declares `limits.cpu: "2"` and still came out
`auxin.dev/skip-reason: limits.cpu < 1 on at least one container`. The product
injected nothing, anywhere, and said `error: 0` while doing it.

The quantity-aware primitive Kyverno actually has is arithmetic:

| expression | result |
|---|---|
| `divide('2','1')`    | `2` |
| `divide('500m','1')` | `0.5` |
| `divide('1500m','1')`| `1.5` |
| `to_number('500m')`  | `null` — do not use |

So: `min(map(&divide(to_string(@), '1'), <list of quantity strings>))`.

`to_string()` first because the API server always serialises quantities as strings but a
raw YAML fixture can carry `cpu: 2` as a JSON number, and `divide(number, string)` is a
`Types mismatch` error.

**Do not "simplify" this to `min(...resources.limits.cpu)`.** JMESPath `min()` over
strings is LEXICOGRAPHIC: `min(['10','2'])` is `'10'` and `min(['2','500m'])` is `'2'`.
Both answers are wrong by a factor of 4-5.

**Pod with NO cpu limit at all -> INJECT.** The filter yields an empty list, `min([])` is
null, the context default makes it 0 — but the guard is satisfied through the
`gtCpuLimitCount == 0` branch, and the gt-13 skip annotation requires
`gtCpuLimitCount > 0`. No limit means no CFS quota, so there is no throttling to protect
the pod from. Pinned by `case-f-no-cpu-limit`.

### BUG 3 — FIXED. `spec.webhookConfiguration` MASKED every policy load error

```
$ kyverno apply ax-inject-policy.yaml --resource test/pods.yaml -v 6
... skipping invalid YAML file  error="failed to parse document (
      spec.webhookConfiguration.namespaceSelector: Invalid value: value provided for unknown field
      spec.webhookConfiguration.objectSelector:    Invalid value: value provided for unknown field)"
Applying 0 policy rule(s) to 5 resource(s)...
pass: 0, fail: 0, warn: 0, error: 0, skip: 0
$ echo $?
0
```

`namespaceSelector` and `objectSelector` are **MutatingWebhookConfiguration** fields, not
Kyverno ClusterPolicy fields. Kyverno's schema has exactly one key under
`webhookConfiguration`: `matchConditions`. A real API server prunes the unknown ones
(structural-schema pruning), so they never filtered anything there either — they looked
like enforcement and did nothing.

The CLI instead drops the whole file, and reports it identically to a clean run:
`error: 0`, exit code 0. **That is how BUG 1 and BUG 2 hid behind 133 green static
checks.** The only reliable signal is the applied-rule count.

`matchConditions` *is* a real field — and it **segfaults** both `kyverno apply` and
`kyverno test` when they run without a cluster:
```
panic: runtime error: invalid memory address or nil pointer dereference
github.com/kyverno/kyverno/pkg/validation/policy.Validate(...)
        pkg/validation/policy/validate.go:126
```
so shipping it would make the policy untestable offline. It is documented as an optional
overlay in the policy header instead. R3/R4 are enforced engine-side by every rule's
`match`/`exclude` (proved by `case-e` and `case-h`) plus the cluster-wide
`deploy/kyverno/kyverno-configmap-webhooks.yaml`.

Regression guards in `verify.sh`:
* every policy must report a **non-zero applied-rule count**;
* `-v 6` must emit no `unknown field` / `skipping invalid YAML file` diagnostic;
* a **self-test** that re-injects the exact bad field into a temp copy and asserts the
  load check catches it. A check that cannot fail is not a check.

### BUG 4 — FIXED. The R6 idempotency guard self-disabled the whole policy

Only visible once BUG 2 was fixed and rules started firing:

```
gt-01-agent-volume-and-init   case-a-no-jto   Pass   mutated ...
gt-02-mount-agent-volume      case-a-no-jto   Skip   preconditions not met
gt-03-java-tool-options-set   case-a-no-jto   Skip   preconditions not met
...                                           Skip   preconditions not met
```

Kyverno applies mutate rules in declaration order and re-binds `request.object` to the
resource **as patched by the previous rules**. `{{ gtInitPresent }} == 0` was on the
shared precondition anchor, so the moment gt-01 created `auxin-agent-init` the
guard was false for everything after it. The pod came out with an emptyDir and an
initContainer that copies the jar — and **no volumeMount, no `JAVA_TOOL_OPTIONS`, no
probe widening, no annotation**. A pod that looks instrumented, mounts nothing, and runs
a JVM that never loads the agent. Worse than BUG 2, because it looks like it worked.

Fix: the pod-level guard lives on gt-01 (the rule that creates the initContainer) and on
gt-12/13/14 (which must never mark an injected pod as skipped). Every other rule carries
its own idempotency precondition against the *current* state. Pinned by
`case-g-already-injected` and by an explicit `verify.sh` R6 check that the guard has not
leaked back onto the anchor.

### Also fixed: rule order, and `max()` type strictness

`go-jmespath`'s `max()` accepts `array[number] | array[string]` and type-asserts every
element to float64. A probe field that arrives as an integer fails it:
`Invalid type for: [2 10]`. Two changes:

* every `max()` over a resource-sourced value is wrapped
  `max([to_number(to_string(x)), <floor>])`;
* **rule order**: `gt-05-startup-probe-floor` now runs BEFORE
  `gt-06-startup-probe-from-liveness` / `gt-07-startup-probe-from-readiness`. With the
  create rules first, the floor rule re-read a startupProbe built by JMESPath `merge()`
  and `max()` rejected the numbers in it — `error: 1`. Being honest about this one: the
  coercion above fixes it independently, and reversing the order today still gives
  `error: 0`. The order is kept as defence in depth and because it stops the floor rule
  re-patching a probe the policy itself just wrote — it is not the thing holding the
  policy up. The coercion is.

---

## Fixture cases — `pods.yaml`

| pod | tests | expected |
|---|---|---|
| `case-a-no-jto` | R7 absent, R9 probes, R11 delivery | JTO **created with a leading space**; initContainer + emptyDir + read-only mount; liveness 5/5; startupProbe from the liveness handler at 30x10s |
| `case-b-literal-jto` | R7 literal -> **APPEND** | value still starts `-Xmx512m`, `-javaagent:` appended, exactly one env entry, exactly one `-javaagent:` |
| `case-c-valuefrom-jto` | R7 `valueFrom` | whole pod skipped + reason; nothing injected; the app's `valueFrom` untouched |
| `case-d-sub-one-cpu` | R10 at 500m | skipped + `limits.cpu < 1` reason; also injected when `cpuGuardMode=warn` |
| `case-e-not-opted-in` | R4 | completely untouched |
| `case-f-no-cpu-limit` | R10 boundary | **injected**, no skip-reason |
| `case-g-already-injected` | R6 | no duplicate initContainer / mount / env / `-javaagent:` |
| `case-h-opted-out` | R4 opt-out | untouched despite the opt-in label |
| `case-i-narrow-startup-probe` | R9 floor + rule order | `/startup` handler preserved, 2->10, 3->30, 1->5 |
| `case-j-guaranteed-qos` | R9 QoS (a) | requests.cpu absent -> `"1"` (Guaranteed -> Burstable) |
| `case-k-tight-limits` | R9 QoS (b) | limits `"1"` -> `"2"` |
| `case-l-no-scope` | default-deny scope | skipped + `include-packages` reason |

## Fixture cases — `deployments.yaml`

| deployment | expected |
|---|---|
| `dep-a-optin` | `progressDeadlineSeconds: 1200` |
| `dep-b-already-generous` | 1800 stays 1800 (`max()` never narrows) |
| `dep-c-not-opted-in` | untouched |

## `values.yaml`

Stands in for the `auxin-system/auxin-inject-config` ConfigMap that the policy
reads through `context: axcfg`. Every `{{ axcfg.data.<key> }}` the policy references must
appear here or the rule evaluates an unresolved variable — `verify.sh` phase 2 asserts
that this file covers every key the policy reads.
