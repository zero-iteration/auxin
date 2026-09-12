#!/usr/bin/env bash
# =============================================================================
# demo/verify-auxin.sh — prove the fixture still matches ground-truth.json
# =============================================================================
# A ground-truth file that is maintained by hand rots the first time someone adds a call
# site, and then every G5 assertion built on it is quietly wrong. So the claims are
# MEASURED, not asserted:
#
#   A. class loading   -> -Xlog:class+load=info from a real run
#   B. execution       -> the application's own counters, printed as JSON at exit
#   C. call sites      -> textual reference counts in the sources
#   D. bridges         -> javap on the compiled class files
#
# Usage: ./demo/verify-auxin.sh [seconds]
# Exit:  0 fixture matches ground-truth.json, 1 it does not, 2 setup problem.
# =============================================================================
set -uo pipefail

DEMO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECONDS_ARG="${1:-5}"

JAVA_BIN="java"
JAVAP_BIN="javap"
if [ -x "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java" ]; then
  JAVA_BIN="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java"
  JAVAP_BIN="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/javap"
fi

JAR="$DEMO/target/auxin-demo.jar"
if [ ! -f "$JAR" ]; then
  echo "[build] $JAR missing — running mvn -o package"
  ( cd "$DEMO" && mvn -o -q package ) || { echo "BUILD FAILED"; exit 2; }
fi

PY="python3"
command -v "$PY" >/dev/null 2>&1 || PY=/usr/bin/python3

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[run] $JAVA_BIN -Xlog:class+load -jar auxin-demo.jar --seconds=$SECONDS_ARG"
"$JAVA_BIN" -Xlog:class+load=info:file="$TMP/classload.log" \
  -jar "$JAR" --seconds="$SECONDS_ARG" --min-requests=600 --threads=4 > "$TMP/run.out" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  echo "APP FAILED (exit $rc):"; cat "$TMP/run.out"; exit 2
fi

"$JAVAP_BIN" -p -s "$DEMO/target/classes/io/auxin/demo/repo/OrderRepository.class" > "$TMP/javap-order.txt" 2>&1
"$JAVAP_BIN" -p -s "$DEMO/target/classes/io/auxin/demo/repo/CustomerRepository.class" > "$TMP/javap-customer.txt" 2>&1
"$JAVAP_BIN" -p -s "$DEMO/target/classes/io/auxin/demo/service/PricingService.class" > "$TMP/javap-pricing.txt" 2>&1

DEMO="$DEMO" TMP="$TMP" "$PY" - <<'PYEOF'
import json, os, re, sys

DEMO = os.environ["DEMO"]
TMP = os.environ["TMP"]
gt = json.load(open(os.path.join(DEMO, "ground-truth.json")))

ok = True
def check(section, label, cond, detail=""):
    global ok
    if cond:
        print("  PASS  %s" % label)
    else:
        ok = False
        print("  FAIL  %s  -> %s" % (label, detail))

# ---------------------------------------------------------------- A. class loading
loaded = set(re.findall(r"(io\.auxin\.demo\.[A-Za-z0-9_.$]+)",
                        open(os.path.join(TMP, "classload.log")).read()))
loaded_real = {c for c in loaded if "$$Lambda" not in c}

print("\nA. CLASS LOADING (measured with -Xlog:class+load=info)")
print("-" * 78)
for e in gt["neverLoadedClasses"]:
    check("A", "never loaded: %s" % e["class"], e["class"] not in loaded_real,
          "it WAS loaded — something now references it")
for e in gt["loadedButNeverInvokedClasses"]:
    check("A", "loaded but idle: %s is loaded" % e["class"], e["class"] in loaded_real,
          "not loaded — App.main no longer loads it")
missing_live = [c for c in gt["liveClasses"] if c not in loaded_real]
check("A", "all %d classes listed as live were loaded" % len(gt["liveClasses"]),
      not missing_live, "not loaded: %s" % missing_live)
