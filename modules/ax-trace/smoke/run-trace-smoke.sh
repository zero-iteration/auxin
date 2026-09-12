#!/usr/bin/env bash
# END-TO-END SMOKE TEST for the auxin per-request tracer (modules/ax-trace).
#
#   build ax-trace -> compile a target app with a SERVLET-SHAPED ENTRY (real javax.servlet
#   descriptors, via stub interfaces so no third-party jar enters the build), a thread pool, a
#   parallelStream, a flag branch, a throw and a collection that shrinks in place
#   -> run it under -javaagent nine times (inert / full / rate cap / token / production-no-token
#      / Tier-1b conflict refuse / Tier-1b conflict disable-strip / no-parallelstream-window /
#      fail-open) against a REAL collector on POST /v1/trace
#   -> assert the trace documents with python3 and the emitted bytecode with javap
#
# Every assertion prints PASS or FAIL. Exits non-zero on the first failing assertion set.
#
# In the style of modules/ax-agent/smoke/run-smoke.sh, and deliberately NOT sharing anything
# with it: this module must be verifiable on its own.
set -uo pipefail

MODULE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$MODULE/smoke/out"
: "${JAVA_HOME:=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
JAVAP="$JAVA_HOME/bin/javap"
AGENT="$MODULE/target/ax-trace.jar"
AXAGENT="$MODULE/../ax-agent/target/ax-agent.jar"

fails=0
checks=0
ok()   { checks=$((checks+1)); echo "  PASS  $1"; }
bad()  { checks=$((checks+1)); fails=$((fails+1)); echo "  FAIL  $1"; }
check(){ checks=$((checks+1))                        # check <what> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "  PASS  $1"
  else echo "  FAIL  $1 (expected '$2', got '$3')"; fails=$((fails+1)); fi
}

echo "== 0. environment =="
echo "  java  : $("$JAVA" -version 2>&1 | head -1)"
echo "  arch  : $(uname -m)  cores=$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
echo "  module: $MODULE"

echo
echo "== 1. build the tracer =="
(cd "$MODULE" && mvn -o -q clean package) || { echo "FATAL: mvn package failed" >&2; exit 3; }
ls -l "$AGENT"
# Zero third-party runtime deps beyond shaded ASM: asserted, not claimed.
FOREIGN="$(unzip -Z1 "$AGENT" '*.class' \
  | grep -v '^io/auxin/trace/' | grep -v '^module-info' || true)"
if [ -z "$FOREIGN" ]; then ok "the shipped jar contains ONLY io/auxin/trace/** classes"
else bad "the shipped jar contains foreign classes: $(echo "$FOREIGN" | head -3 | tr '\n' ' ')"; fi
ASMCOUNT="$(unzip -Z1 "$AGENT" 'io/auxin/trace/shaded/asm/*.class' | wc -l | tr -d ' ')"
if [ "$ASMCOUNT" -gt 10 ]; then ok "ASM is shaded into io/auxin/trace/shaded/asm ($ASMCOUNT classes)"
else bad "ASM does not appear to be shaded (found $ASMCOUNT classes)"; fi
if unzip -p "$AGENT" META-INF/MANIFEST.MF | grep -q 'Can-Retransform-Classes: false'; then
  ok "the manifest declares Can-Retransform-Classes: false (a trace probe is never stripped)"
else bad "the manifest should declare Can-Retransform-Classes: false"; fi

echo
echo "== 2. compile the target app (--release 8, so class file 52 on every JDK) =="
rm -rf "$OUT"
mkdir -p "$OUT/classes"
"$JAVAC" -nowarn -g --release 8 -d "$OUT/classes" \
    "$MODULE/smoke/src/javax/servlet/ServletRequest.java" \
    "$MODULE/smoke/src/javax/servlet/ServletResponse.java" \
    "$MODULE/smoke/src/javax/servlet/FilterChain.java" \
    "$MODULE/smoke/src/javax/servlet/Filter.java" \
    "$MODULE/smoke/src/javax/servlet/http/HttpServletRequest.java" \
    "$MODULE/smoke/src/javax/servlet/http/HttpServletResponse.java" \
    "$MODULE/smoke/src/traceapp/Fare.java" \
    "$MODULE/smoke/src/traceapp/SearchService.java" \
    "$MODULE/smoke/src/traceapp/SearchFilter.java" \
    "$MODULE/smoke/src/traceapp/FakeRequest.java" \
    "$MODULE/smoke/src/tracerun/TraceApp.java" 2>&1 | grep -v '^Note' || true
