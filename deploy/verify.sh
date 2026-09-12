#!/usr/bin/env bash
# =============================================================================
# deploy/verify.sh — verification of the Auxin injection manifests
# =============================================================================
# TWO PHASES, and the order matters.
#
#   PHASE 1  LIVE.   Runs the real `kyverno` CLI. `kyverno apply` evaluates a policy
#                    against real resources with NO cluster and NO Docker, so there is
#                    no excuse for not running it. It asserts, in order:
#                      a) every policy file LOADS   -> non-zero applied-rule count
#                      b) no rule errors            -> `error: 0`
#                      c) the MUTATED POD SPECS     -> the actual output, field by field
#
#   PHASE 2  STATIC. Parses the YAML and checks the requirement structure (R1..R11).
#
# >>> WHY THE ORDER. This script used to be phase 2 only: 133 string checks over the
#     policy text, all green, against a policy that Kyverno REFUSED TO LOAD. Two
#     invalid fields (`spec.webhookConfiguration.namespaceSelector` / `.objectSelector`)
#     made `kyverno apply` drop the file and still print `Applying 0 policy rule(s)`,
#     `pass: 0, fail: 0, error: 0`, exit code 0 — identical to a clean run. A JMESPath
#     syntax error and an "unknown function" bug hid behind that for the file's whole
#     life. Grep-checking YAML proves nothing about whether Kyverno accepts it.
#     PHASE 1 IS THE LOAD-BEARING PHASE. Phase 2 is a structural cross-check.
#
# Usage:  ./deploy/verify.sh [-v]
#         KYVERNO_BIN=/path/to/kyverno ./deploy/verify.sh
# Exit:   0 everything passed
#         1 a check failed
#         2 a file is missing/unparseable, or a prerequisite is unusable
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERBOSE="${1:-}"
KYVERNO_BIN="${KYVERNO_BIN:-kyverno}"

live_rc=0
static_rc=0

# --- pick a python that has PyYAML -------------------------------------------
# AX_VERIFY_NO_YAML=1 forces the degraded path, so the fallback is itself testable.
PY=""
if [ -z "${AX_VERIFY_NO_YAML:-}" ]; then
  for cand in python3 /usr/bin/python3 python /opt/homebrew/bin/python3; do
    if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import yaml' >/dev/null 2>&1; then
      PY="$cand"; break
    fi
  done
fi

# =============================================================================
# PHASE 1 — LIVE: run the real kyverno CLI
# =============================================================================
echo "=============================================================================="
echo "PHASE 1  LIVE — kyverno apply (no cluster required)"
echo "=============================================================================="

if ! command -v "$KYVERNO_BIN" >/dev/null 2>&1; then
  cat <<'LOUD'

  ############################################################################
  ##                                                                        ##
  ##   !!  THE KYVERNO CLI IS NOT INSTALLED — NOTHING BELOW IS PROVEN  !!    ##
  ##                                                                        ##
  ##   Only the static structural checks will run. Those are the checks      ##
  ##   that once passed 133/133 against a policy Kyverno REFUSED TO LOAD.    ##
  ##   They cannot tell you whether this policy works. They never could.     ##
  ##                                                                        ##
  ##       brew install kyverno          # or                                ##
  ##       https://kyverno.io/docs/kyverno-cli/install/                      ##
  ##                                                                        ##
  ##   Then re-run this script. It needs no cluster and no Docker.           ##
  ##                                                                        ##
  ############################################################################

LOUD
  live_rc=3   # 3 == "not run", distinct from 1 == "failed"
elif [ -z "$PY" ]; then
  echo
  echo "  !! no python3 with PyYAML — running the DEGRADED live checks (grep-based)."
  echo "     Install PyYAML for the field-by-field mutation assertions:"
  echo "         python3 -m pip install pyyaml"
  echo
  fail=0
  rm -rf /tmp/gt-verify-degraded   # stale output from a previous run is a false pass
  out="$("$KYVERNO_BIN" apply "$ROOT/helm/auxin-inject/files/ax-inject-policy.yaml" \
          --resource "$ROOT/test/pods.yaml" --values-file "$ROOT/test/values.yaml" \
          -o /tmp/gt-verify-degraded 2>&1)"
  n="$(printf '%s' "$out" | sed -n 's/.*Applying \([0-9]*\) policy rule(s).*/\1/p' | head -1)"
  if [ "${n:-0}" -gt 0 ]; then echo "  PASS  policy LOADS ($n rules applied)"
  else echo "  FAIL  policy applied 0 rules — it did not load"; fail=1; fi
  if printf '%s' "$out" | grep -q "error: 0"; then echo "  PASS  error: 0"
  else echo "  FAIL  rule evaluation errors"; fail=1; fi
  a=/tmp/gt-verify-degraded/case-a-no-jto-mutated.yaml
  b=/tmp/gt-verify-degraded/case-b-literal-jto-mutated.yaml
  if grep -q "value: ' -javaagent:/ax-agent/ax-agent.jar" "$a" 2>/dev/null; then
    echo "  PASS  case-a JAVA_TOOL_OPTIONS created with a LEADING SPACE"
  else echo "  FAIL  case-a JAVA_TOOL_OPTIONS missing or has no leading space"; fail=1; fi
  if grep -q "auxin-agent-init" "$a" 2>/dev/null; then
    echo "  PASS  case-a initContainer injected"
  else echo "  FAIL  case-a initContainer missing"; fail=1; fi
  if grep -q "Xmx512m -javaagent:" "$b" 2>/dev/null; then
    echo "  PASS  case-b APPENDED (app's -Xmx512m survived)"
  else echo "  FAIL  case-b lost the application's own JAVA_TOOL_OPTIONS"; fail=1; fi
  live_rc=$fail
else
  KYVERNO_BIN="$KYVERNO_BIN" VERBOSE="$VERBOSE" "$PY" - "$ROOT" <<'LIVEEOF'
import os, re, subprocess, sys, tempfile, shutil
import yaml

ROOT = sys.argv[1]
KY   = os.environ.get("KYVERNO_BIN", "kyverno")
FILES   = os.path.join(ROOT, "helm", "auxin-inject", "files")
INJECT  = os.path.join(FILES, "ax-inject-policy.yaml")
EVENTS  = os.path.join(FILES, "ax-events-policy.yaml")
ROLLOUT = os.path.join(FILES, "ax-rollout-policy.yaml")
PODS    = os.path.join(ROOT, "test", "pods.yaml")
DEPLOYS = os.path.join(ROOT, "test", "deployments.yaml")
VALUES  = os.path.join(ROOT, "test", "values.yaml")

results = []
def check(group, label, ok, detail=""):
    results.append((group, label, bool(ok), str(detail)))
    return bool(ok)

ANSI = re.compile(r"\x1b\[[0-9;]*m")
RE_RULES = re.compile(r"Applying (\d+) policy rule\(s\) to (\d+) resource\(s\)")
RE_TOTAL = re.compile(r"pass: (\d+), fail: (\d+), warn: (\d+), error: (\d+), skip: (\d+)")

def run(policy, resource, values=None, outdir=None, extra=None):
    cmd = [KY, "apply", policy, "--resource", resource, "--remove-color"]
    if values:  cmd += ["--values-file", values]
    if outdir:  cmd += ["-o", outdir]
    if extra:   cmd += extra
    p = subprocess.run(cmd, capture_output=True, text=True)
    blob = ANSI.sub("", (p.stdout or "") + (p.stderr or ""))
    rules  = RE_RULES.search(blob)
    totals = RE_TOTAL.search(blob)
    return {
        "cmd": " ".join(cmd),
        "out": blob,
        "rc": p.returncode,
        "rules": int(rules.group(1)) if rules else 0,
        "resources": int(rules.group(2)) if rules else 0,
        "pass":  int(totals.group(1)) if totals else -1,
        "fail":  int(totals.group(2)) if totals else -1,
        "warn":  int(totals.group(3)) if totals else -1,
        "error": int(totals.group(4)) if totals else -1,
        "skip":  int(totals.group(5)) if totals else -1,
    }

