#!/usr/bin/env python3
"""
GATE G5 assertion engine.

Reads the artifacts of ONE harness run and emits one line per assertion:

    PASS <name>\t<detail>
    FAIL <name>\t<detail>
    INFO <name>\t<detail>

Two rules govern what is gated and what is merely recorded.

1. Nothing passes by absence. An assertion whose input file is missing is a FAIL, never a skip.
   "Two of three" is a failure, and so is "we could not tell".

2. Defects G5 discovered are pinned, not hidden. A configuration that is known to break is
   asserted to break in exactly the documented way (`G5-BUG-N.*Reproduced`), so the script is
   green while behaviour matches what RESULTS.md says and goes red the moment it changes --
   including when somebody fixes it. The bug names are in the assertion names so they cannot be
   read as passes.
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

RESULTS = []


def emit(status, name, detail=""):
    RESULTS.append((status, name, str(detail)))


def ok(cond, name, detail=""):
    emit("PASS" if cond else "FAIL", name, detail)
    return bool(cond)


# --------------------------------------------------------------------------------------
# ax-agent window
# --------------------------------------------------------------------------------------
def probe_bits(b64, count):
    raw = base64.b64decode(b64) if b64 else b""
    return [bool(raw[i >> 3] & (1 << (i & 7))) if (i >> 3) < len(raw) else False
            for i in range(count)]


def load_gt(run):
    p = os.path.join(run, "ax-window.json")
    if not os.path.exists(p):
        return None
    with open(p) as f:
        return json.load(f)


def gt_coverage_map(win, manifest):
    """(class, method, desc) -> invoked?, decoded from the agent's own wire format."""
    by_name = {c["name"]: c for c in manifest["classes"]}
    out = {}
    for cov in win.get("coverage", []):
        cls = cov["class"]
        entry = by_name.get(cls)
        if entry is None:
            continue
        bits = probe_bits(cov.get("probes", ""), entry["probeCount"])
        for m in entry["methods"]:
            out[(cls, m["name"], m["desc"])] = bits[m["idx"]]
    return out