if [ ! -f "$OUT/classes/traceapp/SearchFilter.class" ]; then
  echo "FATAL: the target app did not compile" >&2; exit 3
fi
CFVER="$("$JAVAP" -v "$OUT/classes/traceapp/SearchService.class" | awk '/major/{print $3}')"
check "the target app is class file 52 (>= 51, so the throw handler's frame is safe)" "52" "$CFVER"
# The entry match is a DESCRIPTOR match, so the stub must produce the real servlet descriptor.
if "$JAVAP" -p "$OUT/classes/traceapp/SearchFilter.class" | grep -q \
   'void doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)'; then
  ok "the target filter has the real Filter#doFilter signature"
else bad "the target filter does not have the Filter#doFilter signature"; fi

free_port() {
  python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'
}

# run <scenario> <recv-subdir> [extra -D...]
run() {
  local scenario="$1"; shift
  local recv="$1"; shift
  local port; port="$(free_port)"
  rm -rf "$OUT/$recv"
  mkdir -p "$OUT/$recv"
  "$JAVA" -javaagent:"$AGENT" \
      -Dax.trace.include.packages=traceapp \
      -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
      -Dax.trace.projection="$MODULE/smoke/auxin-trace-projection.properties" \
      -Dax.trace.log.level=info \
      -Dtrace.smoke.scenario="$scenario" \
      -Dtrace.smoke.recv="$OUT/$recv" \
      -Dtrace.smoke.port="$port" \
      "$@" \
      -cp "$OUT/classes" tracerun.TraceApp
}

tally() { # tally <label> <app exit code>
  checks=$((checks+1))
  if [ "$2" = "0" ]; then echo "  PASS  $1 (in-JVM assertions)"
  else echo "  FAIL  $1 (in-JVM assertions, exit=$2)"; fails=$((fails+1)); fi
}

echo
echo "== 3. RUN A: the MASTER SWITCH is off (the default) -> completely inert =="
run inert recv-inert > "$OUT/log-inert.txt" 2>&1; rc=$?
sed -n '1,40p' "$OUT/log-inert.txt"
tally "RUN A inert" "$rc"
if grep -q 'per-request tracer is OFF' "$OUT/log-inert.txt"; then
  ok "RUN A: the startup line says the tracer is OFF and nothing is instrumented"
else bad "RUN A: expected a startup line saying the tracer is OFF"; fi
check "RUN A: no trace document reached the collector" "0" \
    "$(ls "$OUT/recv-inert" 2>/dev/null | wc -l | tr -d ' ')"

echo
echo "== 4. RUN B: the main scenario -- untraced records nothing, traced records the tree =="
run full recv-full -Dax.trace.enabled=true -Dax.trace.dump.dir="$OUT/dump" \
    > "$OUT/log-full.txt" 2>&1; rc=$?
sed -n '1,60p' "$OUT/log-full.txt"
tally "RUN B full" "$rc"
if grep -q 'TRADE-OFF, stated at startup' "$OUT/log-full.txt"; then
  ok "RUN B: the startup line states the invariant that was given up"
else bad "RUN B: the startup line must state the trade"; fi
if grep -q 'projection REFUSED traceapp.Fare#getPassengerEmail' "$OUT/log-full.txt"; then
  ok "RUN B: the redaction denylist refused Fare#getPassengerEmail at config load, loudly"
else bad "RUN B: getPassengerEmail should have been refused at projection load"; fi

DOC="$OUT/recv-full/trace-1.json"
if [ ! -f "$DOC" ]; then
  echo "  FAIL  RUN B: no trace document arrived on POST /v1/trace"
  fails=$((fails+1)); checks=$((checks+1))
else
  ok "RUN B: a trace document arrived on POST /v1/trace"
  python3 - "$DOC" <<'PY'
import json, sys

doc = json.load(open(sys.argv[1]))
fails = 0
checks = 0

def check(what, cond, detail=""):
    global fails, checks
    checks += 1
    if cond:
        print("  PASS  " + what)
    else:
        print("  FAIL  " + what + (" (" + detail + ")" if detail else ""))
        fails += 1

def walk(frames):
    for f in frames:
        yield f
        for g in walk(f.get("calls", [])):
            yield g

frames = list(walk(doc["frames"]))
raw = open(sys.argv[1]).read()

check("document identifies itself as a trace on schemaVersion 1",
      doc.get("kind") == "trace" and doc.get("schemaVersion") == 1, str(doc.get("kind")))
check("the request path is masked: /search/101?trace=1&pax=jane -> /search/#",
      doc["request"]["path"] == "/search/#", doc["request"]["path"])
