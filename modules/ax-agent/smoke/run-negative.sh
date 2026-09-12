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

if [ ! -f "$MF" ]; then
  echo "FATAL: $MF is missing. Run ./smoke/run-smoke.sh first -- it is what generates the" >&2
  echo "       manifest (with the real ax-static) that this suite tampers with." >&2
  exit 3
fi

# A manifest whose schemaHash for SmokeTarget is wrong.
#
# Parsed as JSON rather than string-spliced: the real generator (ax-static) pretty-prints
# `"name": "smoke.SmokeTarget"` with a space after the colon, which the old byte-level splice
# -- written against ManifestTool's compact output -- would not have found. It would have raised
# ValueError, left no tampered file behind, and turned the three "schemaHash mismatch"
# assertions below into a silent re-run of "manifest missing". Failing loudly instead.
python3 - "$MF" "$OUT/tampered-manifest.json" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
doc = json.load(open(src))
hit = [c for c in doc["classes"] if c["name"] == "smoke.SmokeTarget"]
if len(hit) != 1:
    sys.exit("tamper: expected exactly one smoke.SmokeTarget entry, found %d" % len(hit))
if len(hit[0]["schemaHash"]) != 64:
    sys.exit("tamper: schemaHash is not 64 hex chars: %r" % hit[0]["schemaHash"])
hit[0]["schemaHash"] = "0" * 64
with open(dst, "w") as f:
    json.dump(doc, f)
PY
[ -f "$OUT/tampered-manifest.json" ] || { echo "FATAL: tampering failed" >&2; exit 3; }

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

# ---- SCOPE-v3: the call-edge tier. OFF by default, and every switch around it fail-open ----
# ax.edges.enabled defaults to TRUE since 2026-09-12 (owner's decision, justified by G6: the
# unsampled path costs 0.028-0.340 ns/method against the 0.72-0.77 ns/probe G1 rejected). So the
# off-path is now tested by setting it EXPLICITLY -- the switch still has to work and still has to
# be inert when thrown.
o="$(run 'ax.edges.enabled=false: the edge tier must be inert' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=false)"
expect "app still correct"          "APP_OK=true"          "$o"
expect "edge tier off when disabled" "EDGES=false"         "$o"
expect "tier-1 still installed"     "INSTRUMENTED=true"    "$o"
expect "reported off on the wire"   '"edgesEnabled":false' "$o"
expect "and the edges array is empty, not absent" '"edges":\[\]' "$o"
jsonexpect "not one edge recorded, after a five-deep call graph really ran" \
    'w["edges"] == [] and h["edgesRecorded"] == 0 and h["edgesSampledRoots"] == 0' "$o"

o="$(run 'ax.edges.enabled=true' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1)"
expect "app still correct"          "APP_OK=true"          "$o"
expect "edge tier armed"            "EDGES=true"           "$o"
expect "no trace left open"         "EDGETRACES=0"         "$o"
jsonexpect "edges recorded, and the tier never failed itself off" \
    'len(w["edges"]) > 0 and h["edgeTierFailures"] == 0 and h["edgesTruncatedDistinct"] == 0' "$o"
jsonexpect "the window is NOT degraded by the new tier" 'h["degraded"] is False' "$o"

# The edge tier's roots come from the manifest's tier2 flag, NOT from tier-2 being switched on.
# If it silently needed tier-2 it would be one kill switch away from recording nothing.
o="$(run 'edges on, tier2 OFF: the tiers are independent' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1 -Dax.tier2.enabled=false)"
expect "app still correct"          "APP_OK=true"          "$o"
expect "no tier2 in the window"     '"tier2":\[\]'         "$o"
jsonexpect "edges still recorded with tier-2 off" 'len(w["edges"]) > 0' "$o"

# And the other direction: no probes at all, edges still recorded.
o="$(run 'edges on, tier1 OFF' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1 -Dax.tier1.enabled=false)"
expect "app still correct"          "APP_OK=true"          "$o"
jsonexpect "not one probe fired (tier-1 really is off)" \
    'all(not any(__import__("base64").b64decode(c["probes"])) for c in w["coverage"])' "$o"
# ...and the window must not therefore claim every method in the application is dead. An
# all-zero `probes` bitset is only evidence of death for indices that actually carry a probe,
# and with tier-1 off none of them do -- a fact about THIS JVM that no build manifest can carry,
# which is why the installed-probe mask ships alongside the bitset.
jsonexpect "no probe is claimed INSTALLED either, so the all-zero coverage reads as 'no evidence' rather than 'all dead'" \
    'all(not any(__import__("base64").b64decode(c["probesInstalled"])) for c in w["coverage"])' "$o"
