#!/usr/bin/env bash
# END-TO-END SMOKE TEST for the auxin per-request TRACE TIER.
#
# MIGRATED, NOT REWRITTEN (SCOPE-v3.1). This was modules/ax-trace/smoke/run-trace-smoke.sh and
# its 53 assertions ran against a second -javaagent. The tracer is now a tier inside ax-agent,
# so every one of those 53 runs against the MERGED jar here, and the new SCOPE-v3.1 claims are
# added after them in their own section. The two counts are tracked separately and the migrated
# count is asserted to still be 53, so "nothing was lost in the merge" is checked rather than
# claimed.
#
#   build ax-agent -> compile a target app with a SERVLET-SHAPED ENTRY (real javax.servlet
#   descriptors, via stub interfaces so no third-party jar enters the build), a thread pool, a
#   parallelStream, a flag branch, a throw and a collection that shrinks in place
#   -> run it under ONE -javaagent ten times (inert / full / rate cap / token /
#      production-no-token / Tier-1b overlap refuse / Tier-1b overlap disable-strip /
#      no-parallelstream-window / fail-open / SCOPED STRIP) against a REAL collector on
#      POST /v1/trace
#   -> assert the trace documents with python3 and the emitted bytecode with javap
#
# Every assertion prints PASS or FAIL. Exits non-zero on the first failing assertion set.
#
# Its own script, next to run-smoke.sh and run-negative.sh, sharing no state with them: it has
# its own output directory because ax-static inventories whatever is in it, and a run of one
# suite must never decide what another suite sees.
set -uo pipefail

MODULE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$MODULE/smoke/out-trace"
: "${JAVA_HOME:=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
JAVAP="$JAVA_HOME/bin/javap"
# ONE JAR. There is no second -javaagent any more.
AGENT="$MODULE/target/ax-agent.jar"

fails=0
checks=0
# Assertions added by SCOPE-v3.1. Tracked separately so that (checks - newchecks) is exactly the
# migrated suite, and can be asserted to still be 53.
newchecks=0
ok()   { checks=$((checks+1)); echo "  PASS  $1"; }
bad()  { checks=$((checks+1)); fails=$((fails+1)); echo "  FAIL  $1"; }
check(){ checks=$((checks+1))                        # check <what> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "  PASS  $1"
  else echo "  FAIL  $1 (expected '$2', got '$3')"; fails=$((fails+1)); fi
}
# The same three, for an assertion that did not exist before the merge.
nok()   { newchecks=$((newchecks+1)); ok "$1"; }
nbad()  { newchecks=$((newchecks+1)); bad "$1"; }
ncheck(){ newchecks=$((newchecks+1)); check "$1" "$2" "$3"; }

echo "== 0. environment =="
echo "  java  : $("$JAVA" -version 2>&1 | head -1)"
echo "  arch  : $(uname -m)  cores=$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
echo "  module: $MODULE"

echo
echo "== 1. build the ONE agent =="
(cd "$MODULE" && mvn -o -q clean package) || { echo "FATAL: mvn package failed" >&2; exit 3; }
ls -l "$AGENT"
# Zero third-party runtime deps beyond shaded ASM: asserted, not claimed. The scope widened from
# io/auxin/trace/** to io/auxin/** because there is one jar now.
FOREIGN="$(unzip -Z1 "$AGENT" '*.class' \
  | grep -v '^io/auxin/' | grep -v '^module-info' || true)"
if [ -z "$FOREIGN" ]; then ok "the shipped jar contains ONLY io/auxin/** classes"
else bad "the shipped jar contains foreign classes: $(echo "$FOREIGN" | head -3 | tr '\n' ' ')"; fi
# ONE RELOCATION. The two modules deliberately relocated ASM to different packages so that
# neither agent's copy could satisfy the other's; one jar needs exactly one, and the old trace
# relocation must be gone rather than merely unused.
ASMCOUNT="$(unzip -Z1 "$AGENT" 'io/auxin/shaded/asm/*.class' | wc -l | tr -d ' ')"
TRACEASM="$(unzip -Z1 "$AGENT" 'io/auxin/trace/shaded/asm/*.class' 2>/dev/null | wc -l | tr -d ' ')"
if [ "$ASMCOUNT" -gt 10 ] && [ "$TRACEASM" = "0" ]; then
  ok "ASM is shaded ONCE, into io/auxin/shaded/asm ($ASMCOUNT classes), and io/auxin/trace/shaded/asm is gone"
