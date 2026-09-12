#!/usr/bin/env bash
# ============================================================================
#  auxin -- CLASSLOADER ISOLATION SUITE
#
#  Does the java.lang.$Auxin bootstrap bridge (VALIDATION A10 / C33 / C34)
#  actually work when a class loader really is isolated? Three containers, none of
#  which needs Docker:
#
#    TEST 1  JPMS  -- real named modules on the module path, launched with -m;
#                     exported / opened / sealed variants; plus a custom ModuleLayer
#                     whose loader's parent is the bootstrap loader.
#    TEST 2  OSGi  -- Apache Felix 7.0.5 embedded, a real bundle, with and without
#                     org.osgi.framework.bootdelegation, plus one run on F2's
#                     ax.bridge.shape=field rollback so the old shape stays tested.
#    TEST 3  child-first -- a genuine parent-last loader (Tomcat WebappClassLoader /
#                     Spring Boot LaunchedClassLoader shape), including the fat-jar
#                     double class definition, a shadowed agent jar, and two ways of
#                     taking the bridge away to prove the fallback is a decision and
#                     not a crash.
#
#  Every assertion prints PASS or FAIL. The exit code is non-zero if any failed.
#
#  usage: ./run-isolation.sh [--jdk 11|17|21|all] [--only <substring>] [--keep]
# ============================================================================
set -uo pipefail

ISO="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ISO/.." && pwd)"
AGENT="$ROOT/modules/ax-agent/target/ax-agent.jar"
FELIX="$ISO/lib/org.apache.felix.framework-7.0.5.jar"
OUT="$ISO/out"
BUILD="$OUT/build"
LOGS="$OUT/logs"

JDK17="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
JDK11="$( (brew --prefix openjdk@11 2>/dev/null || echo /nonexistent) )/libexec/openjdk.jdk/Contents/Home"
JDK21="$( (brew --prefix openjdk@21 2>/dev/null || echo /nonexistent) )/libexec/openjdk.jdk/Contents/Home"

WHICH_JDK="all"
ONLY=""
for ((i=1; i<=$#; i++)); do
  case "${!i}" in
    --jdk)  j=$((i+1)); WHICH_JDK="${!j}" ;;
    --only) j=$((i+1)); ONLY="${!j}" ;;
    --keep) ;;
  esac
done

PASSED=0
FAILED=0
declare -a FAILURES=()

pass() { PASSED=$((PASSED+1)); echo "  PASS  $1"; }
fail() { FAILED=$((FAILED+1)); FAILURES+=("$1"); echo "  FAIL  $1"; }

hdr()  { echo; echo "=============================================================="; echo "== $*"; echo "=============================================================="; }
sub()  { echo; echo "-- $*"; }

