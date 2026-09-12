#!/usr/bin/env bash
# =============================================================================
# demo/run.sh — build (if needed) and run the integration target
# =============================================================================
# Application arguments are passed straight through. JVM arguments — which is how you
# attach agents — come from $JVM_ARGS:
#
#   ./demo/run.sh --seconds=30 --threads=8
#
#   JVM_ARGS="-javaagent:/path/ax-agent.jar=artifact=demo" ./demo/run.sh --seconds=60
#
#   # gate G5, all three agents at once (ordering is decided by capability, not by
#   # flag order: incapable transformers run first, so JaCoCo and ax-agent see the
#   # class before OTel does)
#   JVM_ARGS="-javaagent:jacocoagent.jar=includes=io.auxin.demo.*,output=tcpserver \
#             -javaagent:ax-agent.jar=artifact=demo,include=io.auxin.demo. \
#             -javaagent:opentelemetry-javaagent.jar" \
#   OTEL_TRACES_EXPORTER=logging ./demo/run.sh --seconds=120 --threads=8
#
# Two-of-three firing is a FAILURE, not a partial pass: the OTel/SkyWalking conflict was
# invisible until the first request triggered a retransform.
# =============================================================================
set -euo pipefail

DEMO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DEMO/target/auxin-demo.jar"

JAVA_BIN="${JAVA_BIN:-java}"
if [ "$JAVA_BIN" = "java" ] && [ -x "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java" ]; then
  JAVA_BIN="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java"
fi

if [ ! -f "$JAR" ] || [ -n "${FORCE_BUILD:-}" ]; then
  echo "[run.sh] building (offline)"
  ( cd "$DEMO" && mvn -o -q package )
fi

echo "[run.sh] $JAVA_BIN ${JVM_ARGS:-} -jar $JAR $*"
# shellcheck disable=SC2086
exec "$JAVA_BIN" ${JVM_ARGS:-} -jar "$JAR" "$@"