def check_gt(run, gt_json, manifest, expect_zero):
    win = load_gt(run)
    if win is None:
        return ok(False, "GT.window", "ax-window.json missing")

    h = win["agentHealth"]
    inst = h["classesInstrumented"]
    cov = win.get("coverage", [])

    if expect_zero:
        # G5-BUG-1 FIXED. This branch used to assert the DEFECT: the shipped jar instrumented zero
        # classes with an empty skip map and zero failures -- indistinguishable from "all of this
        # code is dead". `IgnoreRules` now derives the agent's own package instead of vetoing the
        # whole `io/auxin/` prefix, an explicit scope beats a soft vendor veto, and every
        # veto of an in-scope class is counted AND warned. So the shipped jar must now work, and
        # it must never again be silent about doing nothing.
        ok(inst > 0, "G5-BUG-1.shippedJarInstruments",
           "the SHIPPED jar (no g5/src/patch) instruments this app: classesInstrumented=%d "
           "coverage=%d" % (inst, len(cov)))
        ok(h.get("scopeMatchedNothing") is not True, "G5-BUG-1.notSilentlyIdle",
           "scopeMatchedNothing=%s -- 'configured to work and did none' is now loud"
           % h.get("scopeMatchedNothing"))
        return inst > 0

    good = True
    good &= ok(inst >= 17, "GT.classesInstrumented", "%d (>=17 expected)" % inst)
    good &= ok(h["transformFailures"] == 0, "GT.transformFailures", h["transformFailures"])
    good &= ok(h.get("degraded") is False, "GT.notDegraded", h.get("degradedReason", ""))
    allowed = {"noManifestEntry", "notDynamicallyObservable"}
    skipped = dict(h["classesSkipped"])
    # `noEligibleMethods` is allowed, but ONLY as many times as the manifest actually contains a
    # class with nothing to probe. The real generator (ax-static) inventories a pure interface --
    # `io.auxin.demo.repo.Repository` -- with probeCount 0, where the previous in-agent stand-in
    # omitted such classes entirely and the agent reported the much vaguer `noManifestEntry`
    # instead. This is the better of the two: "the build knew about this class and there was
    # nothing in it to probe" is benign, whereas "the build has never heard of this class" is an
    # alarm. Bounded by count, so a real regression -- the agent finding a class empty that the
    # build says carries probes -- still fails here rather than hiding behind the reason name.
    if skipped.get("noEligibleMethods", 0) <= sum(
            1 for c in manifest["classes"] if c["probeCount"] == 0):
        skipped.pop("noEligibleMethods", None)
    unexpected = {k: v for k, v in skipped.items() if k not in allowed}
    good &= ok(not unexpected, "GT.noUnexpectedSkips", json.dumps(h["classesSkipped"]))
    good &= ok(h.get("livenessEvidence") is True, "GT.livenessEvidence",
               "environment=%s" % h.get("environment"))

    cmap = gt_coverage_map(win, manifest)
    loaded = set(win.get("classesLoaded", []))

    # --- the fixture's MEASURED ground truth -------------------------------------------
    bad = []
    for d in gt_json["deadMethods"]:
        key = (d["class"], d["method"], d["desc"])
        if key not in cmap:
            bad.append("%s#%s NO PROBE" % (d["class"], d["method"]))
        elif cmap[key]:
            bad.append("%s#%s INVOKED (fixture says dead)" % (d["class"], d["method"]))
    good &= ok(not bad, "GT.deadMethodsUnset",
               "%d dead methods, %d wrong: %s" % (len(gt_json["deadMethods"]), len(bad), bad))

    bad = []
    for r in gt_json["rareMethods"]:
        key = (r["class"], r["method"], r["desc"])
        if key not in cmap:
            bad.append("%s#%s NO PROBE" % (r["class"], r["method"]))
        elif not cmap[key]:
            bad.append("%s#%s NOT INVOKED (fixture says live)" % (r["class"], r["method"]))
    good &= ok(not bad, "GT.rareMethodsSet",
               "%d rare methods, %d wrong: %s" % (len(gt_json["rareMethods"]), len(bad), bad))

    never = [n["class"] for n in gt_json["neverLoadedClasses"]]
    present = [c for c in never if c in loaded]
    good &= ok(not present, "GT.neverLoadedAbsent",
               "%s absent" % never if not present else "present: %s" % present)

    for lni in gt_json["loadedButNeverInvokedClasses"]:
        cls = lni["class"]
        invoked = [k[1] for k, v in cmap.items() if k[0] == cls and v]
        good &= ok(cls in loaded and not invoked, "GT.loadedButNeverInvoked",
                   "%s loaded=%s invokedMethods=%s" % (cls, cls in loaded, invoked))

    # `classesLoaded` on the wire is really "classes we instrumented": a class with no
    # probe-eligible method (a pure interface) never appears. See G5-FINDING-4 in RESULTS.md.
    by_name = {c["name"]: c for c in manifest["classes"]}
    probeable = [c for c in gt_json["liveClasses"] if c in by_name]
    unprobeable = [c for c in gt_json["liveClasses"] if c not in by_name]
    missing = [c for c in probeable if c not in loaded]
    good &= ok(not missing, "GT.liveClassesLoaded",
               "%d probe-eligible live classes; missing %s" % (len(probeable), missing))
    if unprobeable:
        emit("INFO", "G5-FINDING-4.absentFromClassesLoaded",
             "%s -- no probe-eligible method, so never instrumented, so never reported "
             "as loaded (C10 wants 'loaded' to be a real signal)" % unprobeable)

    leaked = []
    for s in gt_json["syntheticOrBridgeMethods"]:
        entry = by_name.get(s["class"])
        if entry and any(m["name"] == s["method"] and m["desc"] == s["desc"]
                         for m in entry["methods"]):
            leaked.append("%s#%s%s" % (s["class"], s["method"], s["desc"]))
    good &= ok(not leaked, "GT.syntheticNotProbed",
               "%d synthetic/bridge methods, %d probed"
               % (len(gt_json["syntheticOrBridgeMethods"]), len(leaked)))
    return good


# --------------------------------------------------------------------------------------
# JaCoCo
# --------------------------------------------------------------------------------------
MISMATCH_RE = re.compile(r"do(es)? no?t? match", re.I)