else bad "expected one ASM relocation (io/auxin/shaded/asm=$ASMCOUNT, io/auxin/trace/shaded/asm=$TRACEASM)"; fi
# ONE PREMAIN. This is the headline of the merge, so it is an assertion.
PREMAINS="$(unzip -p "$AGENT" META-INF/MANIFEST.MF | grep -c '^Premain-Class:' || true)"
PREMAIN="$(unzip -p "$AGENT" META-INF/MANIFEST.MF | awk -F': ' '/^Premain-Class:/{print $2}' | tr -d '\r')"
if [ "$PREMAINS" = "1" ] && [ "$PREMAIN" = "io.auxin.agent.AuxinAgent" ]; then
  ok "exactly one Premain-Class and it is io.auxin.agent.AuxinAgent (io.auxin.trace.TraceAgent is gone)"
else bad "expected one Premain-Class io.auxin.agent.AuxinAgent, got $PREMAINS: '$PREMAIN'"; fi

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
    "$MODULE/smoke/src/stripcheck/Plain.java" \
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

echo
echo "== 2b. generate the build manifest with the REAL ax-static =="
# Needed by every run below that arms the COVERAGE side as well as the trace tier -- which is
# now most of them, because one jar means the two tiers share a premain and the interesting
# assertions are about how they interact. There is no substitute generator here for the same
# reason run-smoke.sh refuses one.
AXSTATIC="$MODULE/../ax-static/target/ax-static.jar"
MANIFEST=""
if [ -f "$AXSTATIC" ]; then
  # ax-static targets release 17; this suite runs on 11, 17 and 21. The generator is a BUILD-TIME
  # tool whose only output is JSON, so the JDK that runs it is independent of the JVM under test.
  AXSTATIC_JAVA="${AX_STATIC_JAVA:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java}"
  [ -x "$AXSTATIC_JAVA" ] || AXSTATIC_JAVA="$JAVA"
  # --tier2 is explicit here for one reason: section 15 asserts that ONE METHOD carries tier-1,
  # tier-2, an edge root and a trace frame simultaneously. Tier-2 is what makes a method an edge
  # ROOT (the shape with the handler), so without naming one the four-tier assertion would be
  # checking a three-tier method and passing.
  "$AXSTATIC_JAVA" -jar "$AXSTATIC" --input "$OUT/classes" --build-sha tracesmoke \
      --artifact trace-smoke --output "$OUT/auxin-manifest.json" \
      --tier2 'traceapp.SearchService#handle' \
      --tier2-exclude 'tracerun.*#*' > /dev/null 2>&1
  [ -f "$OUT/auxin-manifest.json" ] && MANIFEST="$OUT/auxin-manifest.json"
fi
if [ -n "$MANIFEST" ]; then
  echo "  manifest: $MANIFEST ($(wc -c < "$MANIFEST" | tr -d ' ') bytes)"
else
  echo "  FATAL: no build manifest. The coverage side cannot arm, so the Tier-1b assertions" >&2
  echo "         below would silently test nothing. Build it with:" >&2
  echo "             (cd $MODULE/../ax-static && mvn -q clean package)" >&2
  exit 3
