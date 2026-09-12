#!/usr/bin/env bash
# GATE G3 — end-to-end smoke test for the auxin JVM agent.
#   build the agent -> compile a target app (one class at class-file 52, the rest at 61)
#   -> generate the build manifest -> run under -javaagent three times (unclassified,
#      production, blind probe) -> assert the emitted bytecode shape with javap
# Exits non-zero on the first failing assertion set.
set -euo pipefail

MODULE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$MODULE/smoke/out"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
AGENT="$MODULE/target/ax-agent.jar"

echo "== 1. build the agent =="
(cd "$MODULE" && mvn -q clean package)
ls -l "$AGENT"

echo "== 2. compile the target app =="
rm -rf "$OUT"
mkdir -p "$OUT/classes"
# class file major 52 -> below condy's minimum of 55 -> exercises the field fallback
"$JAVAC" -nowarn --release 8 -d "$OUT/classes" "$MODULE/smoke/src/smoke/LegacyTarget.java" 2>&1 | grep -v "^Note" || true
# Modern target: classfile >= 55 is what matters (condy). Pin to the lowest release that
# guarantees it so this suite runs on JDK 11, 17 and 21 alike -- 11 is the version that
# decides the JDK-8216970 CHECKCAST question, so it must be exercisable.
: "${AX_SMOKE_RELEASE:=11}"
"$JAVAC" -nowarn --release "$AX_SMOKE_RELEASE" -cp "$AGENT:$OUT/classes" -d "$OUT/classes" \
    "$MODULE/smoke/src/smoke/SmokeTarget.java" \
    "$MODULE/smoke/src/smoke/BridgeTarget.java" \
    "$MODULE/smoke/src/smoke/SmokeApp.java" \
    "$MODULE/smoke/src/other/Outsider.java" \
    "$MODULE/smoke/src/io/auxin/userapp/Thing.java" \
    "$MODULE/smoke/src/com/datadog/trace/CustomerCode.java"

echo "== 3. generate the build manifest (stand-in for ax-static) =="
"$JAVA" -cp "$AGENT" io.auxin.agent.manifest.ManifestTool \
    "$OUT/classes" "$OUT/auxin-manifest.json" \
    --artifact=smoke-app --buildSha=smoke001 \
    "--tier2=smoke.SmokeTarget#boundary,smoke.BridgeTarget#work"

run_app() {
  local liveness="$1"; shift
  local port
  port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
  # io.auxin.userapp is deliberately named alongside smoke: it shares a prefix with the
  # agent's own packages, which is the G5-BUG-1 trap. An explicitly scoped package must win
  # over the ignore list, and one prefix away (io.auxin.agent) must still be untouchable.
  "$JAVA" -javaagent:"$AGENT" \
      -Dax.include.packages=smoke:io.auxin.userapp \
      -Dax.manifest="$OUT/auxin-manifest.json" \
      -Dax.collector.url="http://127.0.0.1:$port/v1/ingest" \
      -Dax.flush.interval.ms=1000 \
      -Dax.drain.interval.ms=100 \
      -Dax.log.level=info \
      -Dsmoke.port="$port" \
      -Dsmoke.expectLiveness="$liveness" \
      "$@" \
      -cp "$OUT/classes" smoke.SmokeApp
}

echo
echo "== 4. RUN A: ax.environment unset -> C50 fail-closed, livenessEvidence must be false =="
run_app false

echo
echo "== 5. RUN B: ax.environment=production -> livenessEvidence must be true =="
rm -rf "$OUT/dump"
run_app true -Dax.environment=production -Dax.dump.dir="$OUT/dump"

echo
echo "== 6. RUN C: ax.probe.mode=blind -> PLAN-v2's original shape must still work =="
rm -rf "$OUT/dump-blind"
run_app true -Dax.environment=production -Dax.probe.mode=blind -Dax.dump.dir="$OUT/dump-blind"

