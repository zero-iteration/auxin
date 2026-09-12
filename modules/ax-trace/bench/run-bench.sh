#!/usr/bin/env bash
# THE GATE: what a request that is NOT being traced pays.
#
# Runs the JMH suite in this directory against the SHIPPED ax-trace.jar (not a copy of the
# runtime in the bench module), at 1, 4 and 10 threads, with the allocation profiler on.
#
# HAZARD (docs/TOOLCHAIN.md #2): this runs on aarch64 (Apple M-series, 128-byte cache line).
# Production is almost certainly x86_64 Linux (64-byte line, TSO). A volatile int read is an
# `ldar` here and a plain `mov` there, so the gate numbers are if anything PESSIMISTIC -- but
# they are provisional until re-run on x86_64 Linux.
set -uo pipefail

BENCH="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$BENCH/.." && pwd)"
AGENT="$MODULE/target/ax-trace.jar"
JAR="$BENCH/target/ax-trace-bench.jar"
OUT="${AX_TRACE_BENCH_OUT:-$BENCH/results}"
: "${JAVA_HOME:=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
THREADS="${AX_TRACE_BENCH_THREADS:-1 4 10}"

mkdir -p "$OUT"

if [ ! -f "$AGENT" ]; then
  echo "FATAL: the shipped jar is not built: $AGENT" >&2
  echo "       build it with: (cd $MODULE && mvn -q clean package)" >&2
  echo "       The bench measures the SHIPPED runtime on purpose; there is no fallback." >&2
  exit 3
fi
if [ ! -f "$JAR" ]; then
  echo "building the bench..." >&2
  (cd "$BENCH" && mvn -o -q clean package) || exit 3
fi

echo "java    : $("$JAVA" -version 2>&1 | head -1)"
echo "arch    : $(uname -m)  cores=$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
echo "agent   : $AGENT"
echo "output  : $OUT"

for t in $THREADS; do
  echo
  echo ">>> threads=$t"
  "$JAVA" -cp "$JAR:$AGENT" org.openjdk.jmh.Main \
      'io.auxin.trace.bench.UntracedPathBenchmark' \
      -t "$t" -prof gc \
      -rf json -rff "$OUT/untraced-t$t.json" \
      2>&1 | tee "$OUT/untraced-t$t.log" | grep -E '^(Benchmark|io\.auxin|# Run complete)' || true
done

echo
echo "=============================================================="
echo "PER-SITE COST (the claim in TRADE-OFFS.md)"
echo "=============================================================="
python3 - "$OUT" $THREADS <<'PY'
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
    path = os.path.join(out, "untraced-t%s.json" % t)
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
PY
echo
echo "  * = absolute ns/op; the arm calls one probe and no baseline work, so nothing is subtracted."
echo
echo "If the untraced per-site figure is not ~1ns, or B/op is not 0 on the untraced arms,"
echo "say so in TRADE-OFFS.md rather than shipping it."