check("the query string is absent from the document entirely",
      "pax=" not in raw and "trace=1" not in raw)
check("the frame tree is not flat", len(frames) >= 6, "%d frames" % len(frames))

# ---- PII: the whole point of structural capture ----
for needle in ("@example.test", "passenger", "PassengerEmail", "jane"):
    check("NO '%s' anywhere in the document" % needle, needle not in raw)
check("no observation is named getPassengerEmail",
      all(o.get("name") != "getPassengerEmail"
          for f in frames for o in f.get("in", []) + f.get("out", [])
          for o in [o] + o.get("projected", [])))

# ---- the throw ----
threw = [f for f in frames if f.get("threw")]
check("a frame recorded a throw", len(threw) >= 1)
check("the throw is recorded as its CLASS NAME",
      any(f["threw"] == "java.lang.IllegalStateException" for f in threw),
      str([f.get("threw") for f in threw]))
check("the exception MESSAGE is not in the document (it contained an e-mail address)",
      "leg -1" not in raw)
check("the throwing frame is priceLeg",
      any("priceLeg" in f["frame"] for f in threw), str([f["frame"] for f in threw]))

# ---- the size delta: 126 in, 94 out ----
deltas = [(f["frame"], d) for f in frames for d in f.get("sizeDeltas", [])]
check("a collection-size delta was captured", len(deltas) >= 1)
check("the delta is 126 in / 94 out / -32",
      any(d["in"] == 126 and d["out"] == 94 and d["delta"] == -32 for _, d in deltas),
      str(deltas))
check("the delta is on dropNonRefundable, which mutated its parameter IN PLACE",
      any("dropNonRefundable" in n for n, d in deltas if d["delta"] == -32),
      str([n for n, _ in deltas]))
check("the 126 fares themselves are NOT in the document (sizes only)",
      not any(o.get("kind") == "value" for f in frames
              for o in f.get("in", []) + f.get("out", [])))

# ---- branch arms ----
branches = [(f["frame"], b) for f in frames for b in f.get("branches", [])]
check("branch arms were captured", len(branches) >= 1)
check("the flag branch in select() recorded an arm",
      any("select" in n and b["why"] == "call" for n, b in branches),
      str([(n, b.get("op"), b.get("arm"), b.get("why")) for n, b in branches][:8]))
check("an arm names which side was taken",
      all(b.get("arm") in ("then", "else", "case") for _, b in branches))
check("repeated arms at one site are GROUPED with a count, not listed 126 times",
      any(b.get("n", 1) > 1 for _, b in branches),
      str([b.get("n") for _, b in branches]))
check("no loop-counter branch was recorded (sumStops' `i < n` is two local loads)",
      not any("sumStops" in n for n, _ in branches),
      str([n for n, _ in branches]))
check("no iterator loop header was recorded (`while (it.hasNext())`)",
      not any(b.get("op") == "IFEQ" and b.get("why") == "call" and "dropNonRefundable" in n
              and b.get("n", 1) > 100 for n, b in branches))

# ---- executor propagation ----
viaexec = [f for f in frames if f.get("via") == "executor"]
check("trace context survived ExecutorService.submit", len(viaexec) >= 1,
      str([f["frame"] for f in frames if f.get("via")]))
check("the pool-thread frame is enrich(), recorded under the frame that submitted it",
      any("enrich" in f["frame"] for f in viaexec), str([f["frame"] for f in viaexec]))
check("threadsJoined counts more than the request thread", doc["threadsJoined"] > 1,
      str(doc["threadsJoined"]))

# ---- parallelStream ----
viacp = [f for f in frames if f.get("via") == "commonPool"]
print("  ---- parallelStream: %d frames arrived via the common-pool window, "
      "commonPoolJoins=%s" % (len(viacp), doc.get("commonPoolJoins")))
check("parallelStream work was captured through the common-pool window", len(viacp) >= 1,
      "0 commonPool frames -- the named trap is NOT handled in this run")
check("every common-pool frame belongs to the scoring path (lambda / score / what score calls)",
      all(("score" in f["frame"] or "lambda$" in f["frame"] or "traceapp.Fare#" in f["frame"])
          for f in viacp),
      str(sorted(set(f["frame"] for f in viacp))[:6]))

# ---- projection ----
projected = [p for f in frames for o in f.get("in", []) for p in o.get("projected", [])]
check("the per-service projection fired", len(projected) >= 1)
check("an enum projection recorded name(), not the object",
      any(p["kind"] == "enum" and p["text"] in ("INDIGO", "VISTARA", "AKASA")
          for p in projected), str(projected[:4]))