# ---------------------------------------------------------------- build ----
build() {
  hdr "BUILD (javac from JDK 17, --release 11 so every class file is v55/condy-capable)"
  [ -f "$AGENT" ] || { echo "missing agent jar: $AGENT (run mvn -f modules/ax-agent package)"; exit 2; }
  if [ ! -f "$FELIX" ]; then
    echo "  fetching Apache Felix 7.0.5 from Maven Central (one jar, no transitive deps)"
    mkdir -p "$ISO/lib"
    mvn -q dependency:get -Dartifact=org.apache.felix:org.apache.felix.framework:7.0.5 \
        -Dtransitive=false || { echo "could not fetch Felix"; exit 2; }
    cp "$HOME/.m2/repository/org/apache/felix/org.apache.felix.framework/7.0.5/org.apache.felix.framework-7.0.5.jar" \
       "$FELIX" || exit 2
  fi
  local JAVAC="$JDK17/bin/javac" JAR="$JDK17/bin/jar" JAVA="$JDK17/bin/java"

  rm -rf "$BUILD" "$LOGS" "$OUT/dump" "$OUT/felix-cache"
  mkdir -p "$BUILD/mods" "$BUILD/cp" "$BUILD/harness" "$BUILD/rival" "$LOGS" "$OUT/dump"

  # --- the four JPMS modules. ax.iso.app is the authored one; the other three are
  # --- derived from it by package rename so the code under test is identical and only
  # --- the module declaration differs.
  for v in app open sealed layer; do
    local src="$BUILD/src/ax.iso.$v"
    mkdir -p "$src/iso/$v"
    for f in Target Main; do
      sed -e "s/^package iso\.app;/package iso.$v;/" \
          "$ISO/src/jpms/ax.iso.app/iso/app/$f.java" > "$src/iso/$v/$f.java"
    done
    case "$v" in
      app)    cp "$ISO/src/jpms/ax.iso.app/module-info.java" "$src/module-info.java" ;;
      open)   printf '/** exports AND opens: deep reflection into the module is permitted. */\nmodule ax.iso.open {\n    exports iso.open;\n    opens iso.open;\n}\n' > "$src/module-info.java" ;;
      sealed) printf '/** Neither exported nor opened: the strictest module the JPMS allows. */\nmodule ax.iso.sealed {\n}\n' > "$src/module-info.java" ;;
      layer)  printf '/** Loaded into a custom ModuleLayer by LayerCheck, never into the boot layer. */\nmodule ax.iso.layer {\n    exports iso.layer;\n}\n' > "$src/module-info.java" ;;
    esac
    mkdir -p "$BUILD/mods/ax.iso.$v"
    "$JAVAC" -nowarn --release 11 -d "$BUILD/mods/ax.iso.$v" $(find "$src" -name '*.java') || exit 2
  done
  echo "  built modules: $(ls "$BUILD/mods" | tr '\n' ' ')"

  # --- plain class-path application classes (child-first test) ---
  "$JAVAC" -nowarn --release 11 -d "$BUILD/cp" "$ISO/src/cp/iso/child/Widget.java" || exit 2

  # --- OSGi: iso.api goes on the class path (exported by the system bundle),
  # --- iso.osgi goes inside the bundle jar and nowhere else.
  mkdir -p "$BUILD/osgi-classes"
  "$JAVAC" -nowarn --release 11 -cp "$FELIX" -d "$BUILD/osgi-classes" \
      $(find "$ISO/src/osgi" -name '*.java') || exit 2
  cp -R "$BUILD/osgi-classes/iso/api" "$BUILD/cp/iso/"
  cat > "$BUILD/bundle-manifest.txt" <<'MF'
