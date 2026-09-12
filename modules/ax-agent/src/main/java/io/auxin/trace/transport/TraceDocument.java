package io.auxin.trace.transport;

import io.auxin.trace.runtime.Observation;
import io.auxin.trace.runtime.SiteRegistry;
import io.auxin.trace.runtime.TraceContext;
import io.auxin.trace.runtime.TraceFrame;
import io.auxin.trace.runtime.TraceHealth;
import io.auxin.trace.util.TJson;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the trace document. Runs on the dispatcher thread, <b>never</b> on an application
 * thread: JSON, the pass-through collapse and the size-delta arithmetic all happen after the
 * request has already been answered.
 *
 * <p>The shape is frozen in {@code TRACE-CONTRACT.md}. That file, and not
 * {@code docs/CONTRACTS.md}, is this module's contract — the aggregate wire protocol is a
 * different document on a different endpoint and the two must not be able to drift into each
 * other.
 */
public final class TraceDocument {

    private final boolean collapse;
    private final boolean timings;
    private final String artifact;
    private final String instanceId;
    private final String buildSha;

    public TraceDocument(boolean collapsePassThroughs, boolean recordTimings, String artifact,
                         String instanceId, String buildSha) {
        this.collapse = collapsePassThroughs;
        this.timings = recordTimings;
        this.artifact = artifact;
        this.instanceId = instanceId;
        this.buildSha = buildSha;
    }

    public String build(TraceContext ctx) {
        TJson j = new TJson(16384);
        j.objectStart();
        j.field("schemaVersion", 1);
        j.field("kind", "trace");
        j.field("traceId", ctx.traceId);
        if (!artifact.isEmpty()) j.field("artifact", artifact);
        if (!buildSha.isEmpty()) j.field("buildSha", buildSha);
        j.field("instanceId", instanceId);
        j.field("startedAtMs", ctx.startedAtMs);
        if (timings) j.field("durationUs", ctx.durationNanos() / 1000L);
        j.objectStart("request");
        j.field("method", ctx.requestMethod);
        j.field("path", ctx.requestPath);
        j.objectEnd();
        j.objectStart("limits");
        j.field("maxFrames", ctx.maxFrames);
        j.field("maxDepth", ctx.maxDepth);
        j.field("maxObsPerFrame", ctx.maxObsPerFrame);
        j.field("frameCapHit", ctx.frameCapHit());
        j.field("depthCapHit", ctx.depthCapHit());
        j.objectEnd();
        j.field("threadsJoined", ctx.threadsJoined);
        j.field("commonPoolJoins", ctx.commonPoolJoins);
        j.field("framesRecorded", ctx.frameCount());

        int[] collapsedCount = new int[2];              // {collapsed, folded}
        j.arrayStart("frames");
        writeChildren(j, ctx.root.children, collapsedCount);
        j.arrayEnd();
        j.field("framesCollapsed", collapsedCount[0]);
        j.field("framesFolded", collapsedCount[1]);
        // Fold this document's own numbers into the cumulative counters BEFORE writing the
        // health block, so a reader is not shown framesCollapsed: 12 in the tree next to
        // health.framesCollapsed: 0.
        if (collapsedCount[0] > 0) TraceHealth.FRAMES_COLLAPSED.addAndGet(collapsedCount[0]);
        if (collapsedCount[1] > 0) TraceHealth.FRAMES_FOLDED.addAndGet(collapsedCount[1]);
        TraceHealth.writeTo(j);
        j.objectEnd();
        return j.toString();
    }

    /**
     * THE SECOND HALF OF THE VOLUME CONTROL: identical sibling frames are folded into one entry
     * carrying {@code repeated: n}.
     *
     * <p>Measured need, not a guess. The smoke suite's first run produced a <b>429 KB, 2,000
     * frame</b> document for a single request against a five-method service, and the bulk of it
     * was {@code score(Fare)} 127 times over — the same method, with observations that take only
     * a handful of distinct values. The field trial's 5,825 invocations across 196 methods is the
     * same shape at scale.
     *
     * <p>Two frames fold when they are the same method, threw the same thing (or nothing), and
     * their observations and branch arms are indistinguishable — i.e. when the document would
     * say exactly the same words twice. The count is kept, so "this ran 127 times and always did
     * this" is still in the document; what is removed is 126 copies of the sentence. Their
     * children are folded recursively by the same rule.
     *
     * <p>Order is first-seen, and folding is only among SIBLINGS under one parent, so the shape
     * of the call tree is preserved.
     */
    private void writeChildren(TJson j, List<TraceFrame> children, int[] counters) {
        if (children == null || children.isEmpty()) return;
        if (!collapse) {
            for (int i = 0; i < children.size(); i++) writeFrame(j, children.get(i), counters);
            return;
        }
        List<String> keys = new ArrayList<String>();
        List<TraceFrame> reps = new ArrayList<TraceFrame>();
        List<int[]> counts = new ArrayList<int[]>();
        for (int i = 0; i < children.size(); i++) {
            TraceFrame f = children.get(i);
            String key = signature(f);
            int at = keys.indexOf(key);
            if (at >= 0) {
                counts.get(at)[0]++;
                counters[1]++;
                // The representative keeps its own children; a folded sibling's children are
                // by construction indistinguishable, because the signature covers them.
            } else {
                keys.add(key);
                reps.add(f);
                counts.add(new int[]{1});
            }
        }
        for (int i = 0; i < reps.size(); i++) {
            reps.get(i).repeated = counts.get(i)[0];
            writeFrame(j, reps.get(i), counters);
        }
    }