def jacoco_report(run, java, cli, classfiles):
    execf = os.path.join(run, "jacoco-mid.exec")
    if not os.path.exists(execf) or os.path.getsize(execf) == 0:
        return None, "missing/empty jacoco-mid.exec"
    xml = os.path.join(run, "jacoco-mid.xml")
    proc = subprocess.run([java, "-jar", cli, "report", execf,
                           "--classfiles", classfiles, "--xml", xml, "--name", "g5"],
                          capture_output=True, text=True)
    log = proc.stdout + proc.stderr
    with open(os.path.join(run, "jacoco-report.log"), "w") as f:
        f.write(log)
    if proc.returncode != 0 or not os.path.exists(xml):
        return None, "jacococli rc=%d: %s" % (proc.returncode, log.strip()[:400])
    return xml, log


def jacoco_map(xml):
    out = {}
    for c in ET.parse(xml).iter("class"):
        cls = c.get("name").replace("/", ".")
        for m in c.findall("method"):
            covered = 0
            for ctr in m.findall("counter"):
                if ctr.get("type") == "INSTRUCTION":
                    covered = int(ctr.get("covered"))
            out[(cls, m.get("name"), m.get("desc"))] = covered
    return out


def check_jacoco(run, java, cli, classfiles, gt_json, manifest, gt_present, expect_mismatch):
    xml, log = jacoco_report(run, java, cli, classfiles)
    good = ok(xml is not None, "JAC.reportGenerated", log if xml is None else "ok")
    if xml is None:
        return False

    mismatch = [l.strip() for l in log.splitlines() if MISMATCH_RE.search(l)]
    named = sorted(set(re.findall(r"Execution data for class (\S+) does not match", log)))
    jmap = jacoco_map(xml)
    demo = {k: v for k, v in jmap.items() if k[0].startswith("io.auxin.demo")}
    with_data = {c for (c, _, _), v in demo.items() if v > 0}

    if expect_mismatch:
        # --- G5-BUG-2. ax-agent registered BEFORE JaCoCo changes the bytes JaCoCo sees, and
        # JaCoCo's class id is CRC64 of exactly those bytes. Reporting against the build's own
        # class files then silently yields 0%. VALIDATION A3 failure 3, caused by us.
        ok(len(named) >= 15 and len(with_data) == 0,
           "G5-BUG-2.jacocoClassIdMismatchReproduced",
           "%d classes reported 'does not match', %d classes carry coverage "
           "(ax-agent registered before JaCoCo)" % (len(named), len(with_data)))
        emit("INFO", "G5-BUG-2.jacocoCoverageLost",
             "JaCoCo reports 0% for the whole application; nothing in its output says why")
        return False

    good &= ok(not mismatch, "JAC.noClassIdMismatch",
               "; ".join(mismatch)[:600] if mismatch else "no mismatch warnings")
    good &= ok(len(with_data) >= 15, "JAC.classesWithCoverage",
               "%d demo classes carry covered instructions" % len(with_data))

    bad = ["%s#%s cov=%d" % (d["class"], d["method"], jmap[(d["class"], d["method"], d["desc"])])
           for d in gt_json["deadMethods"]
           if jmap.get((d["class"], d["method"], d["desc"]), 0) > 0]
    good &= ok(not bad, "JAC.deadMethodsUncovered", "%d wrong: %s" % (len(bad), bad))

    bad = ["%s#%s" % (r["class"], r["method"]) for r in gt_json["rareMethods"]
           if jmap.get((r["class"], r["method"], r["desc"]), 0) == 0]
    good &= ok(not bad, "JAC.rareMethodsCovered", "%d wrong: %s" % (len(bad), bad))

    # --- the strongest cross-check available: two independent coverage engines, same JVM,
    # same traffic, compared method by method.
    if gt_present:
        win = load_gt(run)
        if win is None:
            good &= ok(False, "JAC.agreesWithGt", "no gt window to compare")
        else:
            cmap = gt_coverage_map(win, manifest)
            by_name = {c["name"]: c for c in manifest["classes"]}
            disagree, compared = [], 0
            for (cls, name, desc), gt_hit in cmap.items():
                me = next(m for m in by_name[cls]["methods"]
                          if m["name"] == name and m["desc"] == desc)
                if not me.get("dynamicallyObservable", True):
                    continue                       # C51: no probe emitted, nothing to compare
                if (cls, name, desc) not in jmap:
                    continue                       # never loaded: JaCoCo has no data either
                compared += 1
                if (jmap[(cls, name, desc)] > 0) != gt_hit:
                    disagree.append("%s#%s%s gt=%s jacoco=%s"
                                    % (cls, name, desc, gt_hit, jmap[(cls, name, desc)] > 0))
            good &= ok(not disagree, "JAC.agreesWithGt",
                       "%d methods compared, %d disagree: %s"
                       % (compared, len(disagree), disagree[:6]))
    return good


