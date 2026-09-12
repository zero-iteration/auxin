package io.auxin.trace.runtime;

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Every id the emitted bytecode carries is an index into this registry, and every id is assigned
 * <b>at transform time</b> — never derived from visit order at runtime, never from a hash of the
 * class bytes. A14 defect 2 (silent misattribution) applies here exactly as it applies to
 * tier-1's probe index.
 *
 * <p>The registry is the reason the probes themselves can be so small. A branch-arm probe is
 * {@code DUP; push siteId; INVOKESTATIC} — four bytes of operand and no metadata — because the
 * opcode, the source line, the method and the operand shape all live here, written once when
 * the class was rewritten and read once when the document is built. The application thread never
 * touches this class; it only carries integers.
 *
 * <p>Growth is under a lock and happens only on a class-load path. Reads are unsynchronised
 * array reads of a volatile snapshot: a site is registered strictly before any bytecode that
 * could name it exists, so a reader can never see an id it cannot resolve.
 */
public final class SiteRegistry {

    /** A method that carries trace instrumentation. */
    public static final class FrameSite {
        public final int id;
        public final String className;      // dotted
        public final String methodName;
        public final String desc;
        public final int line;
        /** True when this method's body contains a {@code parallelStream()}/{@code parallel()}. */
        public final boolean escapesToCommonPool;

        FrameSite(int id, String className, String methodName, String desc, int line,
                  boolean escapes) {
            this.id = id;
            this.className = className;
            this.methodName = methodName;
            this.desc = desc;
            this.line = line;
            this.escapesToCommonPool = escapes;
        }

        public String label() { return className + "#" + methodName + desc; }
    }

    public static final int OBS_PARAM_ENTRY = 0;
    public static final int OBS_PARAM_EXIT = 1;
    public static final int OBS_RETURN = 2;

    /** One observation point: a parameter at entry, the same parameter at exit, or the return. */
    public static final class ObsSite {
        public final int id;
        public final int frameId;
        public final int kind;
        public final int argIndex;          // -1 for a return
        public final String typeDesc;
        public final String name;           // "arg0" / "return": we have no parameter names

        ObsSite(int id, int frameId, int kind, int argIndex, String typeDesc, String name) {
            this.id = id;
            this.frameId = frameId;
            this.kind = kind;
            this.argIndex = argIndex;
            this.typeDesc = typeDesc;
            this.name = name;
        }
    }

    /** One conditional. The opcode lives here so the probe only has to carry the operands. */
    public static final class ArmSite {
        public final int id;
        public final int frameId;
        public final int opcode;
        public final int line;
        /** Why this conditional was selected: {@code call}, {@code field}, {@code null}, … */
        public final String reason;

        ArmSite(int id, int frameId, int opcode, int line, String reason) {
            this.id = id;
            this.frameId = frameId;
            this.opcode = opcode;
            this.line = line;
            this.reason = reason;
        }

        /** The arm a recorded operand pair actually took. Evaluated at document-build time. */
        public String arm(long a, long b) {
            switch (opcode) {
                case Opcodes.IFEQ:        return a == 0 ? "then" : "else";
                case Opcodes.IFNE:        return a != 0 ? "then" : "else";
                case Opcodes.IFLT:        return a < 0 ? "then" : "else";
                case Opcodes.IFGE:        return a >= 0 ? "then" : "else";
                case Opcodes.IFGT:        return a > 0 ? "then" : "else";
                case Opcodes.IFLE:        return a <= 0 ? "then" : "else";
                case Opcodes.IF_ICMPEQ:   return a == b ? "then" : "else";
                case Opcodes.IF_ICMPNE:   return a != b ? "then" : "else";
                case Opcodes.IF_ICMPLT:   return a < b ? "then" : "else";
                case Opcodes.IF_ICMPGE:   return a >= b ? "then" : "else";
                case Opcodes.IF_ICMPGT:   return a > b ? "then" : "else";
                case Opcodes.IF_ICMPLE:   return a <= b ? "then" : "else";
                case Opcodes.IFNULL:      return a == 0 ? "then" : "else";
                case Opcodes.IFNONNULL:   return a != 0 ? "then" : "else";
                case Opcodes.IF_ACMPEQ:   return a == b ? "then" : "else";
                case Opcodes.IF_ACMPNE:   return a != b ? "then" : "else";
                default:                  return "case";
            }
        }