def load_mutated(outdir, name):
    p = os.path.join(outdir, name + "-mutated.yaml")
    if not os.path.exists(p):
        return None
    with open(p) as fh:
        docs = [d for d in yaml.safe_load_all(fh) if d]
    return docs[0] if docs else None

def container(pod, name="app"):
    for c in (pod.get("spec", {}).get("containers") or []):
        if c.get("name") == name:
            return c
    return {}

def env_of(c):
    return {e["name"]: e for e in (c.get("env") or [])}

def anns(pod):
    return (pod.get("metadata", {}).get("annotations") or {})

def initc(pod, name="auxin-agent-init"):
    return [i for i in (pod.get("spec", {}).get("initContainers") or []) if i.get("name") == name]

# =========================================================== 1. policies LOAD
# The check that would have caught the whole class of bug. `kyverno apply` exits 0 and
# prints `error: 0` for a policy it silently refused to load, so the ONLY reliable
# signal is a non-zero applied-rule count.
class Bail(Exception):
    """Stop asserting, but ALWAYS fall through to the report. An early sys.exit() here
    would print nothing and exit 0 — the same silent-green failure this file exists to
    prevent."""

tmp = tempfile.mkdtemp(prefix="gt-verify-")
try:
    loads = {}
    for label, path, res, vals in (
        ("ax-inject-policy",  INJECT,  PODS,    VALUES),
        ("ax-events-policy",  EVENTS,  PODS,    VALUES),
        ("ax-rollout-policy", ROLLOUT, DEPLOYS, None),
    ):
        r = run(path, res, vals, extra=["-v", "6"])
        loads[label] = r
        check("LOAD", "%s: kyverno LOADED it (applied %d rule(s), not 0)" % (label, r["rules"]),
              r["rules"] > 0,
              "0 rules applied — the file was dropped. Re-run with -v 6 to see why.")
        # -v 6 is where the loader logs the reason it dropped a file. Any diagnostic
        # naming this path is a silent-drop, whatever the cause.
        bad = [l for l in r["out"].splitlines()
               if os.path.basename(path) in l
               and ("skipping invalid YAML file" in l or "unknown field" in l
                    or "failed to parse document" in l)]
        check("LOAD", "%s: loader emitted no unknown-field / parse diagnostic" % label,
              not bad, bad[0][:180] if bad else "")

    # --- self-test of the detector above ------------------------------------
    # A check that cannot fail is not a check. Re-introduce the exact field that caused
    # the original masking and assert that the load check CATCHES it. If this ever
    # passes, the detector has gone blind and every LOAD result above is worthless.
    poisoned = os.path.join(tmp, "poisoned-policy.yaml")
    src = open(INJECT).read()
    marker = "  rules:\n"
    assert marker in src
    src = src.replace(marker,
        "  webhookConfiguration:\n"
        "    namespaceSelector:\n"
        "      matchExpressions:\n"
        "        - key: kubernetes.io/metadata.name\n"
        "          operator: NotIn\n"
        "          values: [kube-system]\n" + marker, 1)
    open(poisoned, "w").write(src)
    pr = run(poisoned, PODS, VALUES)
    check("LOAD", "SELF-TEST: a policy with spec.webhookConfiguration.namespaceSelector "
                  "is detected as NOT loading",
          pr["rules"] == 0 and pr["error"] == 0 and pr["rc"] == 0,
          "poisoned copy applied %d rule(s) / error=%d / exit=%d — the masking bug is "
          "either fixed upstream or this detector is blind"
          % (pr["rules"], pr["error"], pr["rc"]))

    # ======================================================= 2. inject: mutations
    outdir = os.path.join(tmp, "inject")
    r = run(INJECT, PODS, VALUES, outdir=outdir)
    # `error: 0` from a policy that applied 0 rules is the masking bug, not a pass.
    check("RUN", "ax-inject-policy: error: 0 across every rule and every fixture",
          r["error"] == 0 and r["rules"] > 0, "error: %d  (rules %d / pass %d / fail %d / skip %d)"
          % (r["error"], r["rules"], r["pass"], r["fail"], r["skip"]))
    check("RUN", "ax-inject-policy: fail: 0", r["fail"] == 0, "fail: %d" % r["fail"])
    check("RUN", "ax-inject-policy: at least one rule actually mutated something",
          r["pass"] > 0, "pass: %d" % r["pass"])

    pods = {n: load_mutated(outdir, n) for n in (
        "case-a-no-jto", "case-b-literal-jto", "case-c-valuefrom-jto", "case-d-sub-one-cpu",
        "case-e-not-opted-in", "case-f-no-cpu-limit", "case-g-already-injected",
        "case-h-opted-out", "case-i-narrow-startup-probe", "case-j-guaranteed-qos",
        "case-k-tight-limits", "case-l-no-scope")}
    missing = [n for n, p in pods.items() if not p]
    if missing:
        check("RUN", "every fixture produced a mutated document", False,
              "no output for %s — the policy probably did not load" % missing)
        raise Bail()
    check("RUN", "every fixture produced a mutated document", True)

    JAVAAGENT = "-javaagent:/ax-agent/ax-agent.jar"

    # ---------------------------------------------------------------- case-a
    # R7 case 1 (absent -> SET with a leading space) + R9 probes + R11 delivery.
    a  = pods["case-a-no-jto"]; ca = container(a); ea = env_of(ca)
    jto = ea.get("JAVA_TOOL_OPTIONS", {}).get("value")
    check("case-a", "JAVA_TOOL_OPTIONS was CREATED", jto is not None)
    check("case-a", ">>> created value starts with a LEADING SPACE (R7)",
          isinstance(jto, str) and jto.startswith(" " + JAVAAGENT), "got %r" % (jto or "")[:50])
    check("case-a", "exactly one JAVA_TOOL_OPTIONS env entry (no kubelet last-wins)",
          sum(1 for e in (ca.get("env") or []) if e["name"] == "JAVA_TOOL_OPTIONS") == 1)
    check("case-a", "R8: no _JAVA_OPTIONS / JDK_JAVA_OPTIONS anywhere in the pod",
          not ({"_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"} & set(ea)))
    check("case-a", "downward-API env GT_POD_NAME / GT_NAMESPACE injected",
          "fieldRef" in (ea.get("GT_POD_NAME", {}).get("valueFrom") or {})
          and "fieldRef" in (ea.get("GT_NAMESPACE", {}).get("valueFrom") or {}))
    ic = initc(a)
    check("case-a", "R11: exactly one auxin-agent-init initContainer", len(ic) == 1)
    check("case-a", "R11: initContainer copies ax-agent.jar into the shared volume",
          bool(ic) and any("ax-agent.jar" in str(x) for x in (ic[0].get("command") or [])))
    vols = {v["name"]: v for v in (a["spec"].get("volumes") or [])}
    check("case-a", "R11: emptyDir volume auxin-agent with a sizeLimit",
          "auxin-agent" in vols
          and bool((vols.get("auxin-agent", {}).get("emptyDir") or {}).get("sizeLimit")))
    mounts = {m["name"]: m for m in (ca.get("volumeMounts") or [])}
    check("case-a", "R11: app container mounts it read-only at /ax-agent",
          mounts.get("auxin-agent", {}).get("mountPath") == "/ax-agent"
          and mounts.get("auxin-agent", {}).get("readOnly") is True)
    lp = ca.get("livenessProbe") or {}
    check("case-a", ">>> R9: livenessProbe.timeoutSeconds raised 1 -> 5",
          lp.get("timeoutSeconds") == 5, "got %r" % lp.get("timeoutSeconds"))
    check("case-a", ">>> R9: livenessProbe.failureThreshold raised 3 -> 5",
          lp.get("failureThreshold") == 5, "got %r" % lp.get("failureThreshold"))
    sp = ca.get("startupProbe") or {}
    check("case-a", "R9: startupProbe created from the liveness HANDLER",
          (sp.get("httpGet") or {}).get("path") == "/health")
    check("case-a", "R9: startupProbe budget = 30 x 10s, timeout 5s, no initial delay",
          sp.get("failureThreshold") == 30 and sp.get("periodSeconds") == 10
          and sp.get("timeoutSeconds") == 5 and sp.get("initialDelaySeconds") == 0,
          "got %r" % sp)
    check("case-a", "provenance annotation auxin.dev/injected=true",
          anns(a).get("auxin.dev/injected") == "true")
    check("case-a", ">>> R10 does NOT fire on a compliant pod (limits.cpu 2 >= 1)",
          "auxin.dev/skip-reason" not in anns(a),
          anns(a).get("auxin.dev/skip-reason", "")[:90])

    # ---------------------------------------------------------------- case-b
    # The SkyWalking regression. A blind overwrite destroys the app's own JVM flags.
    b = pods["case-b-literal-jto"]; cb = container(b); eb = env_of(cb)
    jtob = eb.get("JAVA_TOOL_OPTIONS", {}).get("value", "")
    check("case-b", ">>> the application's own -Xmx512m SURVIVED (SkyWalking bug)",
          "-Xmx512m" in jtob, "got %r" % jtob[:70])
    check("case-b", ">>> our -javaagent: was APPENDED to it", JAVAAGENT in jtob,
          "got %r" % jtob[:70])
    check("case-b", "the app's value is the PREFIX — appended, not prepended or replaced",
          jtob.startswith("-Xmx512m"), "got %r" % jtob[:40])
    check("case-b", "exactly ONE env entry named JAVA_TOOL_OPTIONS",
          sum(1 for e in (cb.get("env") or []) if e["name"] == "JAVA_TOOL_OPTIONS") == 1)
    check("case-b", "exactly ONE -javaagent: in the value", jtob.count("-javaagent:") == 1)
    check("case-b", "pod was injected", anns(b).get("auxin.dev/injected") == "true")

    # ---------------------------------------------------------------- case-c
    c = pods["case-c-valuefrom-jto"]; cc = container(c)
    check("case-c", "pod is SKIPPED (injected=false)",
          anns(c).get("auxin.dev/injected") == "false")
    check("case-c", ">>> skip-reason names valueFrom",
          "valueFrom" in anns(c).get("auxin.dev/skip-reason", ""),
          anns(c).get("auxin.dev/skip-reason", "")[:90])
    check("case-c", "nothing was injected: no initContainer",  not initc(c))
    check("case-c", "nothing was injected: no auxin volume",
          not [v for v in (c["spec"].get("volumes") or []) if v["name"] == "auxin-agent"])
    check("case-c", "the app's valueFrom JAVA_TOOL_OPTIONS is untouched",
          "valueFrom" in env_of(cc).get("JAVA_TOOL_OPTIONS", {}))

    # ---------------------------------------------------------------- case-d
    d = pods["case-d-sub-one-cpu"]
    check("case-d", ">>> R10 FIRES on limits.cpu 500m",
          anns(d).get("auxin.dev/injected") == "false"
          and "limits.cpu < 1" in anns(d).get("auxin.dev/skip-reason", ""),
          anns(d).get("auxin.dev/skip-reason", "")[:90])
    check("case-d", "R10 refusal injects nothing", not initc(d))

    # ---------------------------------------------------------------- case-e
    e = pods["case-e-not-opted-in"]
    check("case-e", ">>> R4: a pod without the opt-in label is COMPLETELY untouched",
          not anns(e) and not initc(e)
          and not (e["spec"].get("volumes") or [])
          and not (container(e).get("env") or [])
          and not (container(e).get("volumeMounts") or []),
          "annotations=%r" % anns(e))

    # ---------------------------------------------------------------- case-f
    f = pods["case-f-no-cpu-limit"]
    check("case-f", "R10 documented behaviour: NO cpu limit => no CFS quota => INJECT",
          anns(f).get("auxin.dev/injected") == "true"
          and "auxin.dev/skip-reason" not in anns(f)
          and len(initc(f)) == 1,
          anns(f).get("auxin.dev/skip-reason", "")[:90])

    # ---------------------------------------------------------------- case-g
    g = pods["case-g-already-injected"]; cg = container(g)
    jtog = env_of(cg).get("JAVA_TOOL_OPTIONS", {}).get("value", "")
    check("case-g", "R6: still exactly one auxin-agent-init", len(initc(g)) == 1)
    check("case-g", "R6: still exactly one auxin-agent volumeMount",
          sum(1 for m in (cg.get("volumeMounts") or []) if m["name"] == "auxin-agent") == 1)
    check("case-g", "R6: still exactly one JAVA_TOOL_OPTIONS entry",
          sum(1 for x in (cg.get("env") or []) if x["name"] == "JAVA_TOOL_OPTIONS") == 1)
    check("case-g", ">>> R6: -javaagent: was NOT duplicated", jtog.count("-javaagent:") == 1,
          "got %r" % jtog[:80])
    check("case-g", "R6: the app's own -Xmx512m still survives re-admission",
          jtog.startswith("-Xmx512m"))

    # ---------------------------------------------------------------- case-h
    h = pods["case-h-opted-out"]
    check("case-h", "R4: auxin.dev/inject-optout=true beats the opt-in label",
          not initc(h) and "auxin.dev/injected" not in anns(h),
          "annotations=%r" % anns(h))

    # ---------------------------------------------------------------- case-i
    i = pods["case-i-narrow-startup-probe"]; ci = container(i)
    spi = ci.get("startupProbe") or {}
    check("case-i", ">>> R9: the app's own startupProbe HANDLER is preserved (/startup)",
          (spi.get("httpGet") or {}).get("path") == "/startup", "got %r" % spi.get("httpGet"))
    check("case-i", "R9: narrow startupProbe widened to the floor (2->10, 3->30, 1->5)",
          spi.get("periodSeconds") == 10 and spi.get("failureThreshold") == 30
          and spi.get("timeoutSeconds") == 5, "got %r" % spi)

    # ------------------------------------------------------------ case-j / -k
    j = container(pods["case-j-guaranteed-qos"])["resources"]
    check("case-j", "R9 QoS: requests.cpu absent -> set to limits/2 (Guaranteed -> Burstable)",
          str(j.get("requests", {}).get("cpu")) == "1" and str(j["limits"]["cpu"]) == "2",
          "got %r" % j)
    k = container(pods["case-k-tight-limits"])["resources"]
    check("case-k", "R9 QoS: limits < 2 x requests -> limits raised to 2 x requests",
          str(k["limits"]["cpu"]) == "2" and str(k["requests"]["cpu"]) == "1", "got %r" % k)

    # ---------------------------------------------------------------- case-l
    l = pods["case-l-no-scope"]
    check("case-l", "default-deny scope: no include-packages annotation -> skipped + told why",
          anns(l).get("auxin.dev/injected") == "false"
          and "include-packages" in anns(l).get("auxin.dev/skip-reason", ""),
          anns(l).get("auxin.dev/skip-reason", "")[:90])

    # ============================================== 3. R10 warn-mode escape hatch
    warn_vals = os.path.join(tmp, "values-warn.yaml")
    open(warn_vals, "w").write(
        open(VALUES).read().replace('axcfg.data.cpuGuardMode: "refuse"',
                                    'axcfg.data.cpuGuardMode: "warn"'))
    wdir = os.path.join(tmp, "warn")
    rw = run(INJECT, PODS, warn_vals, outdir=wdir)
    check("warn", "cpuGuardMode=warn: still error: 0", rw["error"] == 0, "error: %d" % rw["error"])
    dw = load_mutated(wdir, "case-d-sub-one-cpu")
    check("warn", "cpuGuardMode=warn: the sub-1-CPU pod IS injected",
          bool(dw) and anns(dw).get("auxin.dev/injected") == "true"
          and len(initc(dw)) == 1,
          anns(dw or {}).get("auxin.dev/skip-reason", "")[:70])

    # ==================================================== 4. rollout + events
    rdir = os.path.join(tmp, "rollout")
    rr = run(ROLLOUT, DEPLOYS, outdir=rdir)
    check("rollout", "ax-rollout-policy: error: 0 and it actually loaded", rr["error"] == 0 and rr["rules"] > 0,
          "error: %d / rules: %d" % (rr["error"], rr["rules"]))
    da = load_mutated(rdir, "dep-a-optin")
    db = load_mutated(rdir, "dep-b-already-generous")
    dc = load_mutated(rdir, "dep-c-not-opted-in")
    check("rollout", "R9: opted-in Deployment gets progressDeadlineSeconds 1200",
          bool(da) and da["spec"].get("progressDeadlineSeconds") == 1200,
          "got %r" % (da or {}).get("spec", {}).get("progressDeadlineSeconds"))
    check("rollout", "R9: a larger existing value is NEVER lowered (1800 stays 1800)",
          bool(db) and db["spec"].get("progressDeadlineSeconds") == 1800,
          "got %r" % (db or {}).get("spec", {}).get("progressDeadlineSeconds"))
    check("rollout", "R4: a Deployment that did not opt in is untouched",
          bool(dc) and "progressDeadlineSeconds" not in dc["spec"])

    re_ = run(EVENTS, PODS, VALUES)
    check("events", "ax-events-policy: error: 0 and it actually loaded", re_["error"] == 0 and re_["rules"] > 0,
          "error: %d / rules: %d" % (re_["error"], re_["rules"]))
    check("events", "ax-events-policy: a Warning Event is generated for each skip case "
                    "(valueFrom, cpu guard, no scope)",
          re_["pass"] == 3, "pass: %d (expected 3)" % re_["pass"])