# --------------------------------------------------------------------------------------
# OpenTelemetry
# --------------------------------------------------------------------------------------
SPAN_RE = re.compile(r"tracer: (io\.opentelemetry\.[^:\]]+)")


def check_otel(run):
    log = os.path.join(run, "stderr.log")
    if not os.path.exists(log):
        return ok(False, "OTEL.log", "stderr.log missing")
    tracers, total, postcheck, errors = {}, 0, 0, []
    with open(log, errors="replace") as f:
        for line in f:
            m = SPAN_RE.search(line)
            if m:
                total += 1
                tracers[m.group(1)] = tracers.get(m.group(1), 0) + 1
                if "g5postcheck" in line:
                    postcheck += 1
            elif " ERROR " in line and "otel" in line.lower():
                errors.append(line.strip()[:200])
    good = ok(total > 0, "OTEL.spansEmitted", "%d spans, tracers=%s" % (total, tracers))
    good &= ok("io.opentelemetry.java-http-server" in tracers, "OTEL.serverSpans",
               "com.sun.net.httpserver instrumentation fired under traffic")
    good &= ok("io.opentelemetry.http-url-connection" in tracers, "OTEL.clientSpans",
               "HttpURLConnection instrumentation fired under traffic")
    # Not a startup check: these are emitted AFTER ax-agent stripped a class and after a
    # foreign agent retransformed one.
    good &= ok(postcheck > 0, "OTEL.spansAfterStripAndRetransform",
               "%d spans on /g5postcheck" % postcheck)
    good &= ok(not errors, "OTEL.noErrors", "; ".join(errors[:3]) if errors else "none")
    return good


# --------------------------------------------------------------------------------------
# transformer ordering, from the passive observer agent
# --------------------------------------------------------------------------------------
def load_events(run):
    p = os.path.join(run, "xform-events.tsv")
    if not os.path.exists(p):
        return None
    rows = []
    with open(p) as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 8:
                continue
            rows.append({"phase": parts[0], "who": parts[1], "cls": parts[2],
                         "redef": parts[3] == "true", "len": int(parts[4]),
                         "gt": parts[5] == "1", "jac": parts[6] == "1", "otel": parts[7] == "1"})
    return rows


