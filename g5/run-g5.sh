#!/usr/bin/env bash
# =============================================================================
# GATE G5 — three-agent coexistence harness
# =============================================================================
# ax-agent + JaCoCo + the OpenTelemetry javaagent, attached to the SAME JVM, under
# real traffic, asserting that all three fire SIMULTANEOUSLY. Two of three is a failure:
# the OTel/SkyWalking conflict in VALIDATION A6 was invisible until the first request
# triggered a retransform, so every assertion here is made after traffic, never at startup.
#
#   ./run-g5.sh              # JDK 11, 17 and 21, full matrix
#   ./run-g5.sh --quick      # JDK 17 only
#   ./run-g5.sh --jdks=17,21
#
# No Docker, no cluster. The agents are jars from Maven Central; the target app is
# demo/, whose dead code is measured rather than asserted (demo/ground-truth.json).
#
# Runs the whole matrix, then exits non-zero if ANY assertion failed (it does not stop early --
# an attribution matrix is only useful complete). INFO lines are measurements that are recorded
# but not gated; see RESULTS.md for why (the Tier-1b/foreign-retransform interaction is
# known-unhandled, and a harness that FAILED on it would be asserting a fix that does not exist).
# =============================================================================
set -uo pipefail

G5="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$G5/.." && pwd)"
OUT="$G5/out"
DEMO="$ROOT/demo"
AGENT_MODULE="$ROOT/modules/ax-agent"

# ---- pinned dependency versions (recorded in RESULTS.md) --------------------------------
JACOCO_VERSION="${JACOCO_VERSION:-0.8.13}"
OTEL_VERSION="${OTEL_VERSION:-2.31.1}"

M2="${M2_REPO:-$HOME/.m2/repository}"
JACOCO_AGENT="$M2/org/jacoco/org.jacoco.agent/$JACOCO_VERSION/org.jacoco.agent-$JACOCO_VERSION-runtime.jar"
JACOCO_CLI="$M2/org/jacoco/org.jacoco.cli/$JACOCO_VERSION/org.jacoco.cli-$JACOCO_VERSION-nodeps.jar"
OTEL_AGENT="$M2/io/opentelemetry/javaagent/opentelemetry-javaagent/$OTEL_VERSION/opentelemetry-javaagent-$OTEL_VERSION.jar"

JDK17="${JDK17:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
JDK11="${JDK11:-$(brew --prefix openjdk@11 2>/dev/null)/libexec/openjdk.jdk/Contents/Home}"
JDK21="${JDK21:-$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home}"

# Deterministic traffic: --max-requests makes the run exactly reproducible, and
# --min-requests is what guarantees every rare branch fires (demo/README.md).
DEMO_ARGS=(--demo:--seconds=2 --demo:--min-requests=600 --demo:--max-requests=2000 --demo:--threads=4)

JDKS="11,17,21"
for a in "$@"; do
  case "$a" in
    --quick) JDKS="17" ;;
    --jdks=*) JDKS="${a#--jdks=}" ;;
    *) echo "unknown option: $a"; exit 2 ;;
  esac
done

# bash 3.2 (macOS) has no safe empty-array expansion under `set -u`, so the two report
# buffers are newline-joined strings rather than arrays.
PASS=0; FAIL=0; INFO=0
FAILED_LINES=""
INFO_LINES=""

say() { printf '%s\n' "$*"; }
hdr() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

# =============================================================================
# 0. dependencies
# =============================================================================
hdr "0. dependencies"
fetch() { # fetch <artifact-coords> <expected-path>
  if [ -f "$2" ]; then say "  have  $(basename "$2")"; return 0; fi
  say "  fetching $1"
  mvn -q dependency:get -Dartifact="$1" >/dev/null 2>&1
  if [ ! -f "$2" ]; then say "  FATAL: could not resolve $1"; exit 3; fi
}
fetch "org.jacoco:org.jacoco.agent:$JACOCO_VERSION:jar:runtime" "$JACOCO_AGENT"
fetch "org.jacoco:org.jacoco.cli:$JACOCO_VERSION:jar:nodeps" "$JACOCO_CLI"
fetch "io.opentelemetry.javaagent:opentelemetry-javaagent:$OTEL_VERSION" "$OTEL_AGENT"

for j in "$JDK11:11" "$JDK17:17" "$JDK21:21"; do
  home="${j%%:*}"; ver="${j##*:}"
  if [ -x "$home/bin/java" ]; then
    say "  JDK $ver  $("$home/bin/java" -version 2>&1 | head -1)"
  else
    say "  JDK $ver  NOT PRESENT ($home)"
  fi
done