        public String opcodeName() {
            switch (opcode) {
                case Opcodes.IFEQ:        return "IFEQ";
                case Opcodes.IFNE:        return "IFNE";
                case Opcodes.IFLT:        return "IFLT";
                case Opcodes.IFGE:        return "IFGE";
                case Opcodes.IFGT:        return "IFGT";
                case Opcodes.IFLE:        return "IFLE";
                case Opcodes.IF_ICMPEQ:   return "IF_ICMPEQ";
                case Opcodes.IF_ICMPNE:   return "IF_ICMPNE";
                case Opcodes.IF_ICMPLT:   return "IF_ICMPLT";
                case Opcodes.IF_ICMPGE:   return "IF_ICMPGE";
                case Opcodes.IF_ICMPGT:   return "IF_ICMPGT";
                case Opcodes.IF_ICMPLE:   return "IF_ICMPLE";
                case Opcodes.IFNULL:      return "IFNULL";
                case Opcodes.IFNONNULL:   return "IFNONNULL";
                case Opcodes.IF_ACMPEQ:   return "IF_ACMPEQ";
                case Opcodes.IF_ACMPNE:   return "IF_ACMPNE";
                case Opcodes.TABLESWITCH: return "TABLESWITCH";
                case Opcodes.LOOKUPSWITCH: return "LOOKUPSWITCH";
                default:                  return "OP" + opcode;
            }
        }

        public boolean isSwitch() {
            return opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH;
        }
    }

    /** 1 << 20 of each. Past it, instrumentation is refused for that site and counted. */
    private static final int MAX = 1 << 20;

    private static final Object LOCK = new Object();
    private static volatile FrameSite[] frames = new FrameSite[0];
    private static volatile ObsSite[] obs = new ObsSite[0];
    private static volatile ArmSite[] arms = new ArmSite[0];

    public static int registerFrame(String className, String methodName, String desc, int line,
                                    boolean escapesToCommonPool) {
        synchronized (LOCK) {
            FrameSite[] cur = frames;
            if (cur.length >= MAX) {
                TraceHealth.skip("frameIdSpaceExhausted");
                return -1;
            }
            FrameSite[] next = new FrameSite[cur.length + 1];
            System.arraycopy(cur, 0, next, 0, cur.length);
            next[cur.length] = new FrameSite(cur.length, className, methodName, desc, line,
                    escapesToCommonPool);
            frames = next;
            return cur.length;
        }
    }

    public static int registerObs(int frameId, int kind, int argIndex, String typeDesc,
                                  String name) {
        synchronized (LOCK) {
            ObsSite[] cur = obs;
            if (cur.length >= MAX) {
                TraceHealth.skip("obsIdSpaceExhausted");
                return -1;
            }
            ObsSite[] next = new ObsSite[cur.length + 1];
            System.arraycopy(cur, 0, next, 0, cur.length);
            next[cur.length] = new ObsSite(cur.length, frameId, kind, argIndex, typeDesc, name);
            obs = next;
            return cur.length;
        }
    }

    public static int registerArm(int frameId, int opcode, int line, String reason) {
        synchronized (LOCK) {
            ArmSite[] cur = arms;
            if (cur.length >= MAX) {
                TraceHealth.skip("armIdSpaceExhausted");
                return -1;
            }
            ArmSite[] next = new ArmSite[cur.length + 1];
            System.arraycopy(cur, 0, next, 0, cur.length);
            next[cur.length] = new ArmSite(cur.length, frameId, opcode, line, reason);
            arms = next;
            return cur.length;
        }
    }

    public static FrameSite frame(int id) {
        FrameSite[] a = frames;
        return id >= 0 && id < a.length ? a[id] : null;
    }

    public static ObsSite obs(int id) {
        ObsSite[] a = obs;
        return id >= 0 && id < a.length ? a[id] : null;
    }

    public static ArmSite arm(int id) {
        ArmSite[] a = arms;
        return id >= 0 && id < a.length ? a[id] : null;
    }

    public static int frameCount() { return frames.length; }

    public static int obsCount() { return obs.length; }

    public static int armCount() { return arms.length; }

    /** Ops/test hook: every frame site registered in this JVM, in id order. */
    public static List<FrameSite> allFrames() {
        FrameSite[] a = frames;
        List<FrameSite> out = new ArrayList<FrameSite>(a.length);
        for (int i = 0; i < a.length; i++) out.add(a[i]);
        return out;
    }

    private SiteRegistry() { throw new AssertionError(); }
}