def check_order(run, obs_position, gt_present, jac_present, otel_present):
    rows = load_events(run)
    if rows is None:
        return ok(False, "ORDER.events", "xform-events.tsv missing")
    load = [r for r in rows if not r["redef"]]
    demo = [r for r in load if r["cls"].startswith("io/auxin/demo/")]
    good = ok(len(demo) > 0, "ORDER.demoClassesObserved", "%d load events" % len(demo))

    cap = [r for r in demo if r["who"] == "OBS-CAPABLE"]
    inc = [r for r in demo if r["who"] == "OBS-INCAPABLE"]

    # ax-agent's ProbeInstaller and JaCoCo's CoverageTransformer are both INCAPABLE, so their
    # output must already be in the bytes any CAPABLE transformer receives.
    if gt_present:
        n = sum(1 for r in cap if r["gt"])
        good &= ok(n > 0, "ORDER.gtRunsBeforeCapableTransformers",
                   "OBS-CAPABLE saw gt probes on %d/%d demo classes" % (n, len(cap)))
    if jac_present:
        n = sum(1 for r in cap if r["jac"])
        good &= ok(n > 0, "ORDER.jacocoRunsBeforeCapableTransformers",
                   "OBS-CAPABLE saw JaCoCo members on %d/%d demo classes" % (n, len(cap)))

    if otel_present:
        # OTel declares Can-Retransform-Classes: true, so nothing it writes may ever be handed
        # to a transformer in the incapable group.
        leaked = [r["cls"] for r in load if r["who"] == "OBS-INCAPABLE" and r["otel"]]
        good &= ok(not leaked, "ORDER.otelNeverSeenByIncapable",
                   "leaked: %s" % leaked[:5] if leaked
                   else "no OTel-instrumented bytes reached an incapable transformer")
        seen = sorted({r["cls"] for r in load if r["who"] == "OBS-CAPABLE" and r["otel"]})
        if obs_position == "last":
            good &= ok(len(seen) > 0, "ORDER.otelRunsBeforeLaterCapableTransformers",
                       "%d classes already carried OTel advice: %s" % (len(seen), seen[:3]))
        elif obs_position == "first":
            # Registered BEFORE OTel and also capable: within the capable group the order is
            # registration order, so OTel's output must NOT be visible here.
            good &= ok(len(seen) == 0, "ORDER.capableGroupIsRegistrationOrdered",
                       "OBS-CAPABLE registered before OTel and saw no OTel bytes"
                       if not seen else "unexpectedly saw %s" % seen[:5])

    if obs_position == "first" and (gt_present or jac_present):
        # >>> THE PROOF. Both observer transformers are registered in the SAME premain, before
        # JaCoCo's and before ax-agent's. The incapable one therefore sees original bytes; the
        # capable one, registered at the very same instant, sees their output. Capability
        # dominates -javaagent order.
        dirty = [r["cls"] for r in inc if (r["gt"] or r["jac"])]
        good &= ok(not dirty, "ORDER.observerFirst.incapableSawOriginalBytes",
                   "clean" if not dirty else "saw markers on %s" % dirty[:5])
        capm = [r["cls"] for r in cap if (r["gt"] or r["jac"])]
        good &= ok(len(capm) > 0, "ORDER.observerFirst.capableStillRanAfterLaterIncapable",
                   "%d classes: registered FIRST, still ordered after ax-agent/JaCoCo"
                   % len(capm))
    elif obs_position == "last" and (gt_present or jac_present):
        m = [r["cls"] for r in inc if (r["gt"] or r["jac"])]
        good &= ok(len(m) > 0, "ORDER.observerLast.incapableSawEarlierIncapableOutput",
                   "%d classes" % len(m))
    return good


# --------------------------------------------------------------------------------------
# Tier-1b
# --------------------------------------------------------------------------------------
def javap_counts(java_bin, path):
    javap = os.path.join(os.path.dirname(java_bin), "javap")
    try:
        out = subprocess.run([javap, "-v", "-p", "-c", path], capture_output=True,
                             text=True).stdout
    except Exception as e:
        return None
    return {
        "gtProbeLdc": len(re.findall(r"Dynamic #\d+:\$axProbes", out)),
        "jacocoLdc": len(re.findall(r"Dynamic #\d+:\$jacocoData", out)),
        "methodParametersAttr": len(re.findall(r"^\s+MethodParameters:", out, re.M)),
        "bytes": os.path.getsize(path),
    }


def check_bytecode(run, java_bin):
    """Reads the observer's byte dumps: the mechanism, not just the symptom."""
    d = os.path.join(run, "dump")
    if not os.path.isdir(d):
        return
    files = sorted(os.listdir(d))
    phases = {}
    for f in files:
        parts = f.split(".", 2)
        if len(parts) < 3:
            continue
        phases.setdefault(parts[1], []).append(os.path.join(d, f))
    for phase, key in (("load-and-traffic", "atLoad"),
                       ("gt-strip", "afterGtStrip"),
                       ("thirdparty-retransform", "afterForeignRetransform")):
        if phase not in phases:
            continue
        c = javap_counts(java_bin, phases[phase][-1])
        if c is None:
            continue
        emit("INFO", "BYTECODE.%s" % key,
             "Customer: gtProbes=%d jacocoProbes=%d MethodParameters=%d bytes=%d"
             % (c["gtProbeLdc"], c["jacocoLdc"], c["methodParametersAttr"], c["bytes"]))