echo
echo "== 7. RUN D: ax.condy.descriptor=array -> G3's CHECKCAST finding, on this JDK =="
rm -rf "$OUT/dump-condyarray"
run_app true -Dax.environment=production -Dax.condy.descriptor=array \
    -Dax.dump.dir="$OUT/dump-condyarray"

echo
echo "== 7b. RUN E: ax.bridge.shape=field -> F2's rollback switch must still work =="
rm -rf "$OUT/dump-bridgefield"
run_app true -Dax.environment=production -Dax.bridge.shape=field \
    -Dsmoke.bridgeShape=field -Dax.dump.dir="$OUT/dump-bridgefield"

echo
echo "== 8. BYTECODE SHAPE (javap) =="
JAVAP="$JAVA_HOME/bin/javap"
bfails=0
bchecks=0
bcheck() { # bcheck <what> <expected> <actual>
  bchecks=$((bchecks+1))
  if [ "$2" = "$3" ]; then echo "  PASS  $1"
  else echo "  FAIL  $1 (expected '$2', got '$3')"; bfails=$((bfails+1)); fi
}
count() { "$JAVAP" -v -p -c "$1" | grep -cE "$2" || true; }
bhas() { bchecks=$((bchecks+1))                       # bhas <what> <file> <pattern>
  if [ "$(count "$2" "$3")" -gt 0 ]; then echo "  PASS  $1"
  else echo "  FAIL  $1 (no match for '$3')"; bfails=$((bfails+1)); fi
}
bnot() { bchecks=$((bchecks+1))                       # bnot <what> <file> <pattern>
  if [ "$(count "$2" "$3")" -eq 0 ]; then echo "  PASS  $1"
  else echo "  FAIL  $1 ($(count "$2" "$3") matches for '$3')"; bfails=$((bfails+1)); fi
}

ORIG="$OUT/classes/smoke/SmokeTarget.class"
RTS="$OUT/dump/smoke.SmokeTarget.class"
BLIND="$OUT/dump-blind/smoke.SmokeTarget.class"
BRIDGE="$OUT/dump/smoke.BridgeTarget.class"

# Both dumps carry the same tier-2 instrumentation, so read-then-store MINUS blind is exactly
# the cost of the branch: the probe count in extra BALOADs, IFNEs and stack map frames.
PROBES=$(count "$BLIND" '^ +[0-9]+: bastore')
bcheck "9 probes installed in SmokeTarget (answer() is C51-exempt)" "9" "$PROBES"
bcheck "read-then-store: one BALOAD per probe" "$PROBES" "$(count "$RTS" '^ +[0-9]+: baload')"
bcheck "read-then-store: one IFNE per probe (the second call does NOT re-store)" \
    "$(( $(count "$BLIND" '^ +[0-9]+: ifne') + PROBES ))" "$(count "$RTS" '^ +[0-9]+: ifne')"
# spin()'s first instruction already carries a frame, which the probe reuses instead of
# emitting a second entry at the same offset. So: one new frame per probe, minus that one.
bcheck "read-then-store: one extra stack map frame per probe, minus the one reused by spin()" \
    "$(( $(count "$BLIND" 'frame_type') + PROBES - 1 ))" "$(count "$RTS" 'frame_type')"
bcheck "read-then-store: the array is hoisted to a local, one ASTORE per probe" \
    "$PROBES" \
    "$(( $(count "$RTS" '^ +[0-9]+: astore') - $(count "$BLIND" '^ +[0-9]+: astore') ))"
bnot "condy still carries the array: no \$axProbes field added" "$RTS" 'boolean\[\] \$axProbes'

bnot "blind mode: no BALOAD" "$BLIND" '^ +[0-9]+: baload'
bcheck "blind mode: no added stack map frame (PLAN-v2's whole argument)" \
    "$(count "$ORIG" 'frame_type')" "$(( $(count "$BLIND" 'frame_type') - 1 ))"
bcheck "blind mode: still one BASTORE per probe" "9" "$PROBES"