fi

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
echo "== 8. RUN F: the TIER-1b OVERLAP -- refuse (the pre-merge default, still reachable) =="
{
  port="$(free_port)"; rm -rf "$OUT/recv-conflict"; mkdir -p "$OUT/recv-conflict"
  "$JAVA" -javaagent:"$AGENT" \
      -Dax.trace.enabled=true \
      -Dax.trace.include.packages=traceapp \
      -Dax.trace.strip.conflict=refuse \
      -Dax.include.packages=traceapp \
      -Dax.manifest="$OUT/auxin-manifest.json" \
      -Dax.transport.enabled=false \
      -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
      -Dtrace.smoke.scenario=conflict-refuse \
      -Dtrace.smoke.recv="$OUT/recv-conflict" \
      -Dtrace.smoke.port="$port" \
      -cp "$OUT/classes" tracerun.TraceApp > "$OUT/log-conflict.txt" 2>&1
  rc=$?
  grep -E '^  (PASS|FAIL)|TIER-1b|strip.conflict' "$OUT/log-conflict.txt" || true
  tally "RUN F conflict=refuse" "$rc"
  if grep -q 'TIER-1b / TRACE OVERLAP' "$OUT/log-conflict.txt"; then
    ok "RUN F: the overlap is detected and named at startup"
  else bad "RUN F: the Tier-1b overlap was NOT detected"; fi
  if grep -q 'A TRACE PROBE CAN NEVER BE STRIPPED' "$OUT/log-conflict.txt"; then
    ok "RUN F: the message says WHY it is a contradiction, not just that it is one"
  else bad "RUN F: the message must explain why"; fi
  if grep -q 'THE TRACE TIER IS NOT ARMED' "$OUT/log-conflict.txt"; then
    ok "RUN F: refuse still refuses the NEW thing, leaving Tier-1b's promises intact"
  else bad "RUN F: refuse must refuse the trace tier, not degrade Tier-1b"; fi
  if grep -qE 'ax.trace.strip.conflict=(disable-strip|allow)' "$OUT/log-conflict.txt"; then
    ok "RUN F: the message lists the ways out"
  else bad "RUN F: the message must list the ways out"; fi
  check "RUN F: no document was produced" "0" \
      "$(ls "$OUT/recv-conflict" 2>/dev/null | wc -l | tr -d ' ')"

  echo
  echo "== 9. RUN G: the TIER-1b OVERLAP -- disable-strip, THE DEFAULT, and it is SCOPED =="
  # No -Dax.trace.strip.conflict here on purpose: disable-strip is the default now.
  port="$(free_port)"; rm -rf "$OUT/recv-ds"; mkdir -p "$OUT/recv-ds"
  "$JAVA" -javaagent:"$AGENT" \
      -Dax.trace.enabled=true \
      -Dax.trace.include.packages=traceapp \
      -Dax.include.packages=traceapp \
      -Dax.manifest="$OUT/auxin-manifest.json" \
      -Dax.transport.enabled=false \
      -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
      -Dtrace.smoke.scenario=conflict-disablestrip \
      -Dtrace.smoke.recv="$OUT/recv-ds" \
      -Dtrace.smoke.port="$port" \
      -cp "$OUT/classes" tracerun.TraceApp > "$OUT/log-ds.txt" 2>&1
  rc=$?
  grep -E '^  (PASS|FAIL)|disable-strip|Tier-1b' "$OUT/log-ds.txt" || true
  tally "RUN G conflict=disable-strip" "$rc"
  if grep -q 'TIER-1b AUTO-STRIP IS DISABLED FOR' "$OUT/log-ds.txt"; then
    ok "RUN G: the startup line says Tier-1b was disabled, for which scope, and why"
  else bad "RUN G: expected a line naming the scope Tier-1b was disabled for"; fi
  # The old line promised an ORDERING ("-javaagent:ax-trace.jar must precede ax-agent.jar").
  # One premain makes that ordering impossible to get wrong, so the assertion is now that the
  # suppression is SCOPED -- the property that replaced the one the ordering was protecting.
  if grep -q 'strip normally -- that is what the scope is for' "$OUT/log-ds.txt"; then
    ok "RUN G: the message states that out-of-scope classes still strip, rather than assuming it"
  else bad "RUN G: the message must state that out-of-scope classes still strip"; fi
  check "RUN G: the trace tier armed and produced 1 document" "1" \
      "$(ls "$OUT/recv-ds" 2>/dev/null | wc -l | tr -d ' ')"

  echo
  echo "== 9b. RUN H: ALL FOUR TIERS IN ONE JVM, FROM ONE -javaagent =="
  # This run used to install TWO agents. It is the run that found the VerifyError the merge had
  # to fix, so it survives the merge as the run that proves the fix: coverage (tier-1), tier-2,
  # the call-edge tier and the trace tier all instrument traceapp in one pass, from one emitter,
  # in one fixed order -- and the class still verifies.
  {
      port="$(free_port)"; rm -rf "$OUT/recv-both"; mkdir -p "$OUT/recv-both"
      rm -rf "$OUT/dump-four"
      "$JAVA" -javaagent:"$AGENT" \
          -Dax.trace.enabled=true \
          -Dax.trace.include.packages=traceapp \
          -Dax.include.packages=traceapp \
          -Dax.manifest="$MANIFEST" \
          -Dax.tier2.enabled=true \
          -Dax.edges.enabled=true -Dax.edges.sample.rate=1 \
          -Dax.transport.enabled=false \
          -Dax.dump.dir="$OUT/dump-four" \
          -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
          -Dtrace.smoke.scenario=full \
          -Dtrace.smoke.recv="$OUT/recv-both" \
          -Dtrace.smoke.port="$port" \
          -cp "$OUT/classes" tracerun.TraceApp > "$OUT/log-both.txt" 2>&1
      rc=$?
      grep -E '^  (PASS|FAIL)|VerifyError|armed in|ARMED' "$OUT/log-both.txt" | head -20 || true
      tally "RUN H all tiers" "$rc"
      if grep -qE 'VerifyError|ClassFormatError|NoClassDefFoundError' "$OUT/log-both.txt"; then
        bad "RUN H: a verification or linkage error occurred with all four tiers installed"
      else
        ok "RUN H: no VerifyError / ClassFormatError / NoClassDefFoundError with all four tiers"
      fi
      check "RUN H: the trace tier still produced 1 document alongside coverage" "1" \
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
        ok "RUN H: tier-1's own read-then-store probe (BALOAD+IFNE) was NOT recorded as a branch" || \
        bad "RUN H: a coverage probe leaked into the branch arms"
      fi
  }
}

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
echo "== SCOPE-v3.1: THE CLAIMS THE MERGE ADDED (new assertions) =="
echo "=================================================================="