def check_tier1b(run, obs_present, expect_noop):
    p = os.path.join(run, "tier1b.json")
    if not os.path.exists(p):
        return ok(False, "T1B.artifact", "tier1b.json missing")
    with open(p) as f:
        t = json.load(f)
    if "error" in t:
        return ok(False, "T1B.noError", t["error"])
    if not t.get("customerInstrumented"):
        return ok(False, "T1B.customerInstrumented", t.get("skipped", "?"))

    # G5-BUG-3 FIXED: `stripNow` used to return true whenever nothing threw, so it doubled as a
    # proxy for "the retransform was accepted". It now means "probes were actually removed", which
    # is a DIFFERENT fact -- and separating them is the whole point of the fix. Acceptance is
    # proven by nothing having thrown (stripFailures == 0); actual removal is asserted below,
    # gated on whether this ordering can remove anything at all.
    good = ok(t.get("A1.stripFailures", 1) == 0, "T1B.retransformAccepted",
              "retransformClasses accepted the Tier-1b bytes with every agent attached "
              "(stripFailures=%s)" % t.get("A1.stripFailures"))
    if not expect_noop:
        good &= ok(t.get("A1.stripNowReturned") is True, "T1B.probesActuallyRemoved",
                   "stripNow reported real removal (stripNowReturned=%s)"
                   % t.get("A1.stripNowReturned"))
    good &= ok(t.get("A1.stripFailures", 1) == 0, "T1B.noStripFailures",
               t.get("A1.stripFailures"))
    good &= ok(t.get("A2.anonymize.callable") is True, "T1B.classStillWorksAfterStrip",
               "Customer.anonymize() still returns a correct Customer after the strip")

    if expect_noop:
        # --- G5-BUG-3. When ax-agent is registered BEFORE another incapable transformer,
        # that transformer rewrites our probe's branch (JaCoCo inverts IFNE to IFEQ and
        # inserts its own probe into both arms). ProbeStripper matches an exact instruction
        # sequence, so it removes nothing -- and still reports success.
        ok(t.get("A2.stripEffective") is False, "G5-BUG-3.tier1bSilentNoOpReproduced",
           "strip reported success (stripNow=true, stripFailures=0, classesStripped+1) "
           "but the probe still fires: probeAfterCall=%s"
           % t.get("A2.anonymize.probeAfterCall"))
        emit("INFO", "G5-BUG-3.classesStrippedMetricOverstated",
             "health.classesStripped=%s while at least one of those strips changed nothing"
             % t.get("health.classesStripped"))
    else:
        good &= ok(t.get("A2.stripEffective") is True, "T1B.probeGoneAfterStrip",
                   "a never-invoked method ran post-strip and recorded nothing")

    if not obs_present:
        emit("INFO", "T1B.thirdPartyRetransform", "not exercised (no observer agent)")
        return good

    good &= ok(t.get("B1.thirdPartyRetransformed") is True, "T1B.foreignRetransformAccepted",
               "a foreign agent's retransformClasses() succeeded on a stripped class")

    # --- KNOWN-UNHANDLED (VALIDATION C32/C4 tension). Recorded, deliberately not gated. ---
    emit("INFO", "T1B.probesReappearedAfterForeignRetransform", t.get("B3.probesReappeared"))
    emit("INFO", "T1B.gtReStrippedAfterwards", t.get("B4.reStripped"))
    emit("INFO", "T1B.classesAutoStrippedDuringTraffic",
         t.get("A0.classesStrippedDuringTraffic"))
    emit("INFO", "T1B.autoStripCandidate", t.get("C0.autoStripCandidate"))
    emit("INFO", "T1B.autoStrippedClass.reStripDelta", t.get("C2.classesStrippedDelta"))
    # G5-FINDING-5: `-parameters` metadata on the LIVE class, measured by reflection.
    emit("INFO", "G5-FINDING-5.parameterNames",
         "beforeStrip=%s afterStrip=%s afterForeignRetransform=%s"
         % (t.get("A0.parameterNamesPresent"), t.get("A3.parameterNamesPresentAfterStrip"),
            t.get("B6.parameterNamesPresentAfterForeignRetransform")))
    return good


