#!/usr/bin/env bash
# Fail-open tests: broken or absent configuration must never change what the application does.
set -uo pipefail
MODULE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$MODULE/smoke/out"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
AGENT="$MODULE/target/ax-agent.jar"
MF="$OUT/auxin-manifest.json"
fails=0

# Pin to the lowest release that still yields classfile >= 55 (condy), so this suite runs on
# JDK 11, 17 and 21 alike. Hardcoding 17 made it unrunnable on 11 -- it failed in javac before
# the agent was ever loaded, which is a silent coverage hole in the negative suite itself.
: "${AX_SMOKE_RELEASE:=11}"
"$JAVA_HOME/bin/javac" -nowarn --release "$AX_SMOKE_RELEASE" -cp "$AGENT:$OUT/classes" -d "$OUT/classes" \
    "$MODULE/smoke/src/smoke/NegativeApp.java"

# a manifest whose schemaHash for SmokeTarget is wrong
python3 - "$MF" "$OUT/tampered-manifest.json" <<'PY'
import sys, re
src, dst = sys.argv[1], sys.argv[2]
s = open(src).read()
i = s.index('"name":"smoke.SmokeTarget"')
j = s.index('"schemaHash":"', i) + len('"schemaHash":"')
k = s.index('"', j)
open(dst, 'w').write(s[:j] + ('0' * (k - j)) + s[k:])
PY

run() {
  local label="$1"; shift
  echo "--- $label"
  out="$("$JAVA" -javaagent:"$AGENT" "$@" -cp "$OUT/classes" smoke.NegativeApp ${APPARGS:-} 2>&1)"
  echo "$out" | grep -E "^(APP_OK|ACTIVE|INSTRUMENTED)=|auxin\]"
  echo "$out"
}

expect() { # expect <label> <grep-pattern> <output>
  if echo "$3" | grep -q "$2"; then echo "  PASS  $1"; else echo "  FAIL  $1"; fails=$((fails+1)); fi
}

# Structural assertions on the flush window. grep cannot say "present in classesLoaded but
# absent from instrumentedClasses", which is precisely the G5-FINDING-4 distinction, and a set
# printed from a ConcurrentHashMap has no fixed order to grep for.
jsonexpect() { # jsonexpect <label> <python-expression over w and h> <output>
  if printf '%s' "$3" | python3 -c '
import json, re, sys
body = sys.stdin.read()
m = re.search(r"^FLUSH=(\{.*\})\s*$", body, re.M)
if not m:
    sys.exit(2)
w = json.loads(m.group(1))
h = w.get("agentHealth", {})
sys.exit(0 if eval(sys.argv[1]) else 1)
' "$2" 2>/dev/null; then echo "  PASS  $1"; else echo "  FAIL  $1"; fails=$((fails+1)); fi
}

o="$(run 'no ax.include.packages (default deny)' -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "agent inert"              "ACTIVE=false"       "$o"
expect "nothing instrumented"     "INSTRUMENTED=false" "$o"

o="$(run 'manifest missing' -Dax.include.packages=smoke -Dax.manifest=/nope/missing.json)"
expect "app still correct"        "APP_OK=true"        "$o"
expect "agent inert"              "ACTIVE=false"       "$o"
expect "nothing instrumented"     "INSTRUMENTED=false" "$o"

o="$(run 'schemaHash mismatch' -Dax.include.packages=smoke -Dax.manifest="$OUT/tampered-manifest.json")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "agent active"             "ACTIVE=true"        "$o"
expect "SmokeTarget skipped"      "INSTRUMENTED=false" "$o"
expect "counted as mismatch"      "schemaHashMismatch" "$o"
# G5-FINDING-4. classesLoaded used to BE the instrumented set, so a skipped class vanished from
# it and C10 could not distinguish "this pod never loaded the class" from "we declined to
# instrument it" -- which is how lazily loaded code gets labelled dead.
jsonexpect "skipped class is still reported as LOADED (G5-FINDING-4)" \
    '"smoke.SmokeTarget" in w["classesLoaded"]' "$o"
jsonexpect "and is absent from instrumentedClasses" \
    '"smoke.SmokeTarget" not in w["instrumentedClasses"]' "$o"
jsonexpect "so the two sets really are different" \
    'len(w["instrumentedClasses"]) < len(w["classesLoaded"])' "$o"

# ---- G5-BUG-1: the ignore list must never silently overrule an explicit scope --------------
# The shipped agent held "io/auxin/" in one flat prefix list, matched it with startsWith
# BEFORE the include scope, and counted nothing. On an application under that package root it
# instrumented zero classes and reported classesInstrumented 0 / classesSkipped {} /
# transformFailures 0 -- indistinguishable from "all of your code is dead" (VALIDATION C37).
o="$(run 'ignore-list prefix collision: io.auxin.userapp' -Dax.include.packages=io.auxin.userapp -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
jsonexpect "the colliding customer package IS instrumented" \
    '"io.auxin.userapp.Thing" in w["instrumentedClasses"]' "$o"
jsonexpect "and did NOT report a clean zero-coverage run" \
    'h["scopeMatchedNothing"] is False and h["classesInstrumented"] >= 1' "$o"
jsonexpect "no agent class was instrumented or reported loaded" \
    'not any(c.startswith("io.auxin.agent.") for c in w["classesLoaded"] + w["instrumentedClasses"])' "$o"

# The soft tier, both directions, on one fixture class in com.datadog.trace.
# More specific than the veto com/datadog/ -> the operator named it, so the scope wins.
o="$(run 'ignore-list collision, scope more specific: com.datadog.trace' -Dax.include.packages=com.datadog.trace -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "warned that the scope wins" "the scope wins"   "$o"
jsonexpect "the customer's com.datadog.* package IS instrumented" \
    '"com.datadog.trace.CustomerCode" in w["instrumentedClasses"]' "$o"