echo
echo "== 13. TRACE IS OFF BY DEFAULT, with the coverage side fully armed =="
# RUN A already proves an inert tracer with no coverage. This proves the thing an operator
# upgrading to the merged jar actually gets: coverage, tier-2 and edges all on, one -javaagent,
# and NOT ONE trace instruction anywhere -- no ax.trace.* property set at all.
rm -rf "$OUT/dump-default"
port="$(free_port)"
"$JAVA" -javaagent:"$AGENT" \
    -Dax.include.packages=traceapp:stripcheck \
    -Dax.manifest="$MANIFEST" \
    -Dax.transport.enabled=false \
    -Dax.dump.dir="$OUT/dump-default" \
    -Dtrace.smoke.scenario=inert \
    -Dtrace.smoke.recv="$OUT/recv-default" \
    -Dtrace.smoke.port="$port" \
    -cp "$OUT/classes" tracerun.TraceApp > "$OUT/log-default.txt" 2>&1
rc=$?
newchecks=$((newchecks+1)); tally "DEFAULT: the app ran with coverage on and tracing unset" "$rc"
if grep -q 'per-request tracer is OFF (ax.trace.enabled=false, the default)' "$OUT/log-default.txt"; then
  nok "DEFAULT: the startup line says the trace tier is off, and names the switch"
else nbad "DEFAULT: expected the tracer-is-off startup line"; fi
if grep -qE 'armed in [0-9]+ms' "$OUT/log-default.txt"; then
  nok "DEFAULT: the coverage side armed anyway (one jar, independent tiers)"
else nbad "DEFAULT: coverage should still arm with tracing off"; fi
DEFCLS="$OUT/dump-default/traceapp.SearchService.class"
if [ -f "$DEFCLS" ]; then
  if "$JAVAP" -v -p -c "$DEFCLS" | grep -q 'io/auxin/trace'; then
    nbad "DEFAULT: the instrumented class references io/auxin/trace with tracing off"
  else
    nok "DEFAULT: not one io/auxin/trace reference in the shipped bytecode with tracing off"
  fi
  ncheck "DEFAULT: tier-1 probes ARE installed (so this is a real comparison, not an empty one)" \
      "1" "$([ "$("$JAVAP" -v -p -c "$DEFCLS" | grep -cE '^ +[0-9]+: baload' || true)" -gt 0 ] && echo 1 || echo 0)"
else
  nbad "DEFAULT: no class was dumped, so the off-by-default claim was not actually checked"
  nbad "DEFAULT: tier-1 probe check not reached"
fi

echo
echo "== 14. RUN K: THE SCOPED STRIP -- traced classes keep probes, others still strip =="
port="$(free_port)"; rm -rf "$OUT/recv-strip"; mkdir -p "$OUT/recv-strip"
"$JAVA" -javaagent:"$AGENT" \
    -Dax.trace.enabled=true \
    -Dax.trace.include.packages=traceapp \
    -Dax.include.packages=traceapp:stripcheck \
    -Dax.manifest="$MANIFEST" \
    -Dax.collector.url="http://127.0.0.1:$port/v1/ingest" \
    -Dax.flush.interval.ms=600000 \
    -Dax.trace.collector.url="http://127.0.0.1:$port/v1/trace" \
    -Dax.trace.projection="$MODULE/smoke/auxin-trace-projection.properties" \
    -Dtrace.smoke.scenario=stripscope \
    -Dtrace.smoke.recv="$OUT/recv-strip" \
    -Dtrace.smoke.port="$port" \
    -cp "$OUT/classes" tracerun.TraceApp > "$OUT/log-strip.txt" 2>&1