except Bail:
    pass
finally:
    shutil.rmtree(tmp, ignore_errors=True)

# ------------------------------------------------------------------- report
titles = {
    "LOAD":    "LOAD        kyverno actually accepts and loads each policy file",
    "RUN":     "RUN         every rule evaluates with error: 0",
    "case-a":  "case-a      JTO absent -> SET with leading space; probes; jar delivery",
    "case-b":  "case-b      JTO literal -> APPEND (the SkyWalking regression)",
    "case-c":  "case-c      JTO valueFrom -> whole pod skipped, reason recorded",
    "case-d":  "case-d      R10 CPU guard fires at 500m",
    "case-e":  "case-e      no opt-in label -> untouched",
    "case-f":  "case-f      no CPU limit at all -> injected (documented)",
    "case-g":  "case-g      already injected -> idempotent no-op (R6)",
    "case-h":  "case-h      explicit opt-out wins (R4)",
    "case-i":  "case-i      narrow startupProbe widened, handler preserved (R9)",
    "case-j":  "case-j      Guaranteed QoS broken into Burstable (R9)",
    "case-k":  "case-k      limits raised to 2 x requests (R9)",
    "case-l":  "case-l      no include-packages -> default-deny skip",
    "warn":    "warn        cpuGuardMode=warn escape hatch",
    "rollout": "rollout     progressDeadlineSeconds on instrumented Deployments",
    "events":  "events      Warning Events for every skip case",
}
order = list(titles.keys())
failed = 0
last = None
for grp, label, ok, detail in sorted(results, key=lambda r: order.index(r[0]) if r[0] in order else 99):
    if grp != last:
        print("\n" + titles.get(grp, grp)); print("-" * 78); last = grp
    if ok:
        print("  PASS  %s" % label)
    else:
        failed += 1
        print("  FAIL  %s" % label)
        if detail:
            print("        -> %s" % detail)