# =============================================================================
# 1. build
# =============================================================================
hdr "1. build"
if [ ! -f "$AGENT_MODULE/target/ax-agent.jar" ]; then
  say "  building ax-agent"
  (cd "$AGENT_MODULE" && mvn -q package) || { say "  FATAL: ax-agent build failed"; exit 3; }
fi
sha() { shasum -a 256 "$1" | cut -c1-16; }

# SNAPSHOT the agent jar before anything runs. Other work in this tree rebuilds
# modules/ax-agent/target/ax-agent.jar, and a matrix that straddles two artifacts is not a
# result. Everything below runs against the snapshot, and the snapshot's sha is what
# RESULTS.md cites.
mkdir -p "$OUT"
AX_SHIPPED="$OUT/ax-agent-shipped.jar"
cp "$AGENT_MODULE/target/ax-agent.jar" "$AX_SHIPPED"
GT_JAR_SHA="$(sha "$AX_SHIPPED")"
GT_MODULE_SHA_AT_START="$(sha "$AGENT_MODULE/target/ax-agent.jar")"
say "  ax-agent      $(wc -c < "$AX_SHIPPED" | tr -d ' ') bytes  sha256:$GT_JAR_SHA  (snapshotted to out/ax-agent-shipped.jar)"

if [ ! -f "$DEMO/target/auxin-demo.jar" ]; then
  say "  building demo"
  (cd "$DEMO" && mvn -o -q package) || { say "  FATAL: demo build failed"; exit 3; }
fi
say "  demo          $(wc -c < "$DEMO/target/auxin-demo.jar" | tr -d ' ') bytes  sha256:$(sha "$DEMO/target/auxin-demo.jar")"
say "  jacoco agent  $(wc -c < "$JACOCO_AGENT" | tr -d ' ') bytes  sha256:$(sha "$JACOCO_AGENT")"
say "  otel agent    $(wc -c < "$OTEL_AGENT" | tr -d ' ') bytes  sha256:$(sha "$OTEL_AGENT")"

JAVAC="$JDK17/bin/javac"
JAR="$JDK17/bin/jar"

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"

# --release 8: the harness has to load next to demo classes at class-file 55 or 61,
# on a JDK 11, 17 or 21 runtime. Everything it touches goes through reflection.
"$JAVAC" -nowarn --release 8 -d "$OUT/classes" \
    "$G5/src/g5obs/OrderObserver.java" "$G5/src/g5/Harness.java" || exit 3
cat > "$OUT/g5-agent.mf" <<'EOF'
Premain-Class: g5obs.OrderObserver
Agent-Class: g5obs.OrderObserver
Can-Retransform-Classes: true
Can-Redefine-Classes: true
Implementation-Title: g5-order-observer
EOF
"$JAR" cfm "$OUT/g5-observer.jar" "$OUT/g5-agent.mf" -C "$OUT/classes" . || exit 3
say "  g5-observer   built (passive 4th agent: OBS-INCAPABLE + OBS-CAPABLE)"

# ---- G5-BUG-1 is FIXED; the workaround is retired --------------------------------------
# This block used to compile a one-prefix-narrower IgnoreList from g5/src/patch/ and splice it
# into a copy of the shipped jar, because the shipped IgnoreList vetoed every class under
# io/auxin/ -- silently including the demo app. ax-agent now derives its own package
# (IgnoreRules) instead of claiming the whole prefix, so the SHIPPED jar is used everywhere and
# the `shipped-gt-jar` config is now a POSITIVE regression guard: it asserts the unpatched jar
# instruments this application and is never silently idle.
#
# Do not reintroduce a patch-and-substitute here. It was inert after the rename only by luck --
# had the class kept the name `IgnoreList`, `jar uf` would have overwritten the FIXED class with
# the stale copy and every transform would have hit NoSuchMethodError.
cp "$AX_SHIPPED" "$OUT/ax-agent-g5patched.jar"
say "  ax-agent      shipped jar used unpatched  sha256:$(sha "$AX_SHIPPED")  (G5-BUG-1 fixed)"

# ---- JDK 11 needs a class-file-55 build of the demo (the module targets release 17) ------
if [ ! -d "$OUT/demo11/classes" ] || [ -n "${FORCE_DEMO11:-}" ]; then
  rm -rf "$OUT/demo11"; mkdir -p "$OUT/demo11/classes"
  "$JAVAC" -nowarn --release 11 -parameters -g -d "$OUT/demo11/classes" \
      $(find "$DEMO/src/main/java" -name '*.java') || exit 3
fi
say "  demo (rel 11) out/demo11/classes  (class-file 55, for the JDK 11 runs)"

