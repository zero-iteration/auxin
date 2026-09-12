#!/usr/bin/env bash
# GATE G3 — end-to-end smoke test for the auxin JVM agent.
#   build the agent -> compile a target app (one class at class-file 52, the rest at 61)
#   -> generate the build manifest -> run under -javaagent seven times (unclassified,
#      production, blind probe, condy=[Z, bridge.shape=field, and the SCOPE-v3 call-edge tier
#      at sample.rate=1 and at the production 1-in-1024) -> assert the emitted bytecode shape
#      with javap
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
    "$MODULE/smoke/src/smoke/EdgeTarget.java" \
    "$MODULE/smoke/src/smoke/SmokeApp.java" \
    "$MODULE/smoke/src/other/Outsider.java" \
    "$MODULE/smoke/src/io/auxin/userapp/Thing.java" \
    "$MODULE/smoke/src/com/datadog/trace/CustomerCode.java"

echo "== 3. generate the build manifest with the REAL ax-static =="
# This is the production manifest generator, not a stand-in. Until now this step ran
# io.auxin.agent.manifest.ManifestTool, a class that shipped inside the agent jar and described
# itself as a "temporary stand-in for ax-static": the agent had never once consumed a manifest
# produced by the thing that produces them in production, and the two carried DIFFERENT glob
# dialects for --tier2 (ManifestTool did trailing-* prefix matching, so `com.acme.*` crossed
# package boundaries; ax-static's `*` stays inside one segment and `**` crosses). ManifestTool is
# deleted; this is the only manifest generator now.
AXSTATIC="$MODULE/../ax-static/target/ax-static.jar"
if [ ! -f "$AXSTATIC" ]; then
  echo "FATAL: the manifest generator is not built: $AXSTATIC" >&2
  echo "       build it with:" >&2
  echo "           (cd $MODULE/../ax-static && mvn -q clean package)" >&2
  echo "       There is no fallback generator. A manifest produced by anything other than" >&2
  echo "       ax-static would test the agent against a contract nothing ships." >&2
  exit 3
fi
# ax-static targets JDK 17; this suite runs on JDK 11, 17 and 21 (JAVA_HOME above). The generator
# is a BUILD-TIME tool whose only output is JSON, so the JDK that runs it is independent of the
# JVM under test -- run it on 17 even when JAVA_HOME is 11, which is the only way this step can
# work there at all (a 17 class file will not load on 11).
AXSTATIC_JAVA="${AX_STATIC_JAVA:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java}"
if [ ! -x "$AXSTATIC_JAVA" ]; then
  echo "FATAL: no JDK 17+ to run ax-static with: $AXSTATIC_JAVA" >&2
  echo "       ax-static is compiled for release 17, so it cannot be run by the JDK under" >&2
  echo "       test when that is JDK 11. Set AX_STATIC_JAVA=/path/to/jdk17+/bin/java" >&2
  exit 3
fi
echo "  generator: $AXSTATIC"
echo "  run with : $AXSTATIC_JAVA ($("$AXSTATIC_JAVA" -version 2>&1 | head -1))"
echo "  under test: $JAVA ($("$JAVA" -version 2>&1 | head -1))"
# --- tier-2 selection, translated from the old ManifestTool invocation -------------------
# Every old pattern was an exact `Class#method` with no wildcard, so each one means the same
# thing in both dialects and transfers verbatim; ManifestTool took them comma-joined, ax-static
# takes one --tier2 per pattern. The SET of selected methods is asserted below to be identical.
#
# --tier2-exclude 'smoke.SmokeApp*#*' is the one addition, and it is a subtraction: ax-static
# auto-selects every entry point, and SmokeApp carries `main` plus the anonymous HttpHandler
# (SmokeApp$1) that IS this harness's collector. Timing the assertion harness's own HTTP plumbing
# would make `edgesSampledRoots` depend on how many collector POSTs happened to land between two
# flushes -- a timing-dependent number the edge assertions compare against an exact 5. The glob
# is `SmokeApp*` and not `SmokeApp` because ax-static treats `$` as an ordinary character, so
# `*` (which stays inside one package segment) is what reaches SmokeApp$1.
"$AXSTATIC_JAVA" -jar "$AXSTATIC" \
    --input "$OUT/classes" \
    --build-sha smoke001 \
    --artifact smoke-app \
    --output "$OUT/auxin-manifest.json" \
    --tier2 'smoke.SmokeTarget#boundary' \
    --tier2 'smoke.BridgeTarget#work' \
    --tier2 'smoke.EdgeTarget#root' \
    --tier2 'smoke.EdgeTarget#outerRoot' \
    --tier2 'smoke.EdgeTarget#deepRoot' \
    --tier2 'smoke.EdgeTarget#fanRoot' \
    --tier2 'smoke.EdgeTarget#throwingRoot' \
    --tier2 'smoke.EdgeTarget#strippedRoot' \
    --tier2-exclude 'smoke.SmokeApp*#*'