check("a boolean projection recorded true/false",
      any(p["kind"] == "bool" for p in projected))
check("a numeric projection recorded a number",
      any(p["kind"] == "num" for p in projected))

# ---- volume control ----
check("pass-through frames were collapsed", doc.get("framesCollapsed", 0) >= 1,
      str(doc.get("framesCollapsed")))
check("the collapsed frame's children survived (passThrough is gone, select's calls are not)",
      not any(f["frame"].endswith("passThrough(Ljava/util/List;)Ljava/util/List;")
              for f in frames))
check("the arms-per-frame cap is reported when it bites",
      any(f.get("truncated") for f in frames)
      or doc["health"]["armsTruncated"] + doc["health"]["obsTruncated"] == 0)

# ---- health ----
h = doc["health"]
check("health: exactly one trace started and one completed",
      h["tracesStarted"] == 1 and h["tracesCompleted"] == 1,
      "%s/%s" % (h["tracesStarted"], h["tracesCompleted"]))
check("health: the 5 untraced requests are counted as rejectedNoHeader",
      h["rejectedNoHeader"] >= 5, str(h["rejectedNoHeader"]))
check("health: zero transform failures", h["transformFailures"] == 0)
check("health: zero runtime failures", h["runtimeFailures"] == 0)
check("health: executor call sites were rewritten", h["callSitesWrapped"] >= 1,
      str(h["callSitesWrapped"]))
check("health: exactly one servlet entry was instrumented", h["entriesInstrumented"] == 1,
      str(h["entriesInstrumented"]))

print()
print("  DOC CHECKS %d/%d   (document is %d bytes, %d frames)"
      % (checks - fails, checks, len(raw), len(frames)))
sys.exit(1 if fails else 0)
PY
  rc=$?
  checks=$((checks+1))
  if [ "$rc" = "0" ]; then echo "  PASS  RUN B: trace-document assertions"
  else echo "  FAIL  RUN B: trace-document assertions"; fails=$((fails+1)); fi
fi

echo
echo "== 5. RUN C: the RATE CAP engages =="
run ratecap recv-rate -Dax.trace.enabled=true -Dax.trace.rate.per.minute=2 \
    > "$OUT/log-rate.txt" 2>&1; rc=$?
grep -E '^  (PASS|FAIL)' "$OUT/log-rate.txt" || true
tally "RUN C rate cap" "$rc"
check "RUN C: exactly 2 documents reached the collector from 6 traced requests" "2" \
    "$(ls "$OUT/recv-rate" 2>/dev/null | wc -l | tr -d ' ')"
if [ -f "$OUT/recv-rate/trace-2.json" ]; then
  python3 -c "
import json,sys
h=json.load(open('$OUT/recv-rate/trace-2.json'))['health']
print('  rate cap health: started=%d rejectedRateCap=%d' % (h['tracesStarted'], h['rejectedRateCap']))
sys.exit(0 if h['rejectedRateCap'] >= 3 else 1)" && \
    ok "RUN C: the refused requests are counted as rejectedRateCap" || \
    bad "RUN C: rejectedRateCap should be >= 3"
fi

echo
echo "== 6. RUN D: an UNAUTHENTICATED header in production is refused =="
run prodnotoken recv-prod -Dax.trace.enabled=true -Dax.environment=production \
    > "$OUT/log-prod.txt" 2>&1; rc=$?
grep -E '^  (PASS|FAIL)' "$OUT/log-prod.txt" || true
tally "RUN D production without a token" "$rc"
if grep -q 'REFUSING TO ARM' "$OUT/log-prod.txt" \
   && grep -q 'AMPLIFICATION vector' "$OUT/log-prod.txt"; then
  ok "RUN D: the refusal names both the amplification and the data-exposure vector"
else bad "RUN D: expected a loud refusal naming both vectors"; fi

echo
echo "== 7. RUN E: with a token, a wrong header value is refused and the right one works =="
run token recv-token -Dax.trace.enabled=true -Dax.environment=production \
    -Dax.trace.token=s3cret > "$OUT/log-token.txt" 2>&1; rc=$?
grep -E '^  (PASS|FAIL)' "$OUT/log-token.txt" || true
tally "RUN E token" "$rc"
check "RUN E: exactly 1 document (the two wrong values produced none)" "1" \
    "$(ls "$OUT/recv-token" 2>/dev/null | wc -l | tr -d ' ')"