    /**
     * What makes two sibling frames the same sentence. Deliberately includes the children's
     * signatures: two calls to the same method that then did DIFFERENT things inside must not
     * fold, or the document would hide the one that mattered.
     */
    private String signature(TraceFrame f) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(f.frameId).append('|').append(f.threwClass).append('|').append(f.via);
        sb.append('|').append(f.truncated ? 'T' : 'f');
        sig(sb, f.entryObs);
        sb.append("/out");
        sig(sb, f.exitObs);
        if (f.returnObs != null) {
            sb.append("/ret:").append(f.returnObs.kind).append(':').append(f.returnObs.num)
                    .append(':').append(f.returnObs.text);
        }
        if (f.arms != null) {
            for (int i = 0; i < f.arms.size(); i++) {
                TraceFrame.ArmRecord r = f.arms.get(i);
                SiteRegistry.ArmSite s = SiteRegistry.arm(r.armId);
                sb.append("/a:").append(r.armId).append(':')
                        .append(s == null ? "?" : (s.isSwitch() ? "c" + r.a : s.arm(r.a, r.b)));
            }
        }
        if (f.children != null) {
            for (int i = 0; i < f.children.size(); i++) {
                sb.append("/c(").append(signature(f.children.get(i))).append(')');
            }
        }
        return sb.toString();
    }

    private void sig(StringBuilder sb, List<Observation> obs) {
        if (obs == null) return;
        for (int i = 0; i < obs.size(); i++) {
            Observation o = obs.get(i);
            sb.append('/').append(o.name).append(':').append(o.kind).append(':').append(o.num)
                    .append(':').append(o.text);
            if (o.children != null) sig(sb, o.children);
        }
    }

    /**
     * THE FIRST HALF OF THE VOLUME CONTROL: pass-through frames are not emitted.
     *
     * <p>A frame that {@link TraceFrame#isPassThrough} calls a pass-through is elided; its
     * children are emitted in its place, and the count of elided ancestors travels with them as
     * {@code viaCollapsed}. The tree keeps its shape and loses only the nodes that said nothing
     * — what remains is the frames that THREW, took a recorded branch arm, or whose observations
     * changed between entry and exit.
     */
    private void writeFrame(TJson j, TraceFrame f, int[] collapsed) {
        if (collapse && f.isPassThrough() && f.children != null && !f.children.isEmpty()) {
            collapsed[0]++;
            for (int i = 0; i < f.children.size(); i++) {
                f.children.get(i).collapsed += f.collapsed + 1;
            }
            writeChildren(j, f.children, collapsed);
            return;
        }
        if (collapse && f.isPassThrough() && (f.children == null || f.children.isEmpty())) {
            collapsed[0]++;
            return;
        }

        SiteRegistry.FrameSite site = SiteRegistry.frame(f.frameId);
        j.objectStart();
        j.field("frame", site == null ? "unknown#" + f.frameId : site.label());
        if (site != null && site.line > 0) j.field("line", site.line);
        j.field("depth", f.depth);
        if (f.via != null) j.field("via", f.via);
        if (f.repeated > 1) j.field("repeated", f.repeated);
        if (f.collapsed > 0) j.field("viaCollapsed", f.collapsed);
        if (timings && f.endNanos > f.startNanos) {
            j.field("selfUs", (f.endNanos - f.startNanos) / 1000L);
        }
        if (!f.closed) j.field("open", true);
        if (f.truncated) j.field("truncated", true);
        if (f.threwClass != null) j.field("threw", f.threwClass);

        writeObs(j, "in", f.entryObs);
        writeObs(j, "out", f.exitObs);
        if (f.returnObs != null) {
            j.objectStart("returned");
            writeOne(j, f.returnObs);
            j.objectEnd();
        }
        writeDeltas(j, f);
        writeArms(j, f);

        if (f.children != null && !f.children.isEmpty()) {
            j.arrayStart("calls");
            // writeChildren, NOT a bare loop over writeFrame. Getting this wrong is how the
            // first measured run reported framesFolded=0 with 865 frames in the document: the
            // root's children were folded and every nested level was not.
            writeChildren(j, f.children, collapsed);
            j.arrayEnd();
        }
        j.objectEnd();
    }

    private void writeObs(TJson j, String key, List<Observation> obs) {
        if (obs == null || obs.isEmpty()) return;
        j.arrayStart(key);
        for (int i = 0; i < obs.size(); i++) {
            j.objectStart();
            writeOne(j, obs.get(i));
            j.objectEnd();
        }
        j.arrayEnd();
    }

    private void writeOne(TJson j, Observation o) {
        j.field("name", o.name);
        j.field("kind", o.kind);
        if (o.hasDouble) j.field("value", o.dbl);
        else if (Observation.SIZE.equals(o.kind) || Observation.NUM.equals(o.kind)
                || Observation.LEN.equals(o.kind)) {
            j.field("value", o.num);
        }
        if (o.text != null) j.field("text", o.text);
        if (o.children != null && !o.children.isEmpty()) {
            j.arrayStart("projected");
            for (int i = 0; i < o.children.size(); i++) {
                j.objectStart();
                writeOne(j, o.children.get(i));
                j.objectEnd();
            }
            j.arrayEnd();
        }
    }

    /**
     * THE HIGHEST-VALUE SIGNAL: "126 fares in, 94 out". A per-parameter size delta, computed
     * from the entry and exit observations of the same slot, localising where data was dropped
     * <b>without recording the data</b>.
     */
    private void writeDeltas(TJson j, TraceFrame f) {
        if (f.entryObs == null || f.exitObs == null) return;
        List<String> names = null;
        List<Long> deltas = null;
        int n = Math.min(f.entryObs.size(), f.exitObs.size());
        for (int i = 0; i < n; i++) {
            long d = f.sizeDelta(i);
            if (d == Long.MIN_VALUE || d == 0) continue;
            if (names == null) {
                names = new ArrayList<String>(2);
                deltas = new ArrayList<Long>(2);
            }
            names.add(f.entryObs.get(i).name);
            deltas.add(Long.valueOf(d));
        }
        if (names == null) return;
        j.arrayStart("sizeDeltas");
        for (int i = 0; i < names.size(); i++) {
            j.objectStart();
            j.field("name", names.get(i));
            j.field("in", f.entryObs.get(i).num);
            j.field("out", f.exitObs.get(i).num);
            j.field("delta", deltas.get(i).longValue());
            j.objectEnd();
        }
        j.arrayEnd();
    }

    /**
     * Branch arms, GROUPED by (site, arm) with a count.
     *
     * <p>The second half of the volume control, and it happens here rather than on the
     * application thread for the same reason the pass-through collapse does. A predicate inside
     * a loop — {@code if (!fare.isRefundable())} over 126 fares — produces 126 raw arm records
     * and exactly two useful facts: it took {@code then} 32 times and {@code else} 94 times. The
     * grouped form is what a human or an agent can read, the raw count is what tells them the
     * loop ran 126 times, and both are in the document.
     *
     * <p>Ordering is first-seen, so the arm a site took FIRST comes first — which is usually the
     * one a person reading a trace is asking about.
     */
    private void writeArms(TJson j, TraceFrame f) {
        if (f.arms == null || f.arms.isEmpty()) return;
        List<long[]> groups = new ArrayList<long[]>();   // {armId, a, b, count, switchKey}
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < f.arms.size(); i++) {
            TraceFrame.ArmRecord r = f.arms.get(i);
            SiteRegistry.ArmSite site = SiteRegistry.arm(r.armId);
            String arm = site == null ? "unknown"
                    : (site.isSwitch() ? "case:" + r.a : site.arm(r.a, r.b));
            String key = r.armId + "/" + arm;
            int at = labels.indexOf(key);
            if (at >= 0) {
                groups.get(at)[3]++;
            } else {
                labels.add(key);
                groups.add(new long[]{r.armId, r.a, r.b, 1});
            }
        }
        j.arrayStart("branches");
        for (int i = 0; i < groups.size(); i++) {
            long[] g = groups.get(i);
            SiteRegistry.ArmSite site = SiteRegistry.arm((int) g[0]);
            j.objectStart();
            if (site == null) {
                j.field("site", "unknown#" + g[0]);
                j.field("n", g[3]);
                j.objectEnd();
                continue;
            }
            if (site.line > 0) j.field("line", site.line);
            j.field("op", site.opcodeName());
            j.field("why", site.reason);
            if (site.isSwitch()) {
                j.field("arm", "case");
                j.field("case", g[1]);
            } else {
                j.field("arm", site.arm(g[1], g[2]));
                // The OPERANDS, not the objects. For a reference comparison these are already
                // reduced to one bit by TraceRuntime.armA/armAA before they arrive here.
                j.field("lhs", g[1]);
                if (isBinary(site.opcode)) j.field("rhs", g[2]);
            }
            j.field("n", g[3]);
            j.objectEnd();
        }
        j.arrayEnd();
    }

    private static boolean isBinary(int opcode) {
        return (opcode >= 159 && opcode <= 164)       // IF_ICMPEQ..IF_ICMPLE
                || opcode == 165 || opcode == 166;    // IF_ACMPEQ, IF_ACMPNE
    }
}