print("\n" + "-" * 78)
print("LIVE: %d checks, %d passed, %d failed" % (len(results), len(results) - failed, failed))
if not results:
    print("LIVE: NO CHECKS RAN — treat that as a failure, not a pass.")
    sys.exit(1)
sys.exit(1 if failed else 0)
LIVEEOF
  live_rc=$?
fi

# =============================================================================
# PHASE 2 — STATIC: requirement structure
# =============================================================================
echo
echo "=============================================================================="
echo "PHASE 2  STATIC — requirement structure (cross-check only, proves nothing alone)"
echo "=============================================================================="

if [ -z "$PY" ]; then
  echo "  SKIPPED: no python3 with PyYAML (python3 -m pip install pyyaml)"
  static_rc=3
else
echo "yaml parser: $PY ($("$PY" -c 'import yaml;print("PyYAML "+yaml.__version__)'))"

VERBOSE="$VERBOSE" "$PY" - "$ROOT" <<'PYEOF'
import os, re, sys
import yaml

ROOT = sys.argv[1]
FILES = os.path.join(ROOT, "helm", "auxin-inject", "files")

results = []
def check(req, label, ok, detail=""):
    results.append((req, label, bool(ok), detail))
    return bool(ok)

# ---------------------------------------------------------------- YAML loading
class DupKeyLoader(yaml.SafeLoader):
    """YAML silently accepts duplicate mapping keys and keeps the last one.
    In a k8s manifest that means a field you wrote is simply gone. Reject it."""
    pass

def _no_dups(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping", node.start_mark,
                "found duplicate key %r" % (key,), key_node.start_mark)
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping

DupKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _no_dups)

def load_all(path):
    with open(path) as fh:
        return [d for d in yaml.load_all(fh, Loader=DupKeyLoader) if d]

# ---------------------------------------------------------------- walk helpers
def walk(node):
    yield node
    if isinstance(node, dict):
        for v in node.values():
            yield from walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk(v)

def keys_anywhere(node, key):
    for n in walk(node):
        if isinstance(n, dict) and key in n:
            yield n[key]

def strings(node):
    for n in walk(node):
        if isinstance(n, str):
            yield n

def has_precondition(rule, key_substr, operator=None, value=None):
    """True if the rule (or any of its foreach entries) carries a matching
    precondition condition."""
    for pc in keys_anywhere(rule, "preconditions"):
        if not isinstance(pc, dict):
            continue
        for bucket in ("all", "any"):
            for cond in pc.get(bucket) or []:
                if not isinstance(cond, dict):
                    continue
                k = str(cond.get("key", ""))
                if key_substr not in k:
                    continue
                if operator is not None and cond.get("operator") != operator:
                    continue
                if value is not None and cond.get("value") != value:
                    continue
                return True
    return False

# ---------------------------------------------------------------- parse phase
required_files = {
    "inject":   os.path.join(FILES, "ax-inject-policy.yaml"),
    "events":   os.path.join(FILES, "ax-events-policy.yaml"),
    "rollout":  os.path.join(FILES, "ax-rollout-policy.yaml"),
    "kyverno_values": os.path.join(ROOT, "kyverno", "kyverno-values.yaml"),
    "kyverno_rbac":   os.path.join(ROOT, "kyverno", "kyverno-rbac-events.yaml"),
    "cfg_example":    os.path.join(ROOT, "kyverno", "auxin-inject-config.example.yaml"),
    "webhook_cm":     os.path.join(ROOT, "kyverno", "kyverno-configmap-webhooks.yaml"),
    "example_deploy": os.path.join(ROOT, "examples", "demo-deployment.yaml"),
    "test_pods":      os.path.join(ROOT, "test", "pods.yaml"),
    "test_deploys":   os.path.join(ROOT, "test", "deployments.yaml"),
    "test_values":    os.path.join(ROOT, "test", "values.yaml"),
}