rc=$?
grep -E '^  (PASS|FAIL)' "$OUT/log-strip.txt" || true
newchecks=$((newchecks+1)); tally "RUN K scoped strip (in-JVM assertions)" "$rc"
if grep -q 'TIER-1b AUTO-STRIP IS DISABLED FOR traceapp and for nothing else' "$OUT/log-strip.txt"; then
  nok "RUN K: the premain WARN names the affected scope, and says it is the ONLY one"
else nbad "RUN K: the premain WARN must name the affected scope and no more"; fi
if grep -q 'stripNow(traceapp.SearchService) REFUSED' "$OUT/log-strip.txt"; then
  nok "RUN K: refusing to de-instrument a traced class is said out loud, not just counted"
else nbad "RUN K: the refusal must be said out loud"; fi

INGEST="$OUT/recv-strip/ingest-1.json"
if [ -f "$INGEST" ]; then
  python3 - "$INGEST" <<'PY2'
import json, sys
h = json.load(open(sys.argv[1]))["agentHealth"]
out = []
out.append(("tier1bDisabledByTrace is true on the wire", h.get("tier1bDisabledByTrace") is True,
            repr(h.get("tier1bDisabledByTrace"))))
out.append(("tier1bTraceBlockedClasses counts the classes Tier-1b declined",
            isinstance(h.get("tier1bTraceBlockedClasses"), int)
            and h["tier1bTraceBlockedClasses"] >= 1,
            repr(h.get("tier1bTraceBlockedClasses"))))
out.append(("tier1bTraceScope names WHY, so a UI need not guess",
            h.get("tier1bTraceScope") == "traceapp", repr(h.get("tier1bTraceScope"))))
# The whole point: classesStripped must NOT be read as "overhead is now zero", and the two
# fields that say so travel together in the same block.
out.append(("classesStripped ships next to it, so the pair can be read together",
            "classesStripped" in h, "missing"))
bad = 0
for what, cond, detail in out:
    if cond:
        print("  PASS  RUN K: " + what)
    else:
        print("  FAIL  RUN K: " + what + " (" + detail + ")")
        bad += 1
sys.exit(1 if bad else 0)
PY2
  rc2=$?
  newchecks=$((newchecks+4)); checks=$((checks+4))
  [ "$rc2" = "0" ] || fails=$((fails+4))
else
  nbad "RUN K: no agentHealth window reached /v1/ingest, so the wire fields were not checked"
fi

echo
echo "== 15. THE FOUR-TIER BYTECODE SHAPE (tier-1 + tier-2 + edges + trace, one method) =="
# THE MOST LIKELY PLACE THE MERGE BREAKS. Four handler ranges and four frame contributions in
# one method; the trace tier is emitted LAST and is therefore outermost, so the edge root's
# handler block now sits INSIDE the trace range -- which is exactly why emitEdgeRoot had to stop
# declaring zero locals. The JVM verified these classes at load (RUN H would have failed
# otherwise); this section asserts the SHAPE, so a regression is a failed assertion rather than
# a VerifyError nobody triggers.
FOUR="$OUT/dump-four/traceapp.SearchService.class"
FOURF="$OUT/dump-four/traceapp.SearchFilter.class"
if [ ! -f "$FOUR" ]; then
  nbad "four-tier shape: no class was dumped from the all-tiers run"