Bundle-ManifestVersion: 2
Bundle-SymbolicName: iso.osgi.bundle
Bundle-Name: auxin isolation test bundle
Bundle-Version: 1.0.0
Bundle-Activator: iso.osgi.Activator
Import-Package: org.osgi.framework,iso.api
MF
  (cd "$BUILD/osgi-classes" && "$JAR" --create --file "$BUILD/bundle.jar" \
      --manifest "$BUILD/bundle-manifest.txt" iso/osgi) || exit 2
  echo "  built bundle.jar: $(unzip -l "$BUILD/bundle.jar" | grep -c 'iso/osgi/.*class') classes, iso/api NOT included: $(unzip -l "$BUILD/bundle.jar" | grep -c 'iso/api' || true)"

  # --- class-path drivers (they are the only code that talks to the agent) ---
  "$JAVAC" -nowarn --release 11 -cp "$AGENT:$FELIX:$BUILD/cp" -d "$BUILD/harness" \
      $(find "$ISO/src/harness" -name '*.java') || exit 2

  # --- the rival agent ---
  "$JAVAC" -nowarn --release 11 -d "$BUILD/rival" "$ISO/src/rival/rival/RivalAgent.java" || exit 2
  printf 'Premain-Class: rival.RivalAgent\nCan-Redefine-Classes: false\n' > "$BUILD/rival-manifest.txt"
  (cd "$BUILD/rival" && "$JAR" --create --file "$BUILD/rival-agent.jar" \
      --manifest "$BUILD/rival-manifest.txt" rival) || exit 2

  # --- ONE build manifest covering every application class in the suite ---
  mkdir -p "$BUILD/allclasses"
  for d in "$BUILD"/mods/*/ "$BUILD/cp" "$BUILD/osgi-classes"; do
    while IFS= read -r f; do
      mkdir -p "$BUILD/allclasses/$(dirname "$f")"
      cp "$d/$f" "$BUILD/allclasses/$f"
    done < <(cd "$d" && find . -name '*.class' ! -name 'module-info.class' | sed 's|^\./||')
  done
  # one tier-2 method, so the suite can also show what the bridge CANNOT carry
  "$JAVA" -cp "$AGENT" io.auxin.agent.manifest.ManifestTool \
      "$BUILD/allclasses" "$BUILD/manifest.json" --artifact=isolation --buildSha=iso001 \
      "--tier2=iso.osgi.Service#alpha" || exit 2
}

# ------------------------------------------------------- assertion helpers ----
# count <classfile> <regex>
count() { "$JAVAP" -v -p -c "$1" 2>/dev/null | grep -cE "$2" || true; }

cp_has() {  # cp_has <label> <classfile> <regex>
  if [ ! -f "$2" ]; then fail "$1 (no dumped class at $2)"; return; fi
  if [ "$(count "$2" "$3")" -gt 0 ]; then pass "$1"; else fail "$1 (no match for '$3' in $(basename "$2"))"; fi
}
cp_not() {  # cp_not <label> <classfile> <regex>
  if [ ! -f "$2" ]; then fail "$1 (no dumped class at $2)"; return; fi
  local n; n="$(count "$2" "$3")"
  if [ "$n" -eq 0 ]; then pass "$1"; else fail "$1 ($n matches for '$3' in $(basename "$2"))"; fi
}

# The full A10 constant-pool claim for a class instrumented through the bridge. True of BOTH
# bridge shapes -- that is the point: F2 changed how the probe array is delivered without
# weakening this in any way.
assert_bridge_shape() {  # assert_bridge_shape <classfile> <what>
  local f="$1" what="$2"
  cp_has "$what: constant pool references java/lang/\$Auxin.data" "$f" 'java/lang/\$Auxin\.data'
  cp_has "$what: probe array arrives through java/lang/Object.equals"   "$f" 'java/lang/Object\.equals'
  cp_not "$what: constant pool references NOTHING from io/auxin"  "$f" 'io/auxin'
}

# F2: on top of the A10 claim, the bridge now KEEPS condy -- the BootstrapMethods entry is
# REF_invokeStatic on the class's OWN synthetic $axInit, so no field is added, interfaces are
# instrumentable, and lookup.lookupClass() hands Tier-1b a strip handle.
assert_self_bsm_shape() {  # assert_self_bsm_shape <classfile> <what> <owner/internal/name>
  local f="$1" what="$2" owner="$3"
  assert_bridge_shape "$f" "$what"
  cp_has "$what: BootstrapMethods names the class's OWN \$axInit" \
      "$f" "REF_invokeStatic ${owner}\.\\\$axInit"
  cp_has "$what: the probe array arrives by condy, not by a field read" \
      "$f" 'ldc +#[0-9]+ +// +Dynamic #0:\$axProbes'
  cp_not "$what: NO \$axProbes field was added (a retransform therefore stays legal)" \
      "$f" 'boolean\[\] \$axProbes'
  cp_has "$what: \$axInit guards the F3 hazard with INSTANCEOF [Z" \
      "$f" '^ +[0-9]+: instanceof +#[0-9]+ +// +class "\[Z"'
  cp_has "$what: \$axInit captures lookup.lookupClass() (the Tier-1b strip handle)" \
      "$f" 'MethodHandles\$Lookup\.lookupClass'
}

# The pre-F2 shape, asserted for the ax.bridge.shape=field rollback scenario only.
assert_field_bridge_shape() {  # assert_field_bridge_shape <classfile> <what>
  local f="$1" what="$2"
  assert_bridge_shape "$f" "$what"
  cp_has "$what: the \$axProbes field IS added"      "$f" 'boolean\[\] \$axProbes'
  cp_has "$what: a <clinit> prologue IS added"     "$f" 'static \{\}'
  cp_not "$what: no condy, no self bootstrap method" "$f" '\$axInit'
}
assert_condy_shape() {   # the ordinary path, for contrast
  local f="$1" what="$2"
  cp_has "$what: condy bootstrap is io/auxin ProbeHolder"    "$f" 'io/auxin/agent/runtime/ProbeHolder\.bootstrap'
  cp_not "$what: no java.lang bridge reference (condy path)"       "$f" 'java/lang/\$Auxin'
}

# ------------------------------------------------------------- scenarios ----
# run_java <logname> <args...>  -> runs, echoes the driver's PASS/FAIL/info lines,
# folds them into the tally, and fails the scenario if the summary line is missing.
run_java() {
  local name="$1"; shift
  local log="$LOGS/$JDKNAME-$name.log"
  "$JAVA" "$@" > "$log" 2>&1
  local rc=$?
  grep -E '^  (PASS|FAIL|info)  ' "$log" || true
  local p f
  p=$(grep -c '^  PASS  ' "$log" || true)
  f=$(grep -c '^  FAIL  ' "$log" || true)
  PASSED=$((PASSED+p)); FAILED=$((FAILED+f))
  if [ "$f" -gt 0 ]; then
    while IFS= read -r line; do FAILURES+=("[$JDKNAME/$name] ${line#  FAIL  }"); done < <(grep '^  FAIL  ' "$log")
  fi
  if ! grep -qE '^(CHECKS|MODCHECK) .* (OK|FAILED)$' "$log"; then
    fail "$name: the JVM did not reach its summary line (exit $rc) -- see $log"
    echo "      ---- tail of $log ----"
    tail -25 "$log" | sed 's/^/      /'
  fi
  LAST_LOG="$log"
  return 0
}

axflags() {  # axflags <includePackages> <dumpSubdir>
  DUMP="$OUT/dump/$JDKNAME-$2"
  rm -rf "$DUMP"; mkdir -p "$DUMP"
  GT=( -Dax.include.packages="$1"
       -Dax.manifest="$BUILD/manifest.json"
       -Dax.environment=production
       -Dax.transport.enabled=false
       -Dax.dump.dir="$DUMP"
       -Dax.log.level=info )
}

run_for_jdk() {
  JAVA_HOME="$1"; JDKNAME="$2"
  JAVA="$JAVA_HOME/bin/java"; JAVAP="$JAVA_HOME/bin/javap"
  if [ ! -x "$JAVA" ]; then echo "  (skipping JDK $JDKNAME: not installed at $JAVA_HOME)"; return; fi
  hdr "JDK $JDKNAME -- $("$JAVA" -version 2>&1 | head -1)"

  # ---------------- TEST 1: JPMS ----------------
  for v in app open sealed; do
    local s="jpms-launch-$v"
    [ -n "$ONLY" ] && [[ "$s" != *"$ONLY"* ]] && continue
    sub "TEST 1a  $s -- java --module-path mods -m ax.iso.$v/iso.$v.Main"
    axflags "iso.$v" "$s"
    run_java "$s" -javaagent:"$AGENT" "${GT[@]}" \
        --module-path "$BUILD/mods" -m "ax.iso.$v/iso.$v.Main"
    assert_condy_shape "$DUMP/iso.$v.Target.class" "$s"
  done

  if [ -z "$ONLY" ] || [[ "jpms-classpath-driver" == *"$ONLY"* ]]; then
  sub "TEST 1b  jpms-classpath-driver -- named module + unnamed-module driver"
  axflags "iso.app" "jpms-classpath-driver"
  run_java "jpms-classpath-driver" -javaagent:"$AGENT" "${GT[@]}" \
      -Diso.agentJar="$AGENT" \
      -cp "$BUILD/harness" --module-path "$BUILD/mods" \
      --add-modules ax.iso.app,ax.iso.sealed iso.check.JpmsCheck
  assert_condy_shape "$DUMP/iso.app.Target.class" "jpms-classpath-driver"
  cp_not "jpms-classpath-driver: the control module was never dumped (never transformed)" \
      "$DUMP/iso.app.Target.class" 'ZZZ-never-matches'
  if [ -f "$DUMP/iso.sealed.Target.class" ]; then
    fail "jpms-classpath-driver: control class iso.sealed.Target WAS transformed (should be out of scope)"
  else
    pass "jpms-classpath-driver: control class iso.sealed.Target was never transformed"
  fi
  fi

  if [ -z "$ONLY" ] || [[ "jpms-custom-layer" == *"$ONLY"* ]]; then
  sub "TEST 1d  jpms-custom-layer -- named module, custom layer, bootstrap-parent loader"
  axflags "iso.layer" "jpms-custom-layer"
  run_java "jpms-custom-layer" -javaagent:"$AGENT" "${GT[@]}" \
      -Diso.mods="$BUILD/mods" -cp "$BUILD/harness" iso.check.LayerCheck
  assert_self_bsm_shape "$DUMP/iso.layer.Target.class" "jpms-custom-layer" "iso/layer/Target"
  fi

  # ---------------- TEST 2: OSGi / Felix ----------------
  # strict-fieldshape is the same Felix configuration as strict, run with
  # ax.bridge.shape=field: F2's rollback switch, tested in the container F2 is about.
  for m in implicit strict bootdelegation bootdelegation-app strict-fieldshape; do
    local s="osgi-felix-$m"
    [ -n "$ONLY" ] && [[ "$s" != *"$ONLY"* ]] && continue
    sub "TEST 2  $s"
    axflags "iso.osgi" "$s"
    rm -rf "$OUT/felix-cache-$m"
    SHAPE=()
    [ "$m" = "strict-fieldshape" ] && SHAPE=( -Dax.bridge.shape=field )
    run_java "$s" -javaagent:"$AGENT" "${GT[@]}" "${SHAPE[@]+"${SHAPE[@]}"}" \
        -Diso.mode="$m" -Diso.bundleJar="$BUILD/bundle.jar" \
        -Diso.felixCache="$OUT/felix-cache-$m" \
        -cp "$BUILD/harness:$FELIX:$BUILD/cp" iso.check.OsgiCheck
    case "$m" in
      # F1 FIXED: `implicit` moved from condy to bridge. Felix's felix.bootdelegation.implicit
      # decides delegation by WALKING THE CALL STACK, so the old Class.forName probe answered
      # "visible" from the agent's stack and then threw from bundle code. LoaderVisibility now
      # requires a parent-chain walk AND Class identity, so implicit correctly takes the bridge.
      #
      # F2 FIXED: a bridged class keeps condy, with its bootstrap method on itself.
      bootdelegation-app) assert_condy_shape "$DUMP/iso.osgi.Service.class" "$s" ;;
      strict-fieldshape)  assert_field_bridge_shape "$DUMP/iso.osgi.Service.class" "$s" ;;
      *)                  assert_self_bsm_shape "$DUMP/iso.osgi.Service.class" "$s" \
                              "iso/osgi/Service" ;;
    esac
    # F2's headline: the bundle's INTERFACE is instrumented again. It cannot be, in the field
    # shape -- an interface cannot hold a mutable static field -- so there is no dumped class.
    if [ "$m" = "strict-fieldshape" ]; then
      if [ -f "$DUMP/iso.osgi.Helper.class" ]; then
        fail "$s: the interface WAS instrumented under the field shape (impossible)"
      else
        pass "$s: the interface was not instrumented (field shape cannot; this is the F2 defect)"
      fi
    elif [ "$m" != "bootdelegation-app" ]; then
      assert_self_bsm_shape "$DUMP/iso.osgi.Helper.class" "$s (INTERFACE)" "iso/osgi/Helper"
    fi
  done

  # ---------------- TEST 3: child-first ----------------
  for m in condy bridge; do
    local s="child-first-$m"
    [ -n "$ONLY" ] && [[ "$s" != *"$ONLY"* ]] && continue
    sub "TEST 3  $s"
    axflags "iso.child" "$s"
    run_java "$s" -javaagent:"$AGENT" "${GT[@]}" \
        -Diso.mode="$m" -Diso.appClasses="$BUILD/cp" -Diso.agentJar="$AGENT" \
        -cp "$BUILD/harness:$BUILD/cp" iso.check.ChildFirstCheck
    # The app-loader copy is always condy; only the child-first copy can take the bridge.
    # Both copies dump to the same file name, second write wins, so assert on mode.
    if [ "$m" = "bridge" ]; then
      assert_self_bsm_shape "$DUMP/iso.child.Widget.class" "$s (child-first copy, dumped last)" \
          "iso/child/Widget"
    else
      assert_condy_shape "$DUMP/iso.child.Widget.class" "$s"
    fi
  done

  if [ -z "$ONLY" ] || [[ "child-first-skip-bridge-disabled" == *"$ONLY"* ]]; then
  sub "TEST 3c  child-first-skip-bridge-disabled -- ax.bridge.enabled=false"
  axflags "iso.child" "child-first-skip-bridge-disabled"
  run_java "child-first-skip-bridge-disabled" -javaagent:"$AGENT" "${GT[@]}" \
      -Dax.bridge.enabled=false \
      -Diso.mode=skip -Diso.appClasses="$BUILD/cp" -Diso.agentJar="$AGENT" \
      -cp "$BUILD/harness:$BUILD/cp" iso.check.ChildFirstCheck
  if grep -q 'bridge=notAttempted' "$LAST_LOG"; then
    pass "child-first-skip-bridge-disabled: agent reports bridge=notAttempted"
  else
    fail "child-first-skip-bridge-disabled: expected bridge=notAttempted in the armed line"
  fi
  fi

  if [ -z "$ONLY" ] || [[ "rival-wins" == *"$ONLY"* ]]; then
  sub "TEST 3d  rival-wins -- a second agent owns java.lang.\$Auxin"
  axflags "iso.child" "rival-wins"
  run_java "rival-wins" -javaagent:"$BUILD/rival-agent.jar" -javaagent:"$AGENT" "${GT[@]}" \
      -Diso.mode=skip -Diso.appClasses="$BUILD/cp" -Diso.agentJar="$AGENT" \
      -cp "$BUILD/harness:$BUILD/cp" iso.check.ChildFirstCheck
  if grep -q '\[rival\] CLAIMED' "$LAST_LOG"; then
    pass "rival-wins: the rival agent really did define java.lang.\$Auxin first"
  else
    fail "rival-wins: the rival agent did not claim the name -- test is meaningless"
  fi
  if grep -q 'bridge=alreadyDefinedByAnotherAgent' "$LAST_LOG"; then
    pass "rival-wins: ax-agent detected the collision (bridge=alreadyDefinedByAnotherAgent)"
  else
    fail "rival-wins: ax-agent did not report alreadyDefinedByAnotherAgent"
  fi
  if grep -q 'bootstrap bridge unavailable' "$LAST_LOG"; then
    pass "rival-wins: the fallback was logged once, as a warning, not an exception"
  else
    fail "rival-wins: no fallback warning logged"
  fi
  fi

  if [ -z "$ONLY" ] || [[ "bridge-data-tamper" == *"$ONLY"* ]]; then
  sub "TEST 3f  bridge-data-tamper -- ADVERSARIAL: a third party overwrites java.lang.\$Auxin.data"
  axflags "iso.child" "bridge-data-tamper"
  run_java "bridge-data-tamper" -javaagent:"$AGENT" -javaagent:"$BUILD/rival-agent.jar" "${GT[@]}" \
      -Drival.mode=tamper \
      -Diso.mode=tamper -Diso.appClasses="$BUILD/cp" -Diso.agentJar="$AGENT" \
      -cp "$BUILD/harness:$BUILD/cp" iso.check.ChildFirstCheck
  if grep -q '\[rival\] TAMPERED' "$LAST_LOG"; then
    pass "bridge-data-tamper: the tamper really happened"
  else
    fail "bridge-data-tamper: the tamper did not happen -- test is meaningless"
  fi
  fi

  if [ -z "$ONLY" ] || [[ "bridge-data-tamper-late" == *"$ONLY"* ]]; then
  sub "TEST 3g  bridge-data-tamper-late -- ADVERSARIAL: the hook is taken AFTER the transform, the one window no transform-time check can close"
  axflags "iso.child" "bridge-data-tamper-late"
  run_java "bridge-data-tamper-late" -javaagent:"$AGENT" "${GT[@]}" \
      -Diso.mode=tamper-late -Diso.appClasses="$BUILD/cp" -Diso.agentJar="$AGENT" \
      -cp "$BUILD/harness:$BUILD/cp" iso.check.ChildFirstCheck
  assert_self_bsm_shape "$DUMP/iso.child.Widget.class" \
      "bridge-data-tamper-late (child-first copy)" "iso/child/Widget"

  # The counterfactual, in the same container on the same JDK: the pre-F2 shape still breaks
  # application code here, because a guard branch cannot be added to somebody else's <clinit>.
  sub "TEST 3g  bridge-data-tamper-late-fieldshape -- the same tamper against F2's rollback switch"
  axflags "iso.child" "bridge-data-tamper-late-fieldshape"
  run_java "bridge-data-tamper-late-fieldshape" -javaagent:"$AGENT" "${GT[@]}" \
      -Dax.bridge.shape=field -Diso.bridgeShape=field \
      -Diso.mode=tamper-late -Diso.appClasses="$BUILD/cp" -Diso.agentJar="$AGENT" \
      -cp "$BUILD/harness:$BUILD/cp" iso.check.ChildFirstCheck
  assert_field_bridge_shape "$DUMP/iso.child.Widget.class" \
      "bridge-data-tamper-late-fieldshape (child-first copy)"
  fi

  if [ -z "$ONLY" ] || [[ "rival-loses" == *"$ONLY"* ]]; then
  sub "TEST 3e  rival-loses -- ax-agent claims the name first, the rival must not break anything"
  axflags "iso.child" "rival-loses"
  run_java "rival-loses" -javaagent:"$AGENT" -javaagent:"$BUILD/rival-agent.jar" "${GT[@]}" \
      -Diso.mode=bridge -Diso.appClasses="$BUILD/cp" -Diso.agentJar="$AGENT" \
      -cp "$BUILD/harness:$BUILD/cp" iso.check.ChildFirstCheck
  if grep -q '\[rival\] could NOT claim' "$LAST_LOG"; then
    pass "rival-loses: the rival lost the race and survived it"
  else
    fail "rival-loses: expected the rival to fail to claim the name"
  fi
  if grep -q 'bridge=installed' "$LAST_LOG"; then
    pass "rival-loses: ax-agent still owns a working bridge"
  else
    fail "rival-loses: ax-agent's bridge is not installed"
  fi
  assert_self_bsm_shape "$DUMP/iso.child.Widget.class" "rival-loses (child-first copy)" \
      "iso/child/Widget"
  fi
}

# ------------------------------------------------------------------ main ----
build
case "$WHICH_JDK" in
  11)  run_for_jdk "$JDK11" 11 ;;
  17)  run_for_jdk "$JDK17" 17 ;;
  21)  run_for_jdk "$JDK21" 21 ;;
  all) run_for_jdk "$JDK17" 17; run_for_jdk "$JDK11" 11; run_for_jdk "$JDK21" 21 ;;
  *)   echo "unknown --jdk $WHICH_JDK"; exit 2 ;;
esac

hdr "SUMMARY"
echo "  passed: $PASSED"
echo "  failed: $FAILED"
if [ "$FAILED" -gt 0 ]; then
  echo
  echo "  failing assertions:"
  for f in "${FAILURES[@]}"; do echo "    - $f"; done
  echo
  echo "==== ISOLATION SUITE FAILED ===="
  exit 1
fi
echo
echo "==== ISOLATION SUITE PASSED ($PASSED assertions) ===="