docs = {}
parse_failed = False
for name, path in required_files.items():
    if not os.path.exists(path):
        check("P", "file exists: %s" % os.path.relpath(path, ROOT), False, "missing")
        parse_failed = True
        continue
    try:
        docs[name] = load_all(path)
        check("P", "parses, no duplicate keys: %s" % os.path.relpath(path, ROOT), True)
    except Exception as e:
        check("P", "parses: %s" % os.path.relpath(path, ROOT), False, str(e).replace("\n", " ")[:200])
        parse_failed = True

if parse_failed:
    for req, label, ok, detail in results:
        print("%-4s %-62s %s" % (req, label[:62], "PASS" if ok else "FAIL " + detail))
    print("\nABORTED: a manifest is missing or does not parse.")
    sys.exit(2)

inject   = docs["inject"][0]
events   = docs["events"][0]
rollout  = docs["rollout"][0]
policies = {"ax-inject-policy": inject, "ax-events-policy": events, "ax-rollout-policy": rollout}

inject_rules  = inject["spec"]["rules"]
rules_by_name = {r["name"]: r for r in inject_rules}
# The rules that actually perform injection all share the same precondition anchor.
inject_mutating = [r for r in inject_rules if not r["name"].startswith(("gt-12", "gt-13", "gt-14"))]

# ============================================================ R1 failurePolicy
for pname, pol in policies.items():
    check("R1", "%s: failurePolicy == Ignore (not Kyverno's Fail default)" % pname,
          pol["spec"].get("failurePolicy") == "Ignore",
          "got %r" % pol["spec"].get("failurePolicy"))

# ================================================ R2 never returns allowed:false
for pname, pol in policies.items():
    validates = [v for v in keys_anywhere(pol, "validate")]
    check("R2", "%s: contains no validate rule" % pname, not validates,
          "%d validate block(s)" % len(validates))
    denies = [v for v in keys_anywhere(pol, "deny")]
    check("R2", "%s: contains no deny block" % pname, not denies,
          "%d deny block(s)" % len(denies))
    enforce = [v for v in keys_anywhere(pol, "failureAction") if v == "Enforce"]
    enforce += [v for v in keys_anywhere(pol, "validationFailureAction") if v == "Enforce"]
    check("R2", "%s: no Enforce action anywhere" % pname, not enforce)
    kinds = set()
    for r in pol["spec"]["rules"]:
        kinds |= {k for k in ("mutate", "generate", "validate", "verifyImages") if k in r}
    check("R2", "%s: rules are mutate/generate only -> no code path denies" % pname,
          kinds <= {"mutate", "generate"}, "rule kinds: %s" % sorted(kinds))

check("R2", "warnings are async generate rules, not Audit-mode validate",
      all("generate" in r for r in events["spec"]["rules"]))
check("R2", "inject policy is mutate-only",
      all("mutate" in r for r in inject_rules))

# ======================================================== R3 namespace exclusion
# NOTE: this used to read spec.webhookConfiguration.namespaceSelector. That field does
# not exist in Kyverno's ClusterPolicy schema — the API server prunes it and the kyverno
# CLI refuses the whole file. Enforcement is engine-side `exclude` + the cluster-wide
# webhooks ConfigMap. See the long comment at the top of ax-inject-policy.yaml.
REQUIRED_NS = {"kube-system", "kube-public", "kube-node-lease", "kyverno", "auxin-system"}
for pname, pol in policies.items():
    for w in keys_anywhere(pol, "webhookConfiguration"):
        bad = set(w or {}) & {"namespaceSelector", "objectSelector"}
        check("R3", "%s: no phantom webhookConfiguration.%s (not a Kyverno field; it makes "
                    "kyverno silently drop the file)" % (pname, "/".join(sorted(bad)) or "*"),
              not bad, "found %s" % sorted(bad))

for pname in ("ax-inject-policy", "ax-rollout-policy"):
    for r in policies[pname]["spec"]["rules"]:
        ns = set()
        for block in (r.get("exclude") or {}).get("any") or []:
            ns |= set(((block.get("resources") or {}).get("namespaces")) or [])
        if not check("R3", "%s/%s: engine-side exclude lists all 5 protected namespaces"
                     % (pname, r["name"]), REQUIRED_NS <= ns,
                     "missing %s" % sorted(REQUIRED_NS - ns)):
            break

wcm = docs["webhook_cm"][0]
check("R3", "cluster-wide webhook namespace exclusion ships in kyverno-configmap-webhooks.yaml",
      any(n in str(wcm) for n in REQUIRED_NS))

# ================================================== R4 opt-in / opt-out by label
lbl_ok, out_ok, false_ok = [], [], []
for r in inject_rules:
    m = []
    for block in (r.get("match") or {}).get("any") or []:
        sel = ((block.get("resources") or {}).get("selector") or {}).get("matchLabels") or {}
        m.append(sel.get("auxin.dev/inject") == "true")
    lbl_ok.append(bool(m) and all(m))
    ex = [((b.get("resources") or {}).get("selector") or {}).get("matchLabels") or {}
          for b in ((r.get("exclude") or {}).get("any") or [])]
    out_ok.append(any(s.get("auxin.dev/inject-optout") == "true" for s in ex))
    false_ok.append(any(s.get("auxin.dev/inject") == "false" for s in ex))
check("R4", "every rule's match requires the opt-in POD LABEL auxin.dev/inject=true",
      all(lbl_ok), "%d/%d rules" % (sum(lbl_ok), len(lbl_ok)))
check("R4", "every rule excludes auxin.dev/inject-optout=true",
      all(out_ok), "%d/%d rules" % (sum(out_ok), len(out_ok)))
check("R4", "every rule excludes auxin.dev/inject=false",
      all(false_ok), "%d/%d rules" % (sum(false_ok), len(false_ok)))
check("R4", "opt-in is a label selector, not an annotation read inside the webhook",
      not any("annotations" in s and "auxin.dev/inject\"" in s
              for s in strings(inject["spec"]["rules"][0].get("match") or {})))
# the rollout policy's opt-in lives on the POD TEMPLATE, so it is a precondition
check("R4", "ax-rollout-policy keys off the POD TEMPLATE label, not the Deployment's own",
      has_precondition(rollout["spec"]["rules"][0],
                       'request.object.spec.template.metadata.labels', "Equals", "true"))

# ================================================ R5 timeout + Kyverno HA posture
for pname, pol in policies.items():
    check("R5", "%s: webhookTimeoutSeconds == 2" % pname,
          pol["spec"].get("webhookTimeoutSeconds") == 2,
          "got %r" % pol["spec"].get("webhookTimeoutSeconds"))

kv = docs["kyverno_values"][0]
ac = kv.get("admissionController", {})
check("R5", "kyverno-values.yaml: admissionController.replicas >= 2",
      isinstance(ac.get("replicas"), int) and ac["replicas"] >= 2, "got %r" % ac.get("replicas"))
pdb = ac.get("podDisruptionBudget") or {}
check("R5", "kyverno-values.yaml: PodDisruptionBudget enabled",
      pdb.get("enabled") is True)
check("R5", "kyverno-values.yaml: PDB minAvailable set and < replicas (satisfiable)",
      isinstance(pdb.get("minAvailable"), int) and pdb["minAvailable"] < ac.get("replicas", 0),
      "minAvailable=%r replicas=%r" % (pdb.get("minAvailable"), ac.get("replicas")))
paa = ac.get("podAntiAffinity") or {}
check("R5", "kyverno-values.yaml: pod anti-affinity configured",
      bool(paa) and (ac.get("antiAffinity") or {}).get("enabled") is True)