echo
echo "== 8. RUN F: the TIER-1b MUTUAL EXCLUSION -- refuse (the default) =="
if [ ! -f "$AXAGENT" ]; then
  echo "  SKIP  ax-agent.jar is not built ($AXAGENT); the conflict check needs"
  echo "        io.auxin.agent.AuxinAgent resolvable. Build it with:"
  echo "            (cd $MODULE/../ax-agent && mvn -q clean package)"
  bad "RUN F: Tier-1b conflict detection NOT EXERCISED (ax-agent.jar missing)"
else
  port="$(free_port)"; rm -rf "$OUT/recv-conflict"; mkdir -p "$OUT/recv-conflict"
  "$JAVA" -javaagent:"$AGENT" \
      -Dax.trace.enabled=true \
      -Dax.trace.include.packages=traceapp \
      -Dax.include.packages=traceapp \
      -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
      -Dtrace.smoke.scenario=conflict-refuse \
      -Dtrace.smoke.recv="$OUT/recv-conflict" \
      -Dtrace.smoke.port="$port" \
      -cp "$OUT/classes:$AXAGENT" tracerun.TraceApp > "$OUT/log-conflict.txt" 2>&1
  rc=$?
  grep -E '^  (PASS|FAIL)|TIER-1b|strip.conflict' "$OUT/log-conflict.txt" || true
  tally "RUN F conflict=refuse" "$rc"
  if grep -q 'TIER-1b / TRACE CONFLICT' "$OUT/log-conflict.txt"; then
    ok "RUN F: the conflict is detected and named at startup"
  else bad "RUN F: the Tier-1b conflict was NOT detected"; fi
  if grep -q 'A TRACE PROBE CAN NEVER BE STRIPPED' "$OUT/log-conflict.txt"; then
    ok "RUN F: the message says WHY it is a contradiction, not just that it is one"
  else bad "RUN F: the message must explain why"; fi
  if grep -q 'THE TRACER IS NOT ARMED' "$OUT/log-conflict.txt"; then
    ok "RUN F: the default refuses the NEW thing, leaving ax-agent's promises intact"
  else bad "RUN F: the default must refuse the tracer, not degrade ax-agent"; fi
  if grep -qE 'ax.trace.strip.conflict=(disable-strip|allow)' "$OUT/log-conflict.txt"; then
    ok "RUN F: the message lists the ways out"
  else bad "RUN F: the message must list the ways out"; fi
  check "RUN F: no document was produced" "0" \
      "$(ls "$OUT/recv-conflict" 2>/dev/null | wc -l | tr -d ' ')"

  echo
  echo "== 9. RUN G: the TIER-1b MUTUAL EXCLUSION -- disable-strip, and it is VERIFIED =="
  port="$(free_port)"; rm -rf "$OUT/recv-ds"; mkdir -p "$OUT/recv-ds"
  "$JAVA" -javaagent:"$AGENT" \
      -Dax.trace.enabled=true \
      -Dax.trace.include.packages=traceapp \
      -Dax.trace.strip.conflict=disable-strip \
      -Dax.include.packages=traceapp \
      -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
      -Dtrace.smoke.scenario=conflict-disablestrip \
      -Dtrace.smoke.recv="$OUT/recv-ds" \
      -Dtrace.smoke.port="$port" \
      -cp "$OUT/classes:$AXAGENT" tracerun.TraceApp > "$OUT/log-ds.txt" 2>&1
  rc=$?
  grep -E '^  (PASS|FAIL)|disable-strip|Tier-1b' "$OUT/log-ds.txt" || true
  tally "RUN G conflict=disable-strip" "$rc"
  if grep -q 'Tier-1b auto-strip is being' "$OUT/log-ds.txt"; then
    ok "RUN G: the startup line says Tier-1b was turned off, and why"
  else bad "RUN G: expected a line saying Tier-1b was turned off"; fi
  if grep -q 'ONLY WORKS IF -javaagent:ax-trace.jar PRECEDES' "$OUT/log-ds.txt"; then
    ok "RUN G: the ordering requirement is stated rather than assumed"
  else bad "RUN G: the ordering requirement must be stated"; fi
  check "RUN G: the tracer armed and produced 1 document" "1" \
      "$(ls "$OUT/recv-ds" 2>/dev/null | wc -l | tr -d ' ')"

  echo
  echo "== 9b. RUN H: BOTH AGENTS IN ONE JVM (coexistence) =="
  AXSTATIC="$MODULE/../ax-static/target/ax-static.jar"
  if [ ! -f "$AXSTATIC" ]; then
    echo "  SKIP  ax-static.jar is not built; ax-agent needs a build manifest and there is no"
    echo "        substitute generator (run-smoke.sh refuses one too). Coexistence UNTESTED."
  else
    # ax-static targets release 17; this suite runs on 11, 17 and 21. The generator is a
    # BUILD-TIME tool whose only output is JSON, so the JDK that runs it is independent of the
    # JVM under test -- the same argument run-smoke.sh makes.
    AXSTATIC_JAVA="${AX_STATIC_JAVA:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java}"
    [ -x "$AXSTATIC_JAVA" ] || AXSTATIC_JAVA="$JAVA"
    "$AXSTATIC_JAVA" -jar "$AXSTATIC" --input "$OUT/classes" --build-sha tracesmoke \
        --artifact trace-smoke --output "$OUT/auxin-manifest.json" > /dev/null 2>&1
    if [ ! -f "$OUT/auxin-manifest.json" ]; then
      echo "  SKIP  ax-static could not produce a manifest for the target app. Coexistence UNTESTED."
    else
      port="$(free_port)"; rm -rf "$OUT/recv-both"; mkdir -p "$OUT/recv-both"
      "$JAVA" -javaagent:"$AGENT" -javaagent:"$AXAGENT" \
          -Dax.trace.enabled=true \
          -Dax.trace.include.packages=traceapp \
          -Dax.trace.strip.conflict=disable-strip \
          -Dax.include.packages=traceapp \
          -Dax.manifest="$OUT/auxin-manifest.json" \
          -Dax.transport.enabled=false \
          -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
          -Dtrace.smoke.scenario=full \
          -Dtrace.smoke.recv="$OUT/recv-both" \
          -Dtrace.smoke.port="$port" \
          -cp "$OUT/classes" tracerun.TraceApp > "$OUT/log-both.txt" 2>&1
      rc=$?
      grep -E '^  (PASS|FAIL)|VerifyError|armed in|ARMED in' "$OUT/log-both.txt" | head -20 || true
      tally "RUN H both agents" "$rc"
      if grep -qE 'VerifyError|ClassFormatError|NoClassDefFoundError' "$OUT/log-both.txt"; then
        bad "RUN H: a verification or linkage error occurred with both agents installed"
      else
        ok "RUN H: no VerifyError / ClassFormatError / NoClassDefFoundError with both agents"
      fi
      check "RUN H: the tracer still produced 1 document alongside ax-agent" "1" \
          "$(ls "$OUT/recv-both" 2>/dev/null | wc -l | tr -d ' ')"
      if [ -f "$OUT/recv-both/trace-1.json" ]; then
        python3 -c "