# ---- manifests (stand-in for ax-static; ManifestTool ships inside the agent jar) ---------
"$JDK17/bin/java" -cp "$AX_SHIPPED" \
    io.auxin.agent.manifest.ManifestTool \
    "$DEMO/target/classes" "$OUT/demo-manifest.json" \
    --artifact=auxin-demo --buildSha=g5demo >/dev/null || exit 3
"$JDK17/bin/java" -cp "$AX_SHIPPED" \
    io.auxin.agent.manifest.ManifestTool \
    "$OUT/demo11/classes" "$OUT/demo11-manifest.json" \
    --artifact=auxin-demo --buildSha=g5demo >/dev/null || exit 3
say "  manifests     demo-manifest.json + demo11-manifest.json"

# =============================================================================
# 2. one run
# =============================================================================
# run_one <name> <jdk-home> <jdk-label> <agent-order csv> <obs-position> <flags>
#   flags: "shipped"  -> use the unpatched, shipped ax-agent jar (G5-BUG-1 evidence)
#          "gtfirst"  -> ax-agent is registered ahead of JaCoCo (G5-BUG-2 / G5-BUG-3)
run_one() {
  local name="$1" home="$2" label="$3" order="$4" obspos="$5" flags="${6:-}"
  local shipped=""; case "$flags" in *shipped*) shipped=1 ;; esac
  local gtfirst=""; case "$flags" in *gtfirst*) gtfirst=1 ;; esac
  local rundir="$OUT/runs/$label/$name"
  rm -rf "$rundir"; mkdir -p "$rundir"

  local cp mf classfiles
  if [ "$label" = "11" ]; then
    cp="$OUT/demo11/classes"; mf="$OUT/demo11-manifest.json"; classfiles="$OUT/demo11/classes"
  else
    cp="$DEMO/target/auxin-demo.jar"; mf="$OUT/demo-manifest.json"
    classfiles="$DEMO/target/classes"
  fi

  local gtjar="$OUT/ax-agent-g5patched.jar"
  [ -n "$shipped" ] && gtjar="$AX_SHIPPED"

  local -a cmd=("$home/bin/java")
  local agents=""
  local IFS=,
  for a in $order; do
    case "$a" in
      jacoco)
        cmd+=("-javaagent:$JACOCO_AGENT=includes=io.auxin.demo.*,output=file,destfile=$rundir/jacoco-exit.exec,dumponexit=true,sessionid=g5-$name")
        agents="$agents,jacoco" ;;
      gt)
        cmd+=("-javaagent:$gtjar")
        agents="$agents,gt" ;;
      otel)
        cmd+=("-javaagent:$OTEL_AGENT")
        agents="$agents,otel" ;;
      obs)
        cmd+=("-javaagent:$OUT/g5-observer.jar")
        agents="$agents,obs" ;;
    esac
  done
  unset IFS

  cmd+=(-Dax.include.packages=io.auxin.demo
        "-Dax.manifest=$mf"
        -Dax.environment=production
        -Dax.transport.enabled=false
        -Dax.flush.interval.ms=600000
        -Dax.drain.interval.ms=200
        -Dax.log.level=info
        -Dotel.service.name=g5-demo
        -Dotel.traces.exporter=logging
        -Dotel.metrics.exporter=none
        -Dotel.logs.exporter=none
        -Dotel.javaagent.logging=simple
        -Dotel.bsp.schedule.delay=200
        "-Dg5.dump.dir=$rundir/dump"
        -cp "$cp:$OUT/classes"
        g5.Harness "--out=$rundir" "${DEMO_ARGS[@]}")

  printf '%s\n' "${cmd[@]}" > "$rundir/cmdline.txt"
  "${cmd[@]}" > "$rundir/stdout.log" 2> "$rundir/stderr.log"
  local rc=$?

  local extra=""
  [ -n "$shipped" ] && extra="$extra --expect-gt-zero"
  [ -n "$gtfirst" ] && extra="$extra --gt-before-jacoco"
  python3 "$G5/check.py" --run "$rundir" --name "$name" --jdk "$label" \
      --agents "${agents#,}" --obs-position "$obspos" \
      --ground-truth "$DEMO/ground-truth.json" --manifest "$mf" \
      --classfiles "$classfiles" --jacoco-cli "$JACOCO_CLI" --java "$JDK17/bin/java" \
      --exit-code "$rc" $extra > "$rundir/check.out" 2>&1

  printf '\n  \033[1m%s\033[0m  [jdk %s]  agents: %s\n' "$name" "$label" "$order"
  while IFS=$'\t' read -r status aname detail; do
    case "$status" in
      PASS) PASS=$((PASS+1)); printf '    \033[32mPASS\033[0m  %-52s %s\n' "$aname" "$detail" ;;
      FAIL) FAIL=$((FAIL+1)); printf '    \033[31mFAIL\033[0m  %-52s %s\n' "$aname" "$detail"
            FAILED_LINES="$FAILED_LINES[jdk $label/$name] $aname -- $detail\n" ;;
      INFO) INFO=$((INFO+1)); printf '    \033[36mINFO\033[0m  %-52s %s\n' "$aname" "$detail"
            INFO_LINES="$INFO_LINES[jdk $label/$name] $aname = $detail\n" ;;
      *)    [ -n "${status:-}" ] && printf '          %s\n' "$status$aname$detail" ;;
    esac
  done < "$rundir/check.out"
}