# ==================================================== R6 idempotency, both halves
# >>> The gtInitPresent guard must live on gt-01 and the three skip-annotation rules
#     ONLY. Kyverno re-binds request.object to the PATCHED resource between rules, so
#     gt-01 creating the initContainer makes gtInitPresent 1 for everything after it.
#     With the guard on the shared anchor, gt-02..gt-14 were all skipped and the pod got
#     an initContainer, an emptyDir and nothing else: no mount, no JAVA_TOOL_OPTIONS,
#     no probes. This check exists so that never comes back.
check("R6", ">>> gt-01 (and only gt-01) carries the pod-level gtInitPresent guard",
      has_precondition(rules_by_name["gt-01-agent-volume-and-init"], "gtInitPresent", "Equals", 0))
leaked = [r["name"] for r in inject_mutating
          if r["name"] != "gt-01-agent-volume-and-init"
          and has_precondition(r, "gtInitPresent")]
check("R6", ">>> no other injecting rule carries it (it would self-disable the policy)",
      not leaked, "leaked into %s" % leaked)
for n in ("gt-12-annotate-skip-valuefrom", "gt-13-annotate-skip-cpu-guard",
          "gt-14-annotate-skip-no-scope"):
    check("R6", "%s keeps the guard (never mark an injected pod as skipped)" % n,
          has_precondition(rules_by_name[n], "gtInitPresent", "Equals", 0))

append_rule = rules_by_name["gt-04-java-tool-options-append"]
check("R6", "append rule guards on the -javaagent: substring already in JAVA_TOOL_OPTIONS",
      has_precondition(append_rule, "-javaagent:/ax-agent/ax-agent.jar", "Equals", False))
# structural idempotency: strategic-merge list merge keys
init_patch = rules_by_name["gt-01-agent-volume-and-init"]["mutate"]["patchStrategicMerge"]["spec"]
check("R6", "initContainer/volume patches are strategic-merge keyed on name (re-apply = no-op)",
      init_patch["initContainers"][0]["name"] == "auxin-agent-init"
      and init_patch["volumes"][0]["name"] == "auxin-agent")

# ============================================ R7 JAVA_TOOL_OPTIONS, all three cases
set_rule = rules_by_name["gt-03-java-tool-options-set"]

def env_patch(rule):
    fe = rule["mutate"]["foreach"][0]
    conts = fe["patchStrategicMerge"]["spec"]["containers"]
    return {e["name"]: e for e in conts[0]["env"]}

set_env = env_patch(set_rule)
app_env = env_patch(append_rule)

jto_set = set_env.get("JAVA_TOOL_OPTIONS", {}).get("value", "")
check("R7", "absent -> SET with a LEADING SPACE",
      jto_set.startswith(" -javaagent:/ax-agent/ax-agent.jar"), "got %r" % jto_set[:40])
check("R7", "absent-case rule only fires when the env var is absent",
      has_precondition(set_rule, "JAVA_TOOL_OPTIONS", "Equals", 0))

jto_app = app_env.get("JAVA_TOOL_OPTIONS", {}).get("value", "")
check("R7", "present+literal -> APPEND (existing value is the prefix of the new value)",
      jto_app.startswith("{{ element.env[?name=='JAVA_TOOL_OPTIONS'].value | [0] }} "),
      "got %r" % jto_app[:60])
check("R7", "append rule only fires when the env var is present with a literal value",
      has_precondition(append_rule, "JAVA_TOOL_OPTIONS' && value != null", "GreaterThan", 0))
check("R7", "append writes ONE env entry named JAVA_TOOL_OPTIONS (no duplicate entry)",
      sum(1 for e in append_rule["mutate"]["foreach"][0]["patchStrategicMerge"]["spec"]
          ["containers"][0]["env"] if e["name"] == "JAVA_TOOL_OPTIONS") == 1)
check("R7", "set and append preconditions are mutually exclusive (== 0 vs > 0)",
      has_precondition(set_rule, "JAVA_TOOL_OPTIONS", "Equals", 0)
      and has_precondition(append_rule, "JAVA_TOOL_OPTIONS", "GreaterThan", 0))

for r in inject_mutating:
    if not check("R7", "rule %s: skips the whole pod when JTO uses valueFrom" % r["name"],
                 has_precondition(r, "gtJtoValueFrom", "Equals", 0)):
        break
skip_vf = rules_by_name["gt-12-annotate-skip-valuefrom"]
reason = skip_vf["mutate"]["patchStrategicMerge"]["metadata"]["annotations"].get("auxin.dev/skip-reason", "")
check("R7", "valueFrom skip is recorded on the pod as a durable annotation",
      "valueFrom" in reason)
ev_reasons = " ".join(str(s) for s in strings(events))
check("R7", "valueFrom skip also emits a Warning Event",
      "valueFrom" in ev_reasons and "Warning" in ev_reasons)