import json,sys
d=json.load(open('$OUT/recv-both/trace-1.json'))
def walk(fs):
    for f in fs:
        yield f
        for g in walk(f.get('calls',[])): yield g
bs=[b for f in walk(d['frames']) for b in f.get('branches',[])]
bad=[b for b in bs if b.get('op')=='IFNE' and b.get('why') in ('call','staticField','field') and b.get('n',1)>200]
print('  coexistence: %d branch groups recorded, %d suspicious' % (len(bs), len(bad)))
sys.exit(1 if bad else 0)" && \
        ok "RUN H: ax-agent's own read-then-store probe (BALOAD+IFNE) was NOT recorded as a branch" || \
        bad "RUN H: a coverage probe leaked into the branch arms"
      fi
    fi
  fi
fi

echo
echo "== 10. RUN I: parallelstream.window=false -> the trap is explicitly unhandled =="
run noparallel recv-nopar -Dax.trace.enabled=true -Dax.trace.parallelstream.window=false \
    > "$OUT/log-nopar.txt" 2>&1; rc=$?
grep -E '^  (PASS|FAIL)' "$OUT/log-nopar.txt" || true
tally "RUN I parallelstream.window=false" "$rc"
if [ -f "$OUT/recv-nopar/trace-1.json" ]; then
  python3 -c "
import json,sys
d=json.load(open('$OUT/recv-nopar/trace-1.json'))
def walk(fs):
    for f in fs:
        yield f
        for g in walk(f.get('calls',[])): yield g
cp=[f for f in walk(d['frames']) if f.get('via')=='commonPool']
print('  window off: %d commonPool frames (expected 0)' % len(cp))
sys.exit(0 if not cp else 1)" && \
    ok "RUN I: with the window off, NO parallelStream frame is captured (stated, not silent)" || \
    bad "RUN I: commonPool frames appeared with the window disabled"
fi