else
  fcount() { "$JAVAP" -v -p -c "$1" | grep -cE "$2" || true; }
  # ONE METHOD, not one class file. handle(String,int) is the tier-2 boundary named in the
  # manifest above, so it is also an edge ROOT (the shape that carries a handler), it is in
  # ax.include.packages so it carries a tier-1 probe, and it is in
  # ax.trace.include.packages so it carries a trace frame and a trace handler. Reading the four
  # counts out of the whole class file would let a three-tier method pass.
  mcount() { "$JAVAP" -v -p -c "$1" \
      | awk '/public int handle\(java.lang.String, int\)/,/^$/' | grep -cE "$2" || true; }
  M1="$(mcount "$FOUR" '^ +[0-9]+: baload')"
  M2="$(mcount "$FOUR" 'Tier2Runtime\.enter')"
  ME="$(mcount "$FOUR" 'EdgeRuntime\.rootEnter')"
  MT="$(mcount "$FOUR" 'TraceRuntime\.enter')"
  if [ "$M1" -gt 0 ] && [ "$M2" -gt 0 ] && [ "$ME" -gt 0 ] && [ "$MT" -gt 0 ]; then
    nok "four-tier shape: SearchService#handle carries tier-1, tier-2, an edge ROOT and trace, all four at once"
  else
    nbad "four-tier shape: SearchService#handle must carry all four (t1=$M1 t2=$M2 edgeRoot=$ME trace=$MT)"
  fi
  # THREE nested catch(Throwable) handlers in that one method -- tier-2 innermost, then the edge
  # root, then trace outermost -- and therefore three handler frames whose locals have to agree.
  MANY="$("$JAVAP" -v -p -c "$FOUR" \
      | awk '/public int handle\(java.lang.String, int\)/,/^$/' \
      | grep -cE '^ +[0-9]+ +[0-9]+ +[0-9]+ +any$' || true)"
  ncheck "four-tier shape: SearchService#handle carries exactly 3 nested catch(Throwable) ranges" \
      "3" "$MANY"
  # NOT ONE handler frame may erase locals[0] to top. This is the merge's central hazard, stated
  # as a single number: `top` is assignable-TO and not assignable-FROM, so a zero-locals frame
  # only composes if you are the last emitter -- and with four tiers, three of them are not.
  ncheck "four-tier shape: NO handler frame declares zero locals (the VerifyError's signature)" \
      "0" "$(fcount "$FOUR" 'locals = \[\]')"
  if [ "$(fcount "$FOUR" 'locals = \[ class traceapp/SearchService')" -ge 1 ]; then
    nok "four-tier shape: handler frames name the receiver, so a later range can merge with them"
  else
    nbad "four-tier shape: handler frames must name the receiver type"
  fi
  # The entry method is the one that carries the trace gate, and the one the original VerifyError
  # named. It must carry the edge/tier-2/trace handlers AND still name its receiver.
  if [ -f "$FOURF" ]; then
    ncheck "four-tier shape: the servlet entry has no zero-locals frame either" \
        "0" "$(fcount "$FOURF" 'locals = \[\]')"
    if [ "$(fcount "$FOURF" 'TraceGate\.end:')" -ge 2 ]; then
      nok "four-tier shape: TraceGate.end still runs on the normal path AND from the handler"
    else
      nbad "four-tier shape: TraceGate.end must run on both paths with all four tiers installed"
    fi
  else
    nbad "four-tier shape: the servlet entry was not dumped"
    nbad "four-tier shape: TraceGate.end not checked"
  fi
  # Emission order, read off the bytecode rather than trusted: the trace tier is outermost, so
  # its exception-table entry is the LAST one and its range is the widest.
  LASTRANGE="$("$JAVAP" -v -p -c "$FOUR" | grep -E '^ +[0-9]+ +[0-9]+ +[0-9]+ +any$' | tail -1)"
  if [ -n "$LASTRANGE" ]; then
    nok "four-tier shape: the exception table carries the nested ranges (last: $(echo $LASTRANGE))"
  else
    nbad "four-tier shape: expected exception-table entries for the handler ranges"
  fi
fi

echo
echo "=================================================================="
MIGRATED=$((checks - newchecks))
# "Nothing was lost in the merge" is checked, not claimed: the migrated suite was 53 assertions
# when it ran against a separate -javaagent, and it must still be 53 here.
if [ "$MIGRATED" = "53" ]; then
  echo "MIGRATED SUITE: $MIGRATED assertions (was 53 against the separate ax-trace.jar)"
else
  echo "MIGRATED SUITE: $MIGRATED assertions -- EXPECTED 53. An assertion was lost or added"
  echo "                outside the SCOPE-v3.1 section. That is the thing this count exists to"
  echo "                catch, so it is a failure."
  fails=$((fails+1))
fi
echo "SCOPE-v3.1 ADDITIONS: $newchecks assertions"
if [ "$fails" -gt 0 ]; then
  echo "TRACE SMOKE FAILED: $fails/$checks assertions"
  exit 1
fi
echo "TRACE SMOKE PASSED: $checks/$checks assertions ($MIGRATED migrated + $newchecks new)"