# ================================================ R8 correct env var, always
for pname, pol in policies.items():
    env_names = set()
    for n in walk(pol):
        if isinstance(n, dict) and "name" in n and ("value" in n or "valueFrom" in n):
            env_names.add(str(n["name"]))
    bad = env_names & {"_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}
    check("R8", "%s: never sets _JAVA_OPTIONS or JDK_JAVA_OPTIONS" % pname, not bad, str(bad))
check("R8", "JAVA_TOOL_OPTIONS is the variable actually used",
      any("JAVA_TOOL_OPTIONS" in s for s in strings(inject)))

# ==================================================== R9 probes / QoS / rollout
# >>> RULE ORDER. The floor rule precedes the two create-from-handler rules so the two
#     paths are mutually exclusive. This WAS load-bearing: with the create rules first,
#     the floor rule re-read a merge()-built startupProbe and max() rejected the numbers
#     in it ("Invalid type for: [30 30]") — error: 1. The to_number(to_string(..))
#     coercion now fixes that independently, so this check is defence in depth, not the
#     thing standing between you and a broken policy. The coercion check below is.
names = [r["name"] for r in inject_rules]
check("R9", "gt-05-startup-probe-floor runs BEFORE the create-from-handler rules",
      names.index("gt-05-startup-probe-floor")
      < names.index("gt-06-startup-probe-from-liveness")
      < names.index("gt-07-startup-probe-from-readiness"),
      "order: %s" % names[4:7])

sp_live = rules_by_name["gt-06-startup-probe-from-liveness"]["mutate"]["foreach"][0] \
          ["patchStrategicMerge"]["spec"]["containers"][0]["startupProbe"]
for field, floor in (("periodSeconds", "10"), ("failureThreshold", "30"), ("timeoutSeconds", "5")):
    check("R9", "derived startupProbe sets %s = %s" % (field, floor),
          ("%s: `%s`" % (field, floor)) in sp_live, "in %r" % sp_live[:140])
check("R9", "derived startupProbe copies the container's own liveness handler",
      "merge(element.livenessProbe" in sp_live)
check("R9", "multi-select hash keys are UNQUOTED (quoted keys are a JMESPath SyntaxError)",
      not re.search(r"merge\([^)]*\{\s*'", sp_live), "got %r" % sp_live[:140])

sp_floor = rules_by_name["gt-05-startup-probe-floor"]["mutate"]["foreach"][0] \
           ["patchStrategicMerge"]["spec"]["containers"][0]["startupProbe"]
check("R9", "existing startupProbe: periodSeconds raised to >= 10 via max()",
      "element.startupProbe.periodSeconds" in sp_floor["periodSeconds"]
      and "`10`]" in sp_floor["periodSeconds"])
check("R9", "existing startupProbe: failureThreshold raised to >= 30 via max()",
      "`30`]" in sp_floor["failureThreshold"])
check("R9", "existing startupProbe: timeoutSeconds raised to >= 5 via max()",
      "`5`]" in sp_floor["timeoutSeconds"])

lp = rules_by_name["gt-08-liveness-probe-floor"]["mutate"]["foreach"][0] \
     ["patchStrategicMerge"]["spec"]["containers"][0]["livenessProbe"]
check("R9", ">>> livenessProbe.timeoutSeconds >= 5 (the 1s default is the CrashLoop vector)",
      "`5`]" in lp["timeoutSeconds"] and "max(" in lp["timeoutSeconds"])
check("R9", ">>> livenessProbe.failureThreshold >= 5",
      "`5`]" in lp["failureThreshold"] and "max(" in lp["failureThreshold"])
check("R9", "probe floors use max() so they never narrow an app's own wider setting",
      all("max(" in v for v in (lp["timeoutSeconds"], lp["failureThreshold"],
                                sp_floor["periodSeconds"], sp_floor["failureThreshold"])))
# go-jmespath's max() type-asserts every element to float64; an integer straight off the
# resource fails it. Every max() over a resource-sourced value must be string-coerced.
maxed = [s for s in strings(inject) if "max([" in s] + \
        [s for s in strings(rollout) if "max([" in s]
check("R9", "every max() coerces its resource-sourced operand with to_number(to_string(..))",
      all("to_number(to_string(" in s for s in maxed),
      "uncoerced: %s" % [s[:60] for s in maxed if "to_number(to_string(" not in s])

qos_req = rules_by_name["gt-09-burstable-qos-requests"]["mutate"]["foreach"][0]
qos_lim = rules_by_name["gt-10-burstable-qos-limits"]["mutate"]["foreach"][0]
req_cpu = qos_req["patchStrategicMerge"]["spec"]["containers"][0]["resources"]["requests"]["cpu"]
lim_cpu = qos_lim["patchStrategicMerge"]["spec"]["containers"][0]["resources"]["limits"]["cpu"]
check("R9", "burstable QoS: requests.cpu = limits.cpu / 2 when requests is absent",
      "divide(to_string(element.resources.limits.cpu)" in req_cpu and "`2`" in req_cpu)
check("R9", "burstable QoS: limits.cpu raised to 2 x requests.cpu",
      "multiply(to_string(element.resources.requests.cpu)" in lim_cpu and "`2`" in lim_cpu)
check("R9", "burstable QoS rule only fires when limits < 2 x requests",
      has_precondition(qos_lim, "divide(to_string(element.resources.limits.cpu", "LessThan"))

pd = rollout["spec"]["rules"][0]["mutate"]["patchStrategicMerge"]["spec"]["progressDeadlineSeconds"]
check("R9", "progressDeadlineSeconds raised (>= 1200) on instrumented Deployments",
      "`1200`" in pd and "max(" in pd, "got %r" % pd)
check("R9", "progressDeadlineSeconds lives on Deployment, so it is a separate policy",
      rollout["spec"]["rules"][0]["match"]["any"][0]["resources"]["kinds"] == ["Deployment"])

# ============================================================= R10 CPU < 1 guard
cpu_guard_rules = [r for r in inject_mutating
                   if has_precondition(r, "gtMinCpuLimit", "GreaterThanOrEquals", 1)]
check("R10", "every injecting rule requires min(limits.cpu) >= 1 ...",
      len(cpu_guard_rules) == len(inject_mutating),
      "%d/%d rules guarded" % (len(cpu_guard_rules), len(inject_mutating)))
for r in inject_mutating:
    if not check("R10", "rule %s: ... or no CPU limit at all (no CFS quota => no throttling)"
                 % r["name"], has_precondition(r, "gtCpuLimitCount", "Equals", 0)):
        break
for r in inject_mutating:
    if not check("R10", "rule %s: ... with an explicit warn-instead-of-refuse escape hatch"
                 % r["name"], has_precondition(r, "axcfg.data.cpuGuardMode", "Equals", "warn")):
        break
# gt-01 carries its own copy of the shared block (it needs the extra gtInitPresent guard).
# Assert the copy has not drifted: identical conditions, plus exactly that one.
def cond_set(rule):
    pc = rule.get("preconditions") or {}
    return {(b, str(c.get("key")), c.get("operator"), repr(c.get("value")))
            for b in ("all", "any") for c in (pc.get(b) or [])}
anchor = cond_set(rules_by_name["gt-02-mount-agent-volume"])
first  = cond_set(rules_by_name["gt-01-agent-volume-and-init"])
extra  = first - anchor
check("R10", "gt-01's inline precondition copy has not drifted from the shared anchor",
      anchor <= first and len(extra) == 1 and "gtInitPresent" in list(extra)[0][1],
      "anchor-only: %s | gt-01-only: %s" % (sorted(anchor - first), sorted(extra)))
# >>> parse_quantity() IS NOT A KYVERNO FUNCTION. `kyverno jp function` lists every one
#     it adds, and that is not among them. An unknown function fails the whole context
#     variable, Kyverno falls back to `default: 0`, and 0 < 1 refused EVERY pod.
all_jmes = " ".join(str(s) for s in strings(inject)) + " " + \
           " ".join(str(s) for s in strings(events))
check("R10", ">>> no parse_quantity() anywhere (unknown function -> guard refuses every pod)",
      "parse_quantity" not in all_jmes)
cpu_ctx = [c for c in inject_rules[0]["context"] if c.get("name") == "gtMinCpuLimit"]
jp = cpu_ctx[0]["variable"]["jmesPath"] if cpu_ctx else ""
check("R10", "min(limits.cpu) is computed NUMERICALLY, not lexicographically",
      "divide(to_string(" in jp, "got %r" % jp[:110])
cpu_skip = rules_by_name["gt-13-annotate-skip-cpu-guard"]
cpu_reason = cpu_skip["mutate"]["patchStrategicMerge"]["metadata"]["annotations"]["auxin.dev/skip-reason"]
check("R10", "refusal is recorded on the pod with a reason",
      "limits.cpu < 1" in cpu_reason)
check("R10", "refusal also warns loudly via a Warning Event",
      "AuxinCpuGuard" in ev_reasons)

# ======================================== R11 initContainer + emptyDir delivery
vol = init_patch["volumes"][0]
ic = init_patch["initContainers"][0]
check("R11", "shared volume is an emptyDir", "emptyDir" in vol)
check("R11", "emptyDir has a sizeLimit", bool(vol["emptyDir"].get("sizeLimit")))
check("R11", "initContainer copies the agent jar into the shared volume",
      any("ax-agent.jar" in str(c) for c in ic.get("command", [])))
check("R11", "initContainer mounts the shared volume",
      any(m["name"] == vol["name"] and m["mountPath"] == "/ax-agent"
          for m in ic["volumeMounts"]))
mount_rule = rules_by_name["gt-02-mount-agent-volume"]["mutate"]["foreach"][0]
mounts = mount_rule["patchStrategicMerge"]["spec"]["containers"][0]["volumeMounts"]
check("R11", "app containers mount the same volume read-only at /ax-agent",
      mounts[0]["name"] == vol["name"] and mounts[0]["mountPath"] == "/ax-agent"
      and mounts[0].get("readOnly") is True)
check("R11", "mount rule is idempotent (skips containers that already have the mount)",
      has_precondition(rules_by_name["gt-02-mount-agent-volume"], "volumeMounts[?name==", "Equals", 0))
check("R11", "initContainer runs non-root, read-only rootfs, no capabilities",
      ic["securityContext"]["runAsNonRoot"] is True
      and ic["securityContext"]["readOnlyRootFilesystem"] is True
      and ic["securityContext"]["capabilities"]["drop"] == ["ALL"])
check("R11", "env is only ever patched onto spec.containers, never initContainers",
      all(fe["list"] == "request.object.spec.containers"
          for r in inject_rules for fe in (r["mutate"].get("foreach") or [])))

# ============================================== chart / policy consistency checks
cm_path = os.path.join(ROOT, "helm", "auxin-inject", "templates", "configmap.yaml")
pol_path = os.path.join(ROOT, "helm", "auxin-inject", "templates", "policies.yaml")
values_path = os.path.join(ROOT, "helm", "auxin-inject", "values.yaml")
chart_path = os.path.join(ROOT, "helm", "auxin-inject", "Chart.yaml")
for p in (cm_path, pol_path, values_path, chart_path):
    check("C", "chart file exists: %s" % os.path.relpath(p, ROOT), os.path.exists(p))

values = load_all(values_path)[0]
ctx_cm = None
for c in inject_rules[0]["context"]:
    if "configMap" in c:
        ctx_cm = c["configMap"]
check("C", "policy reads its config from a ConfigMap (no Helm templating inside policies)",
      ctx_cm is not None)
check("C", "chart values.config.namespace matches the namespace the policy looks up",
      values["config"]["namespace"] == ctx_cm["namespace"],
      "%s != %s" % (values["config"]["namespace"], ctx_cm["namespace"]))
check("C", "chart values.config.name matches the ConfigMap name the policy looks up",
      values["config"]["name"] == ctx_cm["name"])
cfg_example = docs["cfg_example"][0]
check("C", "raw-apply ConfigMap example has the same name/namespace",
      cfg_example["metadata"]["name"] == ctx_cm["name"]
      and cfg_example["metadata"]["namespace"] == ctx_cm["namespace"])
cfg_keys_used = set(re.findall(r"axcfg\.data\.(\w+)", " ".join(strings(inject))))
check("C", "every axcfg key the policy reads exists in the example ConfigMap: %s"
      % sorted(cfg_keys_used),
      cfg_keys_used <= set(cfg_example["data"].keys()),
      "missing %s" % sorted(cfg_keys_used - set(cfg_example["data"].keys())))
check("C", "every axcfg key exists in chart values",
      cfg_keys_used <= set(values["config"].keys()),
      "missing %s" % sorted(cfg_keys_used - set(values["config"].keys())))
# >>> the test harness stands in for that ConfigMap. A key the policy reads but the
#     harness does not supply means the live phase silently tested an unresolved variable.
tv = docs["test_values"][0].get("globalValues") or {}
harness_keys = {k.split("axcfg.data.", 1)[1] for k in tv if k.startswith("axcfg.data.")}
check("C", "test/values.yaml supplies every axcfg key the policy reads",
      cfg_keys_used <= harness_keys, "missing %s" % sorted(cfg_keys_used - harness_keys))

pol_tpl = open(pol_path).read()
check("C", "chart ships policies via .Files.Get (raw), never through tpl",
      ".Files.Get" in pol_tpl and "tpl " not in pol_tpl)
policy_code = "\n".join(l for l in open(required_files["inject"]).read().splitlines()
                        if not l.lstrip().startswith("#"))
check("C", "policy files contain no Helm/Go template syntax (comments excluded)",
      not re.search(r"\{\{-|\{\{\s*\.(Values|Release|Chart)", policy_code))
check("C", "cpuGuardMode default is the refusing one",
      values["config"]["cpuGuardMode"] == "refuse")

# the example workload is the fixture the docs describe
ex = docs["example_deploy"]
check("C", "example deployment opts in via the pod-template label",
      ex[0]["spec"]["template"]["metadata"]["labels"].get("auxin.dev/inject") == "true")
check("C", "example deployment carries a pre-existing JAVA_TOOL_OPTIONS (append regression)",
      any(e["name"] == "JAVA_TOOL_OPTIONS"
          for e in ex[0]["spec"]["template"]["spec"]["containers"][0]["env"]))
check("C", "example includes a sub-1-CPU counter-example for the R10 guard",
      any(d["spec"]["template"]["spec"]["containers"][0]["resources"]["limits"]["cpu"] == "500m"
          for d in ex if d["kind"] == "Deployment"))

# the live phase is only as good as its fixtures
fixtures = {d["metadata"]["name"] for d in docs["test_pods"]}
REQUIRED_FIXTURES = {"case-a-no-jto", "case-b-literal-jto", "case-c-valuefrom-jto",
                     "case-d-sub-one-cpu", "case-e-not-opted-in", "case-f-no-cpu-limit",
                     "case-g-already-injected", "case-h-opted-out",
                     "case-i-narrow-startup-probe", "case-j-guaranteed-qos",
                     "case-k-tight-limits", "case-l-no-scope"}
check("C", "test/pods.yaml still carries every fixture the live phase asserts on",
      REQUIRED_FIXTURES <= fixtures, "missing %s" % sorted(REQUIRED_FIXTURES - fixtures))

# ---------------------------------------------------------------- report
order = {"P": 0, "R1": 1, "R2": 2, "R3": 3, "R4": 4, "R5": 5, "R6": 6, "R7": 7,
         "R8": 8, "R9": 9, "R10": 10, "R11": 11, "C": 12}
titles = {
    "P":   "PARSE       every shipped manifest loads, no duplicate keys",
    "R1":  "R1          failurePolicy: Ignore",
    "R2":  "R2          the policy can never return allowed:false",
    "R3":  "R3          system + kyverno + collector namespaces excluded",
    "R4":  "R4          opt-in by pod label, explicit opt-out",
    "R5":  "R5          webhookTimeoutSeconds 2, Kyverno >=2 replicas + PDB + anti-affinity",
    "R6":  "R6          idempotency on BOTH initContainer and -javaagent: substring",
    "R7":  "R7          JAVA_TOOL_OPTIONS: set-with-space / append / skip-on-valueFrom",
    "R8":  "R8          JAVA_TOOL_OPTIONS only, never _JAVA_OPTIONS or JDK_JAVA_OPTIONS",
    "R9":  "R9          startupProbe, liveness floors, burstable QoS, progressDeadline",
    "R10": "R10         refuse (or warn) when limits.cpu < 1",
    "R11": "R11         initContainer + emptyDir delivers the agent jar",
    "C":   "CONSISTENCY chart, policies and test harness agree",
}
failed = 0
last = None
for req, label, ok, detail in sorted(results, key=lambda r: order.get(r[0], 99)):
    if req != last:
        print("\n" + titles.get(req, req))
        print("-" * 78)
        last = req
    if ok:
        print("  PASS  %s" % label)
    else:
        failed += 1
        print("  FAIL  %s   -> %s" % (label, detail))

total = len(results)
print("\n" + "-" * 78)
print("STATIC: %d checks, %d passed, %d failed" % (total, total - failed, failed))
sys.exit(1 if failed else 0)
PYEOF
  static_rc=$?
fi

# =============================================================================
# summary
# =============================================================================
echo
echo "=============================================================================="
case "$live_rc" in
  0) echo "LIVE   PASS   (kyverno apply: policies load, error: 0, mutations asserted)";;
  3) echo "LIVE   NOT RUN  <-- the kyverno CLI was missing. Nothing is proven.";;
  *) echo "LIVE   FAIL";;
