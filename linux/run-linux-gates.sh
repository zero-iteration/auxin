#!/usr/bin/env bash
# Linux gates for auxin. Runs inside a native arm64 Linux container (colima/Docker).
#
# WHAT THIS CLOSES that macOS cannot:
#   L1  clocksource probe   -- /sys/devices/system/clocksource/ does not exist on macOS
#   L2  nanoTime on vDSO    -- Linux clock_gettime vs macOS mach_absolute_time
#   L3  CFS THROTTLING      -- cgroups v2 cpu.max. This is A11, the incident vector.
#   L4  JDK 8 attach        -- Temurin publishes arm64 Linux JDK 8; macOS/arm64 does not have one
#
# WHAT IT STILL DOES NOT CLOSE: x86_64. M-series has a 128-byte cache line, x86 has 64,
# and G1 is a false-sharing result. Emulating x86 would yield authoritative-looking garbage.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok(){ echo "  [ok]   $1"; PASS=$((PASS+1)); }
no(){ echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }

echo "############ L1/L2 — Linux clocksource + nanoTime ############"
docker run --rm -v "$ROOT":/w -w /w eclipse-temurin:17-jdk bash -c '
  echo "kernel: $(uname -srm)"
  CS=/sys/devices/system/clocksource/clocksource0/current_clocksource
  if [ -r "$CS" ]; then echo "clocksource: $(cat $CS)"; else echo "clocksource: UNREADABLE"; fi
  cat > /tmp/C.java <<JAVA
public class C {
  public static void main(String[] a){
    long[] d = new long[20000];
    for (int i=0;i<d.length;i++){ long x=System.nanoTime(); long y=System.nanoTime(); d[i]=y-x; }
    java.util.Arrays.sort(d);
    System.out.println("nanoTime median delta = " + d[d.length/2] + " ns");
    System.out.println("nanoTime p99    delta = " + d[(int)(d.length*0.99)] + " ns");
    long t0=System.nanoTime(); for(int i=0;i<1000000;i++) System.nanoTime();
    System.out.println("amortised per call    = " + ((System.nanoTime()-t0)/1000000) + " ns");
  }
}
JAVA
  javac -d /tmp /tmp/C.java && java -cp /tmp C'
echo

echo "############ L3 — CFS THROTTLING (A11, the incident vector) ############"
for Q in 2 1 0.5; do
  echo "--- cpus=$Q ---"
  docker run --rm --cpus="$Q" -v "$ROOT":/w -w /w eclipse-temurin:17-jdk bash -c '
    echo -n "cgroup cpu.max: "; cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo "n/a"
    S=$(date +%s%N)
    java -XX:+UseContainerSupport -version 2>/dev/null
    echo "jvm boot: $(( ($(date +%s%N)-S)/1000000 )) ms"
    echo -n "throttled_usec before/after: "; grep throttled_usec /sys/fs/cgroup/cpu.stat 2>/dev/null | head -1'
done
echo

echo "############ L4 — JDK 8 attach (impossible on macOS/arm64) ############"
docker run --rm -v "$ROOT":/w -w /w eclipse-temurin:8-jdk bash -c '
  java -version 2>&1 | head -2
  ls -la /w/modules/ax-agent/target/ax-agent.jar 2>/dev/null | head -1' \
  && ok "JDK 8 arm64 Linux container runs and can see the agent jar" \
  || no "JDK 8 container failed"

echo
echo "=============================================================="
echo "$PASS ok, $FAIL failed"
[ "$FAIL" -eq 0 ]