echo
echo "== 11. RUN J: FAIL-OPEN latches the tracer off and the application keeps running =="
run failopen recv-fail -Dax.trace.enabled=true > "$OUT/log-fail.txt" 2>&1; rc=$?
grep -E '^  (PASS|FAIL)' "$OUT/log-fail.txt" || true
tally "RUN J fail-open" "$rc"
if grep -q 'is now OFF for the life of' "$OUT/log-fail.txt"; then
  ok "RUN J: the fail-open latch says so exactly once"
else bad "RUN J: expected the fail-open WARN"; fi

echo
echo "== 12. BYTECODE SHAPE (javap) =="
ORIG="$OUT/classes/traceapp/SearchService.class"
TRACED="$OUT/dump/traceapp.SearchService.class"
FILT_O="$OUT/classes/traceapp/SearchFilter.class"
FILT_T="$OUT/dump/traceapp.SearchFilter.class"
if [ ! -f "$TRACED" ]; then
  bad "no instrumented class was dumped to $OUT/dump"
else
  count() { "$JAVAP" -v -p -c "$1" | grep -cE "$2" || true; }
  insn() { count "$1" "^ +[0-9]+: invokestatic.*TraceRuntime\\.$2:"; }

  ENTERS="$(insn "$TRACED" enter)"
  EXITS="$(insn "$TRACED" exit)"
  check "one TraceRuntime.enter per instrumented method" "$ENTERS" \
      "$(count "$TRACED" '^ +[0-9]+: invokestatic.*TraceRuntime\.enter:\(I\)V')"
  # Each method has >= 1 return plus exactly one handler, so exits > enters.
  checks=$((checks+1))
  if [ "$EXITS" -gt "$ENTERS" ]; then
    echo "  PASS  TraceRuntime.exit at every return AND in every handler ($EXITS exits, $ENTERS enters)"
  else
    echo "  FAIL  exits ($EXITS) should exceed enters ($ENTERS)"; fails=$((fails+1))
  fi
  # A constructor gets everything EXCEPT the throw handler: a catch(Throwable) inside <init>
  # would have to merge against a state where `this` may be uninitializedThis. So the expected
  # handler count is one per instrumented method MINUS the instrumented constructors.
  INITS="$("$JAVAP" -p "$ORIG" | grep -c "public traceapp.SearchService(" || true)"
  HANDLERS=$(( ENTERS - INITS ))
  checks=$((checks+1))
  if [ "$(insn "$TRACED" threw)" = "$HANDLERS" ]; then
    echo "  PASS  one TraceRuntime.threw handler per instrumented method except <init> ($HANDLERS of $ENTERS)"
  else
    echo "  FAIL  expected $HANDLERS threw calls, got $(insn "$TRACED" threw)"; fails=$((fails+1))
  fi
  checks=$((checks+1))
  if grep -q 'throwCaptureUnsupportedInit' "$OUT/recv-full/trace-1.json"; then
    echo "  PASS  the omitted <init> handler is COUNTED, not silent (skipped.throwCaptureUnsupportedInit)"
  else
    echo "  FAIL  the omitted <init> handler must be counted"; fails=$((fails+1))
  fi

  # THE CENTRAL CLAIM ABOUT THE SHAPE: one new stack map frame per method (the handler's),
  # and not one anywhere else. Everything else emitted adds no local and no branch.
  OFRAMES="$(count "$ORIG" 'frame_type')"
  NFRAMES="$(count "$TRACED" 'frame_type')"
  check "exactly ONE new stack map frame per handler, and not one anywhere else" \
      "$(( OFRAMES + HANDLERS ))" "$NFRAMES"
  check "one new exception-table 'any' entry per handler, and not one more" \
      "$(( $(count "$ORIG" '^ +[0-9]+ +[0-9]+ +[0-9]+ +any$') + HANDLERS ))" \
      "$(count "$TRACED" '^ +[0-9]+ +[0-9]+ +[0-9]+ +any$')"
  # Every handler frame names the receiver plus the descriptor's arguments, and nothing else.
  # It was ZERO locals until the both-agents run produced
  #   "Type top (current frame, locals[0]) is not assignable to traceapp/SearchFilter"
  # -- see TraceEmitter's comment at the handler frame. `top` is assignable-TO, not -FROM, so a
  # zero-locals frame only composes if you are the last transformer, which no agent can assume.
  checks=$((checks+1))
  if [ "$(count "$TRACED" 'locals = \[\]')" = "0" ]; then
    echo "  PASS  no handler frame erases locals[0] to top (the coexistence VerifyError)"
  else
    echo "  FAIL  $(count "$TRACED" 'locals = \[\]') handler frames still declare zero locals"
    fails=$((fails+1))
  fi
  checks=$((checks+1))
  if [ "$(count "$TRACED" 'locals = \[ class traceapp/SearchService')" -ge 1 ]; then
    echo "  PASS  handler frames name the receiver type (so another agent's handler can merge)"
  else
    echo "  FAIL  handler frames must name the receiver type"; fails=$((fails+1))
  fi

  # No probe adds a local slot: compare the declared locals of one method.
  OL="$("$JAVAP" -v -p -c "$ORIG" | awk '/int score\(traceapp.Fare\)/,/^$/' \
      | grep -o 'locals=[0-9]*' | head -1)"
  NL="$("$JAVAP" -v -p -c "$TRACED" | awk '/int score\(traceapp.Fare\)/,/^$/' \
      | grep -o 'locals=[0-9]*' | head -1)"
  check "no trace probe adds a local slot (score(Fare): $OL -> $NL)" "$OL" "$NL"

  # Branch arms: the operand-duplication shape, and no code at any jump target.
  checks=$((checks+1))
  if [ "$(insn "$TRACED" armI)" -gt 0 ] || [ "$(insn "$TRACED" armII)" -gt 0 ]; then
    echo "  PASS  branch-arm probes were emitted (armI=$(insn "$TRACED" armI), armII=$(insn "$TRACED" armII), armA=$(insn "$TRACED" armA))"
  else
    echo "  FAIL  no branch-arm probe was emitted"; fails=$((fails+1))
  fi
  # The executor call-site rewrite.
  checks=$((checks+1))
  if [ "$(insn "$TRACED" wrapCallable)" -gt 0 ]; then
    echo "  PASS  the ExecutorService.submit(Callable) call site was rewritten in the APPLICATION class"
  else
    echo "  FAIL  no wrapCallable at the submit call site"; fails=$((fails+1))
  fi
  # The parallelStream window.
  checks=$((checks+1))
  if [ "$(insn "$TRACED" commonPoolArm)" -gt 0 ]; then
    echo "  PASS  the parallelStream-bearing method is bracketed with commonPoolArm/Disarm"
  else
    echo "  FAIL  no commonPoolArm in the parallelStream-bearing method"; fails=$((fails+1))
  fi
  # java.util.concurrent is NOT touched: the whole reason the call site is rewritten instead.
  checks=$((checks+1))
  if "$JAVAP" -v -p -c "$TRACED" | grep -q 'java/util/concurrent/ThreadPoolExecutor'; then
    echo "  FAIL  the instrumented class references ThreadPoolExecutor internals"; fails=$((fails+1))
  else
    echo "  PASS  no JDK concurrency class was instrumented (no bootstrap visibility needed)"
  fi
  # The entry.
  checks=$((checks+1))
  if [ "$(count "$FILT_T" '^ +[0-9]+: invokestatic.*TraceGate\.begin:')" = "1" ]; then
    echo "  PASS  exactly one TraceGate.begin in the filter (the activation point)"
  else
    echo "  FAIL  expected 1 TraceGate.begin in SearchFilter"; fails=$((fails+1))
  fi
  checks=$((checks+1))
  GEND="$(count "$FILT_T" '^ +[0-9]+: invokestatic.*TraceGate\.end:')"
  if [ "$GEND" -ge 2 ]; then
    echo "  PASS  TraceGate.end runs on the normal path AND from the handler ($GEND sites)"
  else
    echo "  FAIL  TraceGate.end must run on both paths (found $GEND)"; fails=$((fails+1))
  fi
  echo
  printf "    SearchService  %6d -> %6d bytes (+%d%%), %d -> %d stack map frames\n" \
      "$(wc -c < "$ORIG" | tr -d ' ')" "$(wc -c < "$TRACED" | tr -d ' ')" \
      "$(( ($(wc -c < "$TRACED" | tr -d ' ') - $(wc -c < "$ORIG" | tr -d ' ')) * 100 \
          / $(wc -c < "$ORIG" | tr -d ' ') ))" "$OFRAMES" "$NFRAMES"
  printf "    SearchFilter   %6d -> %6d bytes\n" \
      "$(wc -c < "$FILT_O" | tr -d ' ')" "$(wc -c < "$FILT_T" | tr -d ' ')"
fi

echo
echo "=================================================================="
if [ "$fails" -gt 0 ]; then
  echo "TRACE SMOKE FAILED: $fails/$checks assertions"
  exit 1
fi
echo "TRACE SMOKE PASSED: $checks/$checks assertions"