jsonexpect "edges still recorded with tier-1 off" 'len(w["edges"]) > 0' "$o"

o="$(run 'ax.edges.sample.rate=1000 (not a power of two)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1000)"
expect "app still correct"          "APP_OK=true"          "$o"
expect "warned about the value"     "is not a power of two" "$o"
expect "rounded up, out loud"       "edgesSampleRate=1024" "$o"
jsonexpect "and the rate it actually used is on the wire" 'h["edgesSampleRate"] == 1024' "$o"

o="$(run 'ax.edges.max.depth=0 (out of range)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.max.depth=0)"
expect "app still correct"          "APP_OK=true"          "$o"
expect "clamped, out loud"          "ax.edges.max.depth=0 is out of range" "$o"

# "Fails open" is otherwise an untestable claim: nothing in EdgeRuntime is supposed to be able to
# throw, so the latch, the counter and the one-shot WARN would never be exercised -- and an alert
# on edgeTierFailures that has never fired once is not an alert.
APPARGS=edgefail
o="$(run 'edge tier fails open (deliberate drill)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1)"
unset APPARGS
expect "app still correct"          "APP_OK=true"          "$o"
expect "app correct before"         "EDGEFAIL_BEFORE=18"   "$o"
expect "and after the tier died"    "EDGEFAIL_AFTER=18"    "$o"
expect "said so exactly once"       "is now OFF for the life of" "$o"
expect "tier latched off"           "EDGES=false"          "$o"
expect "no trace left open"         "EDGETRACES=0"         "$o"
jsonexpect "counted as edgeTierFailures" 'h["edgeTierFailures"] >= 1' "$o"
jsonexpect "the window is NOT degraded: coverage and tier-2 are still evidence" \
    'h["degraded"] is False and len(w["coverage"]) > 0' "$o"
jsonexpect "and tier-2 still recorded the boundary methods it timed" 'len(w["tier2"]) > 0' "$o"

# Level three of the kill switch. The edge tier is emitted by the same transformer pass, so a
# package that is out of scope has no edges for the same reason it has no probes.
o="$(run 'edges on + ax.exclude.packages=smoke (package kill switch)' -Dax.include.packages=smoke -Dax.exclude.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1)"
expect "app still correct"          "APP_OK=true"          "$o"
expect "nothing instrumented"       "INSTRUMENTED=false"   "$o"
expect "counted as kill switch"     "packageKillSwitch"    "$o"
jsonexpect "and not one edge, though the tier is armed" \
    'h["edgesEnabled"] is True and w["edges"] == [] and h["edgesRecorded"] == 0' "$o"

APPARGS=edgeflood
o="$(run 'edge ring overflow (drop on full)' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1 -Dax.edges.ring.capacity=256 -Dax.drain.interval.ms=5000)"
unset APPARGS
expect "app still correct"          "APP_OK=true"          "$o"
expect "edge drops counted"         '"edgesDropped":[1-9]' "$o"
jsonexpect "tier-2's own ring was NOT collateral damage (separate rings, separate accounting)" \
    'h["ringDropped"] == 0' "$o"

# A bridged class cannot carry a direct EdgeRuntime call for exactly the reason it cannot carry
# a Tier2Runtime one (an Object.equals hop allocates per invocation). It must degrade to
# tier-1 and be counted -- never a NoClassDefFoundError inside a business method.
o="$(run 'edges on + an agent-invisible class loader' -Dax.include.packages=smoke -Dax.manifest="$MF" -Dax.edges.enabled=true -Dax.edges.sample.rate=1)"
expect "isolated class still works" "APP_OK=true"          "$o"
jsonexpect "the bridged class was instrumented for tier-1 anyway" \
    '"smoke.BridgeTarget" in w["instrumentedClasses"]' "$o"
jsonexpect "and its edge instrumentation was declined and counted" \
    'h["classesSkipped"].get("edgesNotBridgeable", 0) >= 1' "$o"

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

# ---- the DEFAULT is on, and a flip back must be a red test, not a quiet regression ----
o="$(run 'ax.edges.* unset: the tier is ON by default' -Dax.include.packages=smoke -Dax.manifest="$MF")"
expect "app still correct"            "APP_OK=true"         "$o"
expect "edge tier ON by default"      "EDGES=true"          "$o"
expect "reported on the wire"         '"edgesEnabled":true' "$o"
expect "tier-1 unaffected"            "INSTRUMENTED=true"   "$o"
jsonexpect "the production sample rate is the default, not rate=1" \
    'h["edgesSampleRate"] == 1024' "$o"
