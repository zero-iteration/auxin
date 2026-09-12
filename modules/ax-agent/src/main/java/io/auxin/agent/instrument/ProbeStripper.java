package io.auxin.agent.instrument;

import io.auxin.agent.health.Health;
import io.auxin.agent.util.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier-1b: de-instrumentation. Registered with {@code addTransformer(t, true)} —
 * <b>canRetransform = true</b> — and acts ONLY on a retransform we ourselves triggered.
 *
 * <p>Once every probe of a class is set, the class has told us everything it can, and the
 * cheapest probe is the one that is no longer there. E2 proved this end to end: after
 * {@code retransformClasses}, a method that had never run executed and recorded nothing, and the
 * class stayed fully callable. Steady-state cost genuinely goes to zero.
 *
 * <p><b>Legality.</b> {@code Instrumentation}: "The retransformation must not add, remove or
 * rename fields or methods, change the signatures of methods, or change inheritance." Removing
 * instructions changes none of those — and this class asserts it before returning bytes.
 *
 * <p><b>The dead bootstrap method is left in place, deliberately.</b> A class instrumented
 * through the self-BSM bridge (F2) carries a synthetic
 * {@link ProbeEmitter#BRIDGE_BSM_NAME} method. Once the condy {@code LDC}s are gone that method
 * is unreachable, but removing it would be exactly the illegal schema change quoted above — the
 * method-count assertion below would catch it, and the whole retransform would be abandoned,
 * leaving the probes on the hot path. Leaving it costs a few dozen bytes of class data that is
 * never executed and never loaded into an instruction cache. Its body is also skipped when
 * scanning for probe shapes, so nothing inside it can be mistaken for one.
 */
public final class ProbeStripper implements ClassFileTransformer {

    /** Internal names we are currently retransforming. Nothing else is ever touched. */
    private final Set<String> armed =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * Outcome of the most recent retransform, per internal name: {@code [removed, blocked]}.
     *
     * <p>G5-BUG-3. {@code transform} can only answer the JVM with bytes or {@code null}, and
     * {@code null} means both "nothing needed doing" and "I could not match my own probe". The
     * caller used to infer success from "{@code retransformClasses} did not throw", so a strip
     * that removed nothing incremented {@code classesStripped}, left {@code stripFailures} at
     * zero and marked the class permanently stripped. The counts are published here so
     * {@code DrainThread} can tell the two apart.
     */
    private final ConcurrentHashMap<String, int[]> outcome =
            new ConcurrentHashMap<String, int[]>();

    /**
     * Classes {@code DrainThread} believes are de-instrumented.
     *
     * <p>The JVM replays {@code ProbeInstaller}'s cached load-time output as the input to the
     * capable transformers, so <b>any</b> third party's {@code retransformClasses} silently
     * reinstalls every probe we removed (G5 section 7). We are in that chain and are handed
     * those bytes, which makes this the one place the event is observable at all.
     */
    private final Set<String> believedStripped =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Classes whose probes have been seen again since the last drain cycle looked. */
    private final Set<String> reappeared =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public void arm(String internalName) {
        outcome.remove(internalName);
        armed.add(internalName);
    }

    public void disarm(String internalName) { armed.remove(internalName); }

    /** Probes removed by the last retransform of this class; 0 if the transformer never ran. */
    public int removedBy(String internalName) {
        int[] o = outcome.get(internalName);
        return o == null ? 0 : o[0];
    }

    /**
     * Probe array loads found in the last retransform of this class that could NOT be removed
     * because the surrounding instruction sequence was no longer ours. Non-zero means a foreign
     * transformer rewrote our probe (JaCoCo inverts our {@code IFNE} to {@code IFEQ} and puts
     * its own probe in both arms), not that the class was already clean.
     */
    public int blockedIn(String internalName) {
        int[] o = outcome.get(internalName);
        return o == null ? 0 : o[1];
    }

    /** Tier-1b believes this class is de-instrumented; watch for its probes coming back. */
    public void markStripped(String internalName) { believedStripped.add(internalName); }

    public void unmarkStripped(String internalName) {
        believedStripped.remove(internalName);
        reappeared.remove(internalName);
    }

    /** Takes and clears the set of classes whose probes were replayed by a foreign retransform. */
    public List<String> takeReappeared() {
        if (reappeared.isEmpty()) return Collections.emptyList();
        List<String> out = new java.util.ArrayList<String>(reappeared);
        reappeared.removeAll(out);
        return out;
    }

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if (classBeingRedefined == null) return null;    // initial load: not our business
            if (internalName == null || classfileBuffer == null) return null;
            if (!armed.contains(internalName)) {
                // Somebody else's retransform. We modify nothing, but if it is replaying probes
                // onto a class we believe we de-instrumented, this is the only notice we get.
                if (believedStripped.contains(internalName) && carriesProbeName(classfileBuffer)) {
                    reappeared.add(internalName);
                }
                return null;
            }

            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, 0);

            final int fieldsBefore = cn.fields.size();
            final int methodsBefore = cn.methods.size();

            int removed = 0;
            final int[] blocked = new int[1];
            for (int i = 0; i < cn.methods.size(); i++) {
                removed += strip(cn.methods.get(i), cn.name, blocked);
            }
            outcome.put(internalName, new int[]{removed, blocked[0]});
            if (removed == 0) return null;

            if (cn.fields.size() != fieldsBefore || cn.methods.size() != methodsBefore) {
                // unreachable by construction; if it ever happens, abandon the retransform
                // rather than hand the JVM an illegal schema change
                Log.warn("strip aborted for " + internalName + ": schema changed");
                Health.stripFailure();
                return null;
            }

            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            Log.debug("stripped " + removed + " probe(s) from " + internalName);
            return cw.toByteArray();
        } catch (Throwable t) {
            Health.stripFailure();
            Log.debug("strip failed for " + internalName, t);
            return null;   // fail open: the class keeps its probes, which is merely not free
        }
    }

    /**
     * Removes every probe sequence from one method. Leaves tier-2 instrumentation alone.
     *
     * @param blocked {@code blocked[0]} is incremented for every probe array load that is NOT
     *                followed by one of our own shapes. That is the G5-BUG-3 signature: the
     *                probe is demonstrably still there and we could not take it out, which is a
     *                different fact from "this method never had a probe" and must not be
     *                reported as a clean strip.
     */
    private int strip(MethodNode m, String owner, int[] blocked) {
        // The self-BSM bridge's own bootstrap method (F2). It contains no probe, it must not be
        // removed (schema change), and it must keep working for any condy in a class we only
        // partially strip — so it is never scanned at all.
        if (ProbeEmitter.BRIDGE_BSM_NAME.equals(m.name)
                && ProbeEmitter.BRIDGE_BSM_DESC.equals(m.desc)) {
            return 0;
        }
        InsnList insns = m.instructions;
        int removed = 0;
        AbstractInsnNode insn = insns.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            int len = probeLength(insn, owner);
            if (len > 0) {
                AbstractInsnNode cur = insn;
                for (int i = 0; i < len && cur != null; i++) {
                    AbstractInsnNode after = cur.getNext();
                    insns.remove(cur);
                    cur = after;
                }
                next = cur;
                removed++;
            } else if (probeArrayLoad(insn, owner) > 0) {
                blocked[0]++;
            }
            insn = next;
        }
        return removed;
    }

    /**
     * How many instructions, starting at {@code insn}, form one complete probe.
     *
     * <p>Four shapes exist, and all four must be recognised because the emitter's shape is a
     * runtime setting ({@code ax.probe.mode}, {@code ax.condy.descriptor}) while the bytes handed
     * to a stripper may have been written by an earlier configuration of the same JVM:
     * <pre>
     *   read-then-store: &lt;load&gt; ASTORE ALOAD push BALOAD IFNE ALOAD push ICONST_1 BASTORE
     *   blind:           &lt;load&gt; push ICONST_1 BASTORE
     *   &lt;load&gt; = LDC condy [+ CHECKCAST [Z]   |   GETSTATIC $axProbes
     * </pre>
     * The whole shape is verified before anything is removed — a partial match is left alone.
     *
     * @return the number of instructions to remove, or 0 when this is not the start of a probe.
     */
    private int probeLength(AbstractInsnNode insn, String owner) {
        final int load = probeArrayLoad(insn, owner);
        if (load == 0) return 0;

        // t.get(i) is the (i+1)-th instruction after insn, so the instruction following a
        // <load> prefix of `load` instructions is t.get(load - 1).
        final List<AbstractInsnNode> t = ProbeEmitter.next(insn, load + 9);
        final int b = load - 1;

        // blind: push ICONST_1 BASTORE
        if (ProbeEmitter.isPush(t.get(b))
                && op(t.get(b + 1)) == Opcodes.ICONST_1
                && op(t.get(b + 2)) == Opcodes.BASTORE) {
            return load + 3;
        }

        // read-then-store: ASTORE ALOAD push BALOAD IFNE ALOAD push ICONST_1 BASTORE L_end
        if (op(t.get(b)) != Opcodes.ASTORE || op(t.get(b + 1)) != Opcodes.ALOAD) return 0;
        final int slot = ((VarInsnNode) t.get(b)).var;
        if (((VarInsnNode) t.get(b + 1)).var != slot) return 0;
        if (!ProbeEmitter.isPush(t.get(b + 2))) return 0;
        if (op(t.get(b + 3)) != Opcodes.BALOAD || op(t.get(b + 4)) != Opcodes.IFNE) return 0;
        if (op(t.get(b + 5)) != Opcodes.ALOAD || ((VarInsnNode) t.get(b + 5)).var != slot) return 0;
        if (!ProbeEmitter.isPush(t.get(b + 6))) return 0;
        if (op(t.get(b + 7)) != Opcodes.ICONST_1 || op(t.get(b + 8)) != Opcodes.BASTORE) return 0;
        // the branch must land exactly after the store, or this is somebody else's code
        if (!(t.get(b + 9) instanceof LabelNode)
                || ((JumpInsnNode) t.get(b + 4)).label != t.get(b + 9)) {
            return 0;
        }
        // The label and the frame that follows it are deliberately NOT removed: the label may be
        // one the original code already owned (a method starting with a loop header), and a
        // leftover SAME frame at offset 0 is legal, verifiable and costs 3 bytes. Removing
        // instructions only is also what keeps the retransform legal — no field, no method, no
        // signature changes.
        return load + 9;
    }

    /**
     * Is {@code insn} a load of THIS class's probe array, and how many instructions is it?
     *
     * <p>The anchor of a probe, and — independently of whether the rest of the shape still
     * matches — proof that the class is still instrumented. That second use is what makes
     * G5-BUG-3 detectable: when JaCoCo has rewritten the branch around it, this still returns
     * non-zero while {@link #probeLength} returns 0.
     *
     * @return 1 or 2 (a condy declared {@code Ljava/lang/Object;} is followed by CHECKCAST),
     *         or 0 when this is not a probe array load.
     */
    private static int probeArrayLoad(AbstractInsnNode insn, String owner) {
        if (insn instanceof LdcInsnNode) {
            Object cst = ((LdcInsnNode) insn).cst;
            if (!(cst instanceof ConstantDynamic)
                    || !ProbeEmitter.PROBE_NAME.equals(((ConstantDynamic) cst).getName())) {
                return 0;
            }
            // CHECKCAST [Z is present only when the condy is declared Ljava/lang/Object;
            AbstractInsnNode next = insn.getNext();
            boolean checkcast = next instanceof TypeInsnNode
                    && next.getOpcode() == Opcodes.CHECKCAST
                    && ProbeEmitter.PROBE_DESC.equals(((TypeInsnNode) next).desc);
            return checkcast ? 2 : 1;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            if (f.getOpcode() != Opcodes.GETSTATIC
                    || !ProbeEmitter.PROBE_NAME.equals(f.name)
                    || !ProbeEmitter.PROBE_DESC.equals(f.desc)
                    || !owner.equals(f.owner)) {
                return 0;
            }
            return 1;
        }
        return 0;
    }

    /**
     * Does this class file still name our probe constant? A raw constant-pool scan for the UTF-8
     * bytes of {@code $axProbes} — no {@code ClassReader}, no parsing, because this runs on
     * bytes handed to us by somebody else's retransform and must cost almost nothing. A false
     * positive is harmless: it costs one extra bounded strip attempt.
     */
    private static boolean carriesProbeName(byte[] classfile) {
        final String name = ProbeEmitter.PROBE_NAME;
        final int n = name.length();
        final int limit = classfile.length - n;
        final byte first = (byte) name.charAt(0);
        for (int i = 0; i <= limit; i++) {
            if (classfile[i] != first) continue;
            int j = 1;
            while (j < n && classfile[i + j] == (byte) name.charAt(j)) j++;
            if (j == n) return true;
        }
        return false;
    }

    private static int op(AbstractInsnNode insn) {
        return insn == null ? -1 : insn.getOpcode();
    }
}