# Broader than the veto -> the veto holds, so another APM's runtime is safe from a company-wide
# scope. What must NOT happen is doing nothing in silence: this is the C37 case exactly.
o="$(run 'ignore-list collision, scope broader than the veto: com' -Dax.include.packages=com -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "counted as inScopeVetoed" "inScopeVetoed"      "$o"
expect "named the class and the prefix" "matches ax.include.packages but was NOT instrumented" "$o"
expect "warned that it did no work" "matched NO instrumented class" "$o"
jsonexpect "the colliding class was NOT instrumented" \
    '"com.datadog.trace.CustomerCode" not in w["instrumentedClasses"]' "$o"
jsonexpect "but it IS reported as loaded, so absence is not read as death (C10)" \
    '"com.datadog.trace.CustomerCode" in w["classesLoaded"]' "$o"
jsonexpect "scopeMatchedNothing is on the wire, so zero coverage is not read as death" \
    'h["scopeMatchedNothing"] is True and h["classesInstrumented"] == 0' "$o"
jsonexpect "the veto is counted per reason, not silent" \
    'h["classesSkipped"].get("inScopeVetoed", 0) >= 1 and h["classesSkipped"].get("ignoredPrefix", 0) >= 1' "$o"

o="$(run 'scope overlaps the agent'"'"'s own runtime (ax.include.packages=io.auxin)' -Dax.include.packages=io.auxin -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "warned once at premain"   "also matches the agent's own runtime" "$o"
jsonexpect "the userapp class is still instrumented" \
    '"io.auxin.userapp.Thing" in w["instrumentedClasses"]' "$o"
jsonexpect "and not one agent class was touched" \
    'not any(c.startswith("io.auxin.agent.") or c.startswith("io.auxin.shaded.") for c in w["classesLoaded"])' "$o"

o="$(run 'ax.enabled=false (agent kill switch)' -Dax.enabled=false -Dax.include.packages=smoke -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "agent inert"              "ACTIVE=false"       "$o"

o="$(run 'ax.tier2.enabled=false (tier kill switch)' -Dax.tier2.enabled=false -Dax.include.packages=smoke -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "tier-1 still installed"   "INSTRUMENTED=true"  "$o"
expect "no tier2 in the window"   '"tier2":\[\]'       "$o"

o="$(run 'ax.exclude.packages=smoke (package kill switch)' -Dax.include.packages=smoke -Dax.exclude.packages=smoke -Dax.manifest="$MF")"
expect "app still correct"        "APP_OK=true"        "$o"
expect "nothing instrumented"     "INSTRUMENTED=false" "$o"
expect "counted as kill switch"   "packageKillSwitch"  "$o"

o="$(run 'startup CPU budget of 0ms (circuit breaker)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.startup.cpu.budget.ms=0)"
expect "app still correct"        "APP_OK=true"        "$o"
expect "window marked degraded"   '"degraded":true'    "$o"
expect "counted as budget breach" "budgetExceeded"     "$o"

APPARGS=flood
o="$(run 'ring overflow (drop on full)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.ring.capacity=256 -Dax.drain.interval.ms=5000)"
unset APPARGS
expect "app still correct"        "APP_OK=true"        "$o"
expect "drops counted"            '"ringDropped":[1-9]' "$o"

o="$(run 'collector unreachable' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.collector.url=http://127.0.0.1:1/v1/ingest)"
expect "app still correct"        "APP_OK=true"        "$o"
expect "tier-1 still installed"   "INSTRUMENTED=true"  "$o"

# CHANGE 2 fail-open: a loader that cannot resolve ProbeHolder must be skipped, never handed a
# reference that would throw NoClassDefFoundError inside a business method.
o="$(run 'bridge disabled + agent-invisible class loader' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.bridge.enabled=false)"
expect "app still correct"        "APP_OK=true"          "$o"
expect "bridge reports disabled"  "BRIDGE=notAttempted"  "$o"
expect "visible loaders still ok" "INSTRUMENTED=true"    "$o"
expect "isolated class skipped"   "agentNotVisible"      "$o"

# CHANGE 1 fail-open: an unknown probe shape must warn and use the default, never guess.
o="$(run 'unknown ax.probe.mode' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.probe.mode=nonsense)"
expect "app still correct"        "APP_OK=true"              "$o"
expect "warned about the value"   "unknown value for ax.probe.mode" "$o"
expect "fell back to the default" "probeMode=readthenstore"  "$o"

o="$(run 'ax.probe.mode=blind (G1 rollback switch)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.probe.mode=blind)"
expect "app still correct"        "APP_OK=true"        "$o"
expect "tier-1 still installed"   "INSTRUMENTED=true"  "$o"
expect "blind shape reported"     "probeMode=blind"    "$o"

# F2 fail-open: the pre-F2 field+<clinit> bridge must remain one system property away.
o="$(run 'ax.bridge.shape=field (F2 rollback switch)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.bridge.shape=field)"
expect "app still correct"        "APP_OK=true"          "$o"
expect "tier-1 still installed"   "INSTRUMENTED=true"    "$o"
expect "field shape reported"     "bridgeShape=field"    "$o"

o="$(run 'unknown ax.bridge.shape' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.bridge.shape=nonsense)"
expect "app still correct"        "APP_OK=true"                        "$o"
expect "warned about the value"   "unknown value for ax.bridge.shape"  "$o"
expect "fell back to the default" "bridgeShape=selfbsm"                "$o"

echo
if [ "$fails" -gt 0 ]; then echo "== NEGATIVE TESTS FAILED: $fails =="; exit 1; fi
echo "== NEGATIVE TESTS PASSED =="