gen = {c for c in loaded if "$$Lambda" in c}
check("A", "runtime-generated classes exist (the C9 exclusion has a subject): %d" % len(gen),
      len(gen) > 0)

# ---------------------------------------------------------------- B. execution
summary = None
for line in open(os.path.join(TMP, "run.out")):
    if line.startswith("[demo] summary "):
        summary = json.loads(line[len("[demo] summary "):].strip())
check("B", "the run printed a summary", summary is not None)
if summary is None:
    sys.exit(2)

print("\nB. EXECUTION (measured with the app's own counters)")
print("-" * 78)
print("     %s" % json.dumps(summary, sort_keys=True))
for e in gt["rareMethods"]:
    got = summary.get(e["counter"], 0)
    check("B", "rare %s.%s ran >= %d times (%d)"
          % (e["class"].rsplit(".", 1)[1], e["method"], e["minExpected"], got),
          got >= e["minExpected"],
          "counter %s = %d; raise --min-requests" % (e["counter"], got))
for e in gt["rareBranches"]:
    got = summary.get(e["counter"], 0)
    check("B", "branch %s ran exactly %d times (%d)" % (e["counter"], e["expected"], got),
          got == e["expected"])
dead_fired = [k for k in summary if k.startswith("dead.")]
check("B", "no method marked DEAD executed", not dead_fired, "these fired: %s" % dead_fired)
for e in gt["deadMethods"]:
    if "counter" in e:
        check("B", "dead %s.%s never executed" % (e["class"].rsplit(".", 1)[1], e["method"]),
              summary.get(e["counter"], 0) == 0)
check("B", "the error path produced errors (tier-2 errorTypes has a subject)",
      summary.get("errors", 0) > 0)

# ---------------------------------------------------------------- C. call sites
print("\nC. CALL SITES (textual, non-comment lines under demo/src)")
print("-" * 78)
src = []
for root, _dirs, files in os.walk(os.path.join(DEMO, "src")):
    for f in files:
        if f.endswith(".java"):
            src.extend(open(os.path.join(root, f)).read().splitlines())
code = [l for l in src if not l.lstrip().startswith(("*", "//", "/*"))]
body = "\n".join(code)
for e in gt["deadMethods"]:
    if not e.get("staticCheck"):
        continue
    n = len(re.findall(r"(?<![A-Za-z0-9_$])%s\(" % re.escape(e["method"]), body))
    check("C", "%s.%s has %d source reference(s) as declared"
          % (e["class"].rsplit(".", 1)[1], e["method"], e["srcRefs"]),
          n == e["srcRefs"],
          "found %d, ground-truth.json says %d — a call site was added or removed"
          % (n, e["srcRefs"]))

# ---------------------------------------------------------------- D. bridges
print("\nD. BRIDGE / SYNTHETIC METHODS (read out of the class files with javap)")
print("-" * 78)
javap = {
    "io.auxin.demo.repo.OrderRepository": open(os.path.join(TMP, "javap-order.txt")).read(),
    "io.auxin.demo.repo.CustomerRepository": open(os.path.join(TMP, "javap-customer.txt")).read(),
    "io.auxin.demo.service.PricingService": open(os.path.join(TMP, "javap-pricing.txt")).read(),
}
for e in gt["syntheticOrBridgeMethods"]:
    text = javap.get(e["class"], "")
    check("D", "%s.%s%s exists in the class file"
          % (e["class"].rsplit(".", 1)[1], e["method"], e["desc"]),
          e["desc"] in text, "descriptor not found — javac no longer emits this bridge")

print("\n" + "=" * 78)
if ok:
    print("GROUND TRUTH VERIFIED — demo/ground-truth.json matches the fixture.")
else:
    print("GROUND TRUTH MISMATCH — fix the code or update demo/ground-truth.json.")
print("""
What this does NOT prove: that any coverage agent correctly reports these methods.
That is gate G5, and it needs the agent, JaCoCo and the OTel javaagent attached to this
jar simultaneously, under traffic. Not runnable here (no agent jars built yet, no Docker).""")
sys.exit(0 if ok else 1)
PYEOF