ARRAYDESC="$OUT/dump-condyarray/smoke.SmokeTarget.class"
bcheck "condy declared [Z: exactly one CHECKCAST dropped per probe (G3, 3 bytes each)" \
    "$PROBES" \
    "$(( $(count "$RTS" '^ +[0-9]+: checkcast') - $(count "$ARRAYDESC" '^ +[0-9]+: checkcast') ))"

bhas "bridge class: reads java/lang/\$Auxin.data" "$BRIDGE" 'java/lang/\$Auxin\.data'
bhas "bridge class: reaches the array through Object.equals" "$BRIDGE" 'java/lang/Object\.equals'
bnot "bridge class: references NOTHING from io/auxin" "$BRIDGE" 'io/auxin'
bcheck "bridge class: read-then-store probes installed too" "3" \
    "$(count "$BRIDGE" '^ +[0-9]+: baload')"

# ---- F2: the bridge keeps condy, via a bootstrap method on the instrumented class itself ----
bhas "F2 bridge: BootstrapMethods names the class's OWN \$axInit" \
    "$BRIDGE" 'REF_invokeStatic smoke/BridgeTarget\.\$axInit'
bhas "F2 bridge: the probe array arrives by condy, not by a field read" \
    "$BRIDGE" 'ldc +#[0-9]+ +// +Dynamic #0:\$axProbes'
bnot "F2 bridge: NO \$axProbes field was added (so a retransform stays legal)" \
    "$BRIDGE" 'boolean\[\] \$axProbes'
bnot "F2 bridge: the class's <clinit> was NOT touched" "$BRIDGE" 'static \{\}'
bhas "F2 bridge: \$axInit guards the F3 hazard with INSTANCEOF [Z" \
    "$BRIDGE" '^ +[0-9]+: instanceof +#[0-9]+ +// +class "\[Z"'
bhas "F2 bridge: \$axInit captures lookup.lookupClass() (the Tier-1b strip handle)" \
    "$BRIDGE" 'MethodHandles\$Lookup\.lookupClass'
# ---- the rollback switch emits exactly the pre-F2 shape ----
FIELDBRIDGE="$OUT/dump-bridgefield/smoke.BridgeTarget.class"
# Both shapes carry the same 3 read-then-store probes and the same original frames. The ONLY
# stack map difference is the F3 guard's merge point -- one frame, in a method we authored, where
# there was nothing to merge with. That is the whole reason F3 is defensible here and was not
# defensible inside somebody else's <clinit>.
bcheck "F2 bridge: exactly ONE hand-written stack map frame more than the field shape" \
    "$(( $(count "$FIELDBRIDGE" 'frame_type') + 1 ))" "$(count "$BRIDGE" 'frame_type')"
bhas "rollback (ax.bridge.shape=field): reads java/lang/\$Auxin.data" \
    "$FIELDBRIDGE" 'java/lang/\$Auxin\.data'
bnot "rollback: references NOTHING from io/auxin" "$FIELDBRIDGE" 'io/auxin'
bhas "rollback: the \$axProbes field IS added" "$FIELDBRIDGE" 'boolean\[\] \$axProbes'
bhas "rollback: a <clinit> prologue IS added" "$FIELDBRIDGE" 'static \{\}'
bnot "rollback: no condy and no self bootstrap method" "$FIELDBRIDGE" '\$axInit'

echo
echo "  SmokeTarget, 9 probes, both dumps identical except the probe shape:"
ob=$(wc -c < "$ORIG" | tr -d ' ')
for f in "$BLIND:blind" "$RTS:read-then-store" "$ARRAYDESC:rts,condy=[Z"; do
  p="${f%%:*}"; label="${f##*:}"
  nb=$(wc -c < "$p" | tr -d ' ')
  printf "    %-16s class %5d -> %5d bytes, %2d stack map frames\n" \
      "$label" "$ob" "$nb" "$(count "$p" 'frame_type')"
done

echo
if [ "$bfails" -gt 0 ]; then
  echo "==== BYTECODE SHAPE FAILED: $bfails/$bchecks ===="
  exit 1
fi
echo "==== $bchecks/$bchecks bytecode shape checks passed ===="

echo
echo "== G3 PASSED (all runs) =="