# =============================================================================
# 3. the matrix
# =============================================================================
for label in ${JDKS//,/ }; do
  case "$label" in
    11) home="$JDK11" ;;
    17) home="$JDK17" ;;
    21) home="$JDK21" ;;
    *) say "unknown jdk label $label"; exit 2 ;;
  esac
  if [ ! -x "$home/bin/java" ]; then
    say "SKIPPING JDK $label: $home not present"
    continue
  fi

  hdr "JDK $label  --  $("$home/bin/java" -version 2>&1 | head -1)"

  # --- attribution: one agent at a time, then pairs ---
  run_one "gt-only"        "$home" "$label" "gt,obs"           last
  run_one "jacoco-only"    "$home" "$label" "jacoco,obs"       last
  run_one "otel-only"      "$home" "$label" "otel,obs"         last
  run_one "gt+jacoco"      "$home" "$label" "jacoco,gt,obs"    last
  run_one "gt+otel"        "$home" "$label" "gt,otel,obs"      last
  run_one "jacoco+otel"    "$home" "$label" "jacoco,otel,obs"  last

  # --- THE GATE: all three, both -javaagent orderings, and with no 4th agent at all ---
  run_one "all3-A"         "$home" "$label" "jacoco,gt,otel,obs" last
  run_one "all3-B-reversed" "$home" "$label" "obs,otel,gt,jacoco" first gtfirst
  run_one "all3-C-gt-first" "$home" "$label" "gt,jacoco,otel,obs" last gtfirst
  run_one "all3-no-observer" "$home" "$label" "jacoco,gt,otel"    none

  # --- evidence: the SHIPPED agent jar, unpatched, against this same app ---
  run_one "gt-before-jacoco" "$home" "$label" "gt,jacoco,obs"  last gtfirst
  run_one "shipped-gt-jar" "$home" "$label" "gt,obs"           last shipped
done

# =============================================================================
# 4. summary
# =============================================================================
hdr "G5 SUMMARY"
say "  ax-agent jar  sha256:$GT_JAR_SHA  (snapshot; every run in this matrix used it)"
GT_MODULE_SHA_AT_END="$(sha "$AGENT_MODULE/target/ax-agent.jar")"
if [ "$GT_MODULE_SHA_AT_START" != "$GT_MODULE_SHA_AT_END" ]; then
  # Not a failure -- the snapshot kept this matrix self-consistent -- but the reader must know
  # the module moved, because these results describe the snapshot and not what is on disk now.
  INFO_LINES="$INFO_LINES[harness] HARNESS.moduleJarRebuiltDuringRun = $GT_MODULE_SHA_AT_START -> $GT_MODULE_SHA_AT_END (results describe the snapshot $GT_JAR_SHA)\n"
  INFO=$((INFO+1))
  say "  NOTE          modules/ax-agent/target/ax-agent.jar was rebuilt during this run"
  say "                ($GT_MODULE_SHA_AT_START -> $GT_MODULE_SHA_AT_END); the matrix used the snapshot."
fi
say "  jacoco agent  org.jacoco:org.jacoco.agent:$JACOCO_VERSION:runtime"
say "  otel agent    io.opentelemetry.javaagent:opentelemetry-javaagent:$OTEL_VERSION"
say "  jdks          $JDKS"
say "  artifacts     $OUT/runs/<jdk>/<config>/"
say ""
say "  PASS $PASS    FAIL $FAIL    INFO $INFO"

if [ -n "$INFO_LINES" ]; then
  hdr "RECORDED (not gated) -- see RESULTS.md"
  printf "  %b" "$INFO_LINES" | sed 's/^\[/  [/'
fi

if [ "$FAIL" -gt 0 ]; then
  hdr "FAILURES"
  printf "  %b" "$FAILED_LINES" | sed 's/^\[/  [/' 
  printf '\n\033[31m==== G5 FAILED: %d assertion(s) ====\033[0m\n' "$FAIL"
  exit 1
fi

printf '\n\033[32m==== G5 PASSED: %d assertions, %d recorded observations ====\033[0m\n' "$PASS" "$INFO"
