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
  *)   GATES="g1 g2 g3 g4" ;;
esac

for G in $GATES; do
case $G in

g1)
  echo; echo "############ G1 - probe pattern shootout ############"
  run "$JAVA" -cp "$JAR" ax.bench.g1.ArmSelfCheck | tee "$OUT/g1-selfcheck.md"
  run "$JAVA" -cp "$JAR" ax.bench.g1.ClassSizeReport | tee "$OUT/g1-size.md"
  for T in $THREADS; do
    echo; echo ">>> G1 JMH at $T thread(s)"
    "$JAVA" -jar "$JAR" 'gt\.bench\.g1\.ProbeBench' \
      -t "$T" -rf json -rff "$OUT/g1-t$T.json" 2>&1 | tee "$OUT/g1-t$T.log"
  done
  ;;

g2)
  echo; echo "############ G2 - inlining regression ############"
  run "$JAVA" -cp "$JAR" ax.bench.g2.InliningDiff 400000 2>&1 | tee "$OUT/g2-inlining.md"
  for T in 1 10; do
    echo; echo ">>> G2 JMH at $T thread(s)"
    "$JAVA" -jar "$JAR" 'gt\.bench\.g2\.G2Bench' \
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
