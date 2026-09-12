#!/usr/bin/env bash
# auxin benchmark gates G1-G4.
#
# HAZARD (docs/TOOLCHAIN.md #2): this runs on aarch64 (Apple M-series, 128-byte cache line,
# mach_absolute_time). Production is almost certainly x86_64 Linux (64-byte cache line,
# vDSO/TSC clock, TSO memory model). False-sharing and clock numbers DO NOT TRANSFER.
# Every number produced here is PROVISIONAL until re-run on x86_64 Linux.
set -uo pipefail

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$BENCH_DIR/target/ax-bench.jar"
OUT="${GT_BENCH_OUT:-$BENCH_DIR/results}"
: "${JAVA_HOME:=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
# G3 is run once per JDK listed here (colon-separated JAVA_HOMEs). Only JDK 17 is installed locally.
: "${AX_BENCH_JDKS:=$JAVA_HOME}"
G4_CLASSES="${GT_BENCH_G4_CLASSES:-5000}"
THREADS="${GT_BENCH_THREADS:-1 4 10}"

mkdir -p "$OUT"
echo "bench dir : $BENCH_DIR"
echo "java      : $($JAVA -version 2>&1 | head -1)"
echo "arch      : $(uname -m)  cores=$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
echo "output    : $OUT"

run() { echo; echo ">>> $*"; "$@"; }

case "${1:-all}" in
  g1)  GATES="g1" ;;
  g2)  GATES="g2" ;;
  g3)  GATES="g3" ;;
  g4)  GATES="g4" ;;
  g6)  GATES="g6" ;;
  g7)  GATES="g7" ;;
  *)   GATES="g1 g2 g3 g4 g6 g7" ;;
esac

# G6 measures the SHIPPED agent runtime rather than a copy of it, so it needs the agent jar on
# the classpath next to the bench jar.
AGENT="$BENCH_DIR/../modules/ax-agent/target/ax-agent.jar"
G6_CP="$JAR:$AGENT"

for G in $GATES; do
case $G in

g1)
  echo; echo "############ G1 - probe pattern shootout ############"
  run "$JAVA" -cp "$JAR" ax.bench.g1.ArmSelfCheck | tee "$OUT/g1-selfcheck.md"
  run "$JAVA" -cp "$JAR" ax.bench.g1.ClassSizeReport | tee "$OUT/g1-size.md"
  for T in $THREADS; do
    echo; echo ">>> G1 JMH at $T thread(s)"
    "$JAVA" -jar "$JAR" 'ax\.bench\.g1\.ProbeBench' \
      -t "$T" -rf json -rff "$OUT/g1-t$T.json" 2>&1 | tee "$OUT/g1-t$T.log"
  done
  ;;

g2)
  echo; echo "############ G2 - inlining regression ############"
  run "$JAVA" -cp "$JAR" ax.bench.g2.InliningDiff 400000 2>&1 | tee "$OUT/g2-inlining.md"
  for T in 1 10; do
    echo; echo ">>> G2 JMH at $T thread(s)"
    "$JAVA" -jar "$JAR" 'ax\.bench\.g2\.G2Bench' \
      -t "$T" -rf json -rff "$OUT/g2-t$T.json" 2>&1 | tee "$OUT/g2-t$T.log"
  done
  ;;

g3)
  echo; echo "############ G3 - condy install/strip correctness ############"
  : > "$OUT/g3.log"
  IFS=':' read -ra HOMES <<< "$AX_BENCH_JDKS"
  for H in "${HOMES[@]}"; do
    [ -x "$H/bin/java" ] || { echo "SKIP (no java): $H" | tee -a "$OUT/g3.log"; continue; }
    echo; echo ">>> G3 under $H"
    "$H/bin/java" -javaagent:"$JAR"=g3 -cp "$JAR" ax.bench.g3.G3Main 2>&1 | tee -a "$OUT/g3.log"
    echo "exit=$?" | tee -a "$OUT/g3.log"
  done
  ;;

g6)
  echo; echo "############ G6 - sampled runtime call-edge tier (SCOPE-v3) ############"
  if [ ! -f "$AGENT" ]; then
    echo "MISSING $AGENT"
    echo "G6 benchmarks the shipped agent runtime. Build it first:"
    echo "    mvn -q -f $BENCH_DIR/../modules/ax-agent/pom.xml clean package"
    exit 2
  fi
  # FIRST: prove every arm records exactly what its name claims. A benchmark of instrumentation
  # that silently records nothing reports precisely the number we want to see.
  run "$JAVA" -cp "$G6_CP" ax.bench.g6.EdgeSelfCheck | tee "$OUT/g6-selfcheck.md"
  for T in $THREADS; do
    echo; echo ">>> G6 JMH at $T thread(s)"
    "$JAVA" -cp "$G6_CP" org.openjdk.jmh.Main 'ax\.bench\.g6\.EdgeBench' \
      -t "$T" -rf json -rff "$OUT/g6-t$T.json" 2>&1 | tee "$OUT/g6-t$T.log"
  done
  # The zero-allocation invariant is an assertion, so it gets measured too: gc.alloc.rate.norm
  # must be 0 B/op on the sampled path, not just on the unsampled one.
  echo; echo ">>> G6 allocation profile (gc.alloc.rate.norm must be 0 B/op)"
  "$JAVA" -cp "$G6_CP" org.openjdk.jmh.Main \
    'ax\.bench\.g6\.EdgeBench\.(a0_baseline|a2_unsampled|a4_sampledEveryRoot)' \
    -t 4 -f 1 -wi 2 -i 3 -prof gc 2>&1 | tee "$OUT/g6-alloc.log"
  ;;