# --------------------------------------------------------------------------------------
# the application itself: did four agents change what the program does?
# --------------------------------------------------------------------------------------
def check_app(run, gt_json, exit_code):
    good = ok(exit_code == 0, "APP.exitZero", "exit=%d" % exit_code)
    out = os.path.join(run, "stdout.log")
    if not os.path.exists(out):
        return ok(False, "APP.stdout", "stdout.log missing")
    summary = None
    with open(out, errors="replace") as f:
        for line in f:
            if line.startswith("[demo] summary "):
                summary = json.loads(line[len("[demo] summary "):])
    if summary is None:
        return ok(False, "APP.summary", "no [demo] summary line -- the app did not finish")
    good &= ok(True, "APP.summary", "%d counters" % len(summary))

    dead = [k for k in summary if k.startswith("dead.")]
    good &= ok(not dead, "APP.noDeadCountersFired",
               "the fixture's own tripwires: %s" % (dead or "none fired"))

    short = []
    for r in gt_json["rareMethods"]:
        c = r.get("counter")
        if c and summary.get(c, 0) < r["minExpected"]:
            short.append("%s=%s (min %d)" % (c, summary.get(c, 0), r["minExpected"]))
    good &= ok(not short, "APP.rareCountersMet", "; ".join(short) if short else "all met")
    good &= ok(summary.get("driver.ioError", 0) == 0, "APP.noDriverIoErrors",
               summary.get("driver.ioError", 0))
    warm = gt_json["rareBranches"][0]
    good &= ok(summary.get(warm["counter"]) == warm["expected"], "APP.warmupBranchExact",
               "%s=%s (expected %s)" % (warm["counter"], summary.get(warm["counter"]),
                                        warm["expected"]))
    return good


# --------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--jdk", required=True)
    ap.add_argument("--agents", required=True, help="comma list of gt,jacoco,otel,obs")
    ap.add_argument("--obs-position", default="last", choices=["first", "last", "none"])
    ap.add_argument("--ground-truth", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--classfiles", required=True)
    ap.add_argument("--jacoco-cli", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--exit-code", type=int, required=True)
    ap.add_argument("--expect-gt-zero", action="store_true",
                    help="evidence run: the SHIPPED, unpatched ax-agent jar")
    ap.add_argument("--gt-before-jacoco", action="store_true",
                    help="ax-agent registered ahead of JaCoCo: expect G5-BUG-2 and G5-BUG-3")
    a = ap.parse_args()

    agents = set(x for x in a.agents.split(",") if x)
    gt_real = "gt" in agents and not a.expect_gt_zero
    with open(a.ground_truth) as f:
        gt_json = json.load(f)
    with open(a.manifest) as f:
        manifest = json.load(f)

    check_app(a.run, gt_json, a.exit_code)

    gt_ok = jac_ok = otel_ok = None
    if "gt" in agents:
        gt_ok = check_gt(a.run, gt_json, manifest, a.expect_gt_zero)
    if "jacoco" in agents:
        jac_ok = check_jacoco(a.run, a.java, a.jacoco_cli, a.classfiles, gt_json, manifest,
                              gt_real, a.gt_before_jacoco)
    if "otel" in agents:
        otel_ok = check_otel(a.run)
    if "obs" in agents:
        check_order(a.run, a.obs_position, gt_real, "jacoco" in agents, "otel" in agents)
    if gt_real:
        check_tier1b(a.run, "obs" in agents, a.gt_before_jacoco)
        check_bytecode(a.run, a.java)

    # >>> THE GATE. Two of three is a failure. Only applied to the documented-correct
    # -javaagent ordering; the deliberately-wrong orderings are graded by G5-BUG-2/3 above.
    if {"gt", "jacoco", "otel"} <= agents and gt_real and not a.gt_before_jacoco:
        ok(gt_ok and jac_ok and otel_ok, "ALL3.simultaneous",
           "gt=%s jacoco=%s otel=%s -- one JVM, under traffic" % (gt_ok, jac_ok, otel_ok))

    with open(os.path.join(a.run, "assertions.tsv"), "w") as f:
        for status, name, detail in RESULTS:
            f.write("%s\t%s\t%s\n" % (status, name, detail))
    for status, name, detail in RESULTS:
        print("%s\t%s\t%s" % (status, name, detail))
    return 0


if __name__ == "__main__":
    sys.exit(main())