esac
case "$static_rc" in
  0) echo "STATIC PASS";;
  3) echo "STATIC NOT RUN  <-- PyYAML missing";;
  2) echo "STATIC ABORTED  <-- a manifest is missing or unparseable";;
  *) echo "STATIC FAIL";;
esac
echo "=============================================================================="

cat <<'NOTE'

STILL NOT VERIFIED BY THIS SCRIPT — `kyverno apply` is an offline engine, not a cluster:
  * that the API server admits these CRs (CRD schema validation is server-side; the CLI
    uses a bundled copy of the schema, which is close but not identical)
  * that the generated MutatingWebhookConfiguration scopes/registers as expected
  * that the webhook actually admits pods when Kyverno is down (failurePolicy: Ignore)
  * that the Warning Events are really created (needs the background controller + RBAC)
  * that the agent image exists or that the jar lands in the emptyDir at runtime
  * anything about performance, or about Kyverno's HA behaviour under load
A kind cluster is still the next step for all of the above.
NOTE

if [ "$live_rc" = "0" ] && [ "$static_rc" = "0" ]; then
  echo "RESULT: PASS"
  exit 0
fi
if [ "$live_rc" = "3" ] || [ "$static_rc" = "3" ]; then
  # a phase that did not run is not a pass
  echo "RESULT: INCOMPLETE"
  exit 1
fi
echo "RESULT: FAIL"
exit 1