g7)
  echo; echo "############ G7 - the UNTRACED path (SCOPE-v3.1) ############"
  # MIGRATED from modules/ax-trace/bench/run-bench.sh, which measured the tracer while it was a
  # separate -javaagent. It measures the SHIPPED runtime out of ax-agent.jar for the same reason
  # G6 does: whatever HotSpot decides about inlining the code that actually ships is what the
  # gate reports -- and the merge is exactly the kind of change that could move that decision,
  # so the number is RE-MEASURED here rather than carried over.
  if [ ! -f "$AGENT" ]; then
    echo "MISSING $AGENT"
    echo "G7 benchmarks the shipped agent runtime. Build it first:"
    echo "    mvn -q -f $BENCH_DIR/../modules/ax-agent/pom.xml clean package"
    exit 2
  fi
  for T in $THREADS; do
    echo; echo ">>> G7 JMH at $T thread(s)"
    "$JAVA" -cp "$G6_CP" org.openjdk.jmh.Main 'ax\.bench\.g7\.UntracedPathBenchmark' \
      -t "$T" -prof gc -rf json -rff "$OUT/g7-t$T.json" 2>&1 | tee "$OUT/g7-t$T.log" \
      | grep -E '^(Benchmark|ax\.bench|# Run complete)' || true
  done
  echo
  echo "=============================================================="
  echo "PER-SITE COST (the claim in modules/ax-agent/docs/TRADE-OFFS.md)"
  echo "=============================================================="
  python3 - "$OUT" $THREADS <<'G7PY'
import json, os, sys

out = sys.argv[1]
# (probe sites, does the arm include the baseline work?)
sites = {
    "baseline": (0, True),
    "enterExit": (2, True),
    "fullProbeSet": (6, True),
    "fullProbeSetWhileAnotherThreadTraces": (6, True),
    # These two call ONE probe and nothing else, so their ns/op IS the per-site cost -- there is
    # no baseline to subtract, and subtracting one would report a large negative number.
    "wrapUntraced": (1, False),
    "obsRefOnly": (1, False),
}
for t in sys.argv[2:]:
    path = os.path.join(out, "g7-t%s.json" % t)
    if not os.path.exists(path):
        print("  (no result for threads=%s)" % t)
        continue
    rows = json.load(open(path))
    by = {}
    for r in rows:
        name = r["benchmark"].rsplit(".", 1)[1]
        by[name] = r
    base = by.get("baseline")
    print()
    print("threads=%s" % t)
    print("  %-42s %12s %10s %14s %12s" % ("arm", "ns/op", "error", "ns/site", "B/op"))
    for name in ("baseline", "enterExit", "fullProbeSet", "obsRefOnly", "wrapUntraced",
                 "fullProbeSetWhileAnotherThreadTraces"):
        r = by.get(name)
        if r is None:
            continue
        score = r["primaryMetric"]["score"]
        err = r["primaryMetric"]["scoreError"]
        n, relative = sites.get(name, (0, True))
        alloc = r.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
        persite = ""
        if n and relative and base is not None:
            persite = "%.3f" % ((score - base["primaryMetric"]["score"]) / n)
        elif n:
            persite = "%.3f*" % (score / n)
        print("  %-42s %12.3f %10.3f %14s %12s"
              % (name, score, 0.0 if err != err else err, persite,
                 "n/a" if alloc is None else "%.2f" % alloc))
G7PY
  echo
  echo "  * = absolute ns/op; the arm calls one probe and no baseline work, so nothing is subtracted."
  echo
  echo "If the untraced per-site figure is not ~1ns, or B/op is not 0 on the untraced arms,"
  echo "say so in modules/ax-agent/docs/TRADE-OFFS.md rather than shipping it."
  ;;

g4)
  echo; echo "############ G4 - startup cost ############"
  rm -rf "$OUT/g4-classes"
  run "$JAVA" -cp "$JAR" ax.bench.g4.ClassGen "$OUT/g4-classes" "$G4_CLASSES"
  run "$JAVA" -cp "$JAR" ax.bench.g4.G4Main "$JAR" "$OUT/g4-classes" "$G4_CLASSES" 5 \
    2>&1 | tee "$OUT/g4.md"
  ;;
esac
done

echo
echo "done. raw output in $OUT"