# The tier-2 set is the one thing a change of generator could silently alter, and the edge-tier
# assertions below are counts of roots. So it is asserted, not assumed.
echo "  tier-2 selection:"
python3 - "$OUT/auxin-manifest.json" <<'PY'
import json, sys
expected = [
    "smoke.BridgeTarget#work(I)I",
    "smoke.EdgeTarget#deepRoot(I)I",
    "smoke.EdgeTarget#fanRoot(I)I",
    "smoke.EdgeTarget#outerRoot(I)I",
    "smoke.EdgeTarget#root(I)I",
    "smoke.EdgeTarget#strippedRoot(I)I",
    "smoke.EdgeTarget#throwingRoot(I)I",
    "smoke.SmokeTarget#boundary(I)I",
]
m = json.load(open(sys.argv[1]))
got = sorted(c["name"] + "#" + x["name"] + x["desc"]
             for c in m["classes"] for x in c["methods"] if x["tier2"])
for k in got:
    print("      " + k)
if got != sorted(expected):
    print("  FATAL: tier-2 selection is not the set the edge assertions were written against")
    print("         missing: %s" % sorted(set(expected) - set(got)))
    print("         extra  : %s" % sorted(set(got) - set(expected)))
    sys.exit(3)
PY

run_app() {
  local liveness="$1"; shift
  local port
  port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
  # io.auxin.userapp is deliberately named alongside smoke: it shares a prefix with the
  # agent's own packages, which is the G5-BUG-1 trap. An explicitly scoped package must win
  # over the ignore list, and one prefix away (io.auxin.agent) must still be untouchable.
  "$JAVA" -javaagent:"$AGENT" \
      -Dax.edges.enabled=false \
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
echo "== 7c. RUN F: the call-edge tier, sample.rate=1 (SCOPE-v3) =="
# rate=1 traces every root entry, and the caps are shrunk so both of them bite on a graph small
# enough to assert exactly. flush.interval.ms is pushed out so every window in this run is one
# an assertion asked for: edge counts are per window, and a background flush landing between a
# call and its measurement would split them.
rm -rf "$OUT/dump-edges"
run_app true -Dax.environment=production \
    -Dax.edges.enabled=true -Dax.edges.sample.rate=1 \
    -Dax.edges.max.depth=8 -Dax.edges.max.per.root=10 \
    -Dax.flush.interval.ms=600000 \
    -Dsmoke.edges=full -Dsmoke.edges.maxDepth=8 -Dsmoke.edges.maxPerRoot=10 \
    -Dax.dump.dir="$OUT/dump-edges"

echo
echo "== 7d. RUN G: the call-edge tier at the PRODUCTION default, 1-in-1024 =="
run_app true -Dax.environment=production \
    -Dax.edges.enabled=true \
    -Dax.flush.interval.ms=600000 \
    -Dsmoke.edges=sampled

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
# 10 probe-eligible methods, 3 of them C51-exempt under the REAL generator, so 7 probes.
# This was 9 while the manifest came from the in-agent stand-in, whose C51 rule recognised only
# a two-instruction constant return -- answer(). ax-static also recognises the other two shapes
# TOSEM 2022 names: counter() is a plain field accessor (aload_0/getfield/lreturn) and
# SmokeTarget() is a delegating constructor (aload_0/iconst_0/invokespecial this(int)/return).
# Both are genuinely C51-exempt; the stand-in was under-reporting, and the agent was therefore
# installing two probes that could never have been read as evidence of anything.
bcheck "7 probes installed in SmokeTarget (answer(), counter() and <init>() are C51-exempt)" \
    "7" "$PROBES"
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
bcheck "blind mode: still one BASTORE per probe" "7" "$PROBES"

ARRAYDESC="$OUT/dump-condyarray/smoke.SmokeTarget.class"
bcheck "condy declared [Z: exactly one CHECKCAST dropped per probe (G3, 3 bytes each)" \
    "$PROBES" \
    "$(( $(count "$RTS" '^ +[0-9]+: checkcast') - $(count "$ARRAYDESC" '^ +[0-9]+: checkcast') ))"

bhas "bridge class: reads java/lang/\$Auxin.data" "$BRIDGE" 'java/lang/\$Auxin\.data'
bhas "bridge class: reaches the array through Object.equals" "$BRIDGE" 'java/lang/Object\.equals'
bnot "bridge class: references NOTHING from io/auxin" "$BRIDGE" 'io/auxin'
# work() and idle(); BridgeTarget's implicit super() constructor is C51-exempt (was 3 under the
# stand-in generator, which did not classify a delegating constructor).
bcheck "bridge class: read-then-store probes installed too" "2" \
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

# ---- SCOPE-v3: the call-edge tier's emitted shape -----------------------------------------
EDGEOFF="$OUT/dump/smoke.EdgeTarget.class"
EDGEON="$OUT/dump-edges/smoke.EdgeTarget.class"
insn() { count "$1" "^ +[0-9]+: invokestatic.*EdgeRuntime\\.$2:"; }

bnot "edges OFF by default: not one EdgeRuntime reference in the shipped shape" \
    "$RTS" 'EdgeRuntime'
bnot "edges OFF by default: none in the edge subject either" "$EDGEOFF" 'EdgeRuntime'
# 6 roots (the tier-2 allowlist), 6 other probed methods (leaf, level1-3, recurse, thrower).
# The edge tier is emitted from the same loop as the tier-1 probe and behind the same C51 gate,
# so a method the manifest marks dynamicallyObservable=false gets neither. Under the real
# generator EdgeTarget's <init>() (delegating constructor) and calls() (plain field accessor) are
# C51-exempt, so they drop out of BOTH the probe count and the edge count: this was 8 while the
# manifest came from the in-agent stand-in. EdgeTarget's own javadoc already states the rule
# ("C51 would mark such a method not-dynamically-observable, and then it would get neither a
# probe nor an edge") -- it was simply written against the weaker stand-in classification.
bcheck "edge tier: one rootEnter per boundary method" "6" "$(insn "$EDGEON" rootEnter)"
bcheck "edge tier: one enter per non-boundary instrumented method" "6" "$(insn "$EDGEON" enter)"
# Each root returns once and has one handler => 2 rootExits.
bcheck "edge tier: rootExit at every return AND in the handler" "12" "$(insn "$EDGEON" rootExit)"
# Per RETURN, not per method: recurse() has two, and thrower() has none at all because it only
# throws -- which is exactly the case EdgeRuntime.popTo() exists to repair. So:
# level1 + level2 + level3 + leaf = 4, recurse = 2, thrower = 0.
bcheck "edge tier: exit at every return -- two in recurse(), NONE in the method that only throws" \
    "6" "$(insn "$EDGEON" exit)"
bcheck "edge tier: one new exception-table entry per root, and not one anywhere else" \
    "$(( $(count "$EDGEOFF" '^ +[0-9]+ +[0-9]+ +[0-9]+ +any$') + 6 ))" \
    "$(count "$EDGEON" '^ +[0-9]+ +[0-9]+ +[0-9]+ +any$')"
# THE point of the callee shape: no local, no branch, no handler, therefore no frame. Every one
# of the 6 new frames belongs to a root's handler; the other 6 methods gained none.
bcheck "edge tier: exactly one new stack map frame per ROOT, none for any callee" \
    "$(( $(count "$EDGEOFF" 'frame_type') + 6 ))" "$(count "$EDGEON" 'frame_type')"
# SCOPE-v3.1 CHANGED THIS ASSERTION, AND THE NEW ONE IS STRICTLY STRONGER.
#
# It used to read "the handler frame declares ZERO locals (nothing named, nothing to get wrong)"
# and expect 6 matches of `locals = []`. That was sound only while the edge tier was the LAST
# thing emitted into a method, because an undeclared local is `top` and `top` is assignable-TO,
# not assignable-FROM. The per-request trace tier is now emitted after the edge tier and its
# handler range covers the edge handler block, so a zero-locals frame there produced exactly:
#
#   VerifyError: Type top (current frame, locals[0]) is not assignable to 'traceapp/SearchFilter'
#
# The frame now declares the receiver and the descriptor's arguments -- which cannot be got
# wrong, because both come from the method's own signature. So: zero of the old shape, and six
# of the honest one, named exactly rather than counted vaguely.
bcheck "edge tier: NOT ONE handler frame erases locals[0] to top (the coexistence VerifyError)" \
    "0" "$(count "$EDGEON" 'locals = \[\]')"
bcheck "edge tier: every root's handler frame names the receiver and the descriptor's argument" \
    "6" "$(count "$EDGEON" 'locals = \[ class smoke/EdgeTarget, int \]')"
bcheck "edge tier: the callee adds NO local slot" \
    "$("$JAVAP" -v -p -c "$EDGEOFF" | awk '/public int level1\(int\)/,/public int level2/' \
        | grep -o 'locals=[0-9]*' | head -1)" \
    "$("$JAVAP" -v -p -c "$EDGEON" | awk '/public int level1\(int\)/,/public int level2/' \
        | grep -o 'locals=[0-9]*' | head -1)"
bcheck "edge tier: the tier-1 probes are untouched (same BALOAD count)" \
    "$(count "$EDGEOFF" '^ +[0-9]+: baload')" "$(count "$EDGEON" '^ +[0-9]+: baload')"

echo
printf "    EdgeTarget class %5d -> %5d bytes with the edge tier (+%d%%), %d -> %d frames\n" \
    "$(wc -c < "$EDGEOFF" | tr -d ' ')" "$(wc -c < "$EDGEON" | tr -d ' ')" \
    "$(( ($(wc -c < "$EDGEON" | tr -d ' ') - $(wc -c < "$EDGEOFF" | tr -d ' ')) * 100 \
        / $(wc -c < "$EDGEOFF" | tr -d ' ') ))" \
    "$(count "$EDGEOFF" 'frame_type')" "$(count "$EDGEON" 'frame_type')"

echo
echo "  SmokeTarget, $PROBES probes, both dumps identical except the probe shape:"
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
