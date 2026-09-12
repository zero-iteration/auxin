package ax.bench.agent;

import ax.bench.gen.ProbeEmitter;
import ax.bench.gen.ProbeStyle;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One agent, two jobs, selected by the agent argument string.
 *
 *   -javaagent:ax-bench.jar=g3            gate G3: condy install at load + strip on retransform
 *   -javaagent:ax-bench.jar=g4:MODE       gate G4: startup transform cost
 *
 * The two-transformer split is PLAN-v2 / E1: the INSTALLER registers canRetransform=false so it
 * runs first by spec and its output is replayed verbatim on every later retransform; the STRIPPER
 * registers canRetransform=true and is only ever driven by our own retransformClasses() call.
 *
 * Fail-open is enforced here the way the plan demands: a Throwable must never escape into app
 * code, and the failure must be counted, because the JVM silently swallows transformer exceptions.
 */
public final class BenchAgent {

    public static final AtomicLong transformFailures = new AtomicLong();
    public static final AtomicLong classesSeen = new AtomicLong();
    public static final AtomicLong classesTransformed = new AtomicLong();
    public static final AtomicLong bytesIn = new AtomicLong();
    public static final AtomicLong bytesOut = new AtomicLong();
    public static final AtomicLong probesInstalled = new AtomicLong();

    public static volatile Instrumentation INST;

    /** internal class name -> sorted "name+desc" -> probe index (C7: deterministic, not visit order). */
    public static final Map<String, Map<String, Integer>> INDEX = new LinkedHashMap<>();

    private BenchAgent() {}

    public static void premain(String args, Instrumentation inst) { init(args, inst); }
    public static void agentmain(String args, Instrumentation inst) { init(args, inst); }

    private static void init(String args, Instrumentation inst) {
        INST = inst;
        String a = args == null ? "" : args;
        if (a.startsWith("g3")) {
            inst.addTransformer(new G3Installer());          // canRetransform = false
            inst.addTransformer(new G3Stripper(), true);     // canRetransform = true
        } else if (a.startsWith("g4:")) {
            inst.addTransformer(new G4Transformer(G4Mode.valueOf(a.substring(3).toUpperCase())), false);
        }
    }

    // =================================================================== G3

    public static final String G3_PREFIX = "gt/bench/g3/Subject";

    /** Installs blind-store condy probes at initial class load. Never acts on a retransform. */
    static final class G3Installer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader cl, String name, Class<?> beingRedefined,
                                ProtectionDomain pd, byte[] buf) {
            try {
                if (name == null || !name.startsWith(G3_PREFIX) || beingRedefined != null) return null;
                boolean raw = name.endsWith("Raw");
                ClassNode cn = new ClassNode();
                new ClassReader(buf).accept(cn, 0);
                System.out.println("[installer] " + name + " classfile major=" + (cn.version & 0xFFFF)
                                   + " condyDescriptor=" + (raw ? "[Z (no CHECKCAST)" : "Ljava/lang/Object; + CHECKCAST"));

                TreeMap<String, MethodNode> sorted = new TreeMap<>();
                for (MethodNode m : cn.methods) {
                    if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                    if (m.name.equals("<clinit>")) continue;   // PLAN-v2: never <clinit>
                    sorted.put(m.name + m.desc, m);
                }
                ConstantDynamic condy = ProbeEmitter.condy(name, sorted.size(), raw);
                Map<String, Integer> idx = new LinkedHashMap<>();
                int i = 0;
                for (Map.Entry<String, MethodNode> e : sorted.entrySet()) {
                    idx.put(e.getKey(), i);
                    ProbeEmitter.instrumentGenerated(e.getValue(), ProbeStyle.BLIND, condy, new int[]{i});
                    System.out.println("[installer]   probe " + i + " -> " + e.getKey());
                    i++;
                }
                INDEX.put(name, idx);
                probesInstalled.addAndGet(i);
                ClassWriter cw = new ClassWriter(0);           // never COMPUTE_FRAMES
                cn.accept(cw);
                byte[] out = cw.toByteArray();
                G3Record.installed.put(name, out);
                return out;
            } catch (Throwable t) {
                transformFailures.incrementAndGet();
                System.out.println("[installer] FAIL-OPEN on " + name + ": " + t);
                return null;
            }
        }
    }

    /** Removes the probe instruction sequences. Adds/removes no field and no method. */
    public static final class G3Stripper implements ClassFileTransformer {
        public static volatile boolean armed = false;
        public static volatile int lastRemoved = -1;

        @Override
        public byte[] transform(ClassLoader cl, String name, Class<?> beingRedefined,
                                ProtectionDomain pd, byte[] buf) {
            try {
                if (name == null || !name.startsWith(G3_PREFIX) || beingRedefined == null || !armed) return null;
                ClassNode cn = new ClassNode();
                new ClassReader(buf).accept(cn, 0);
                int removed = 0;
                for (MethodNode m : cn.methods) {
                    AbstractInsnNode in = m.instructions.getFirst();
                    while (in != null) {
                        AbstractInsnNode next = in.getNext();
                        if (in instanceof LdcInsnNode
                            && ((LdcInsnNode) in).cst instanceof ConstantDynamic
                            && ProbeEmitter.CONDY_NAME.equals(((ConstantDynamic) ((LdcInsnNode) in).cst).getName())) {
                            AbstractInsnNode c = in;
                            int guard = 0;
                            while (c != null && guard++ < 8) {
                                AbstractInsnNode d = c.getNext();
                                boolean end = c.getOpcode() == Opcodes.BASTORE;
                                m.instructions.remove(c);
                                c = d;
                                if (end) break;
                            }
                            next = c;
                            removed++;
                        }
                        in = next;
                    }
                }
                lastRemoved = removed;
                System.out.println("[stripper] " + name + ": removed " + removed + " probe(s)");
                ClassWriter cw = new ClassWriter(0);
                cn.accept(cw);
                byte[] out = cw.toByteArray();
                G3Record.stripped.put(name, out);
                return out;
            } catch (Throwable t) {
                transformFailures.incrementAndGet();
                System.out.println("[stripper] FAIL-OPEN on " + name + ": " + t);
                return null;
            }
        }
    }

    /** Byte-level record of what the installer emitted and what the stripper emitted. */
    public static final class G3Record {
        public static final Map<String, byte[]> installed = new LinkedHashMap<>();
        public static final Map<String, byte[]> stripped = new LinkedHashMap<>();
        private G3Record() {}
    }

    // =================================================================== G4

    public enum G4Mode {
        /** name-prefix check only, never parse, never rewrite: the transformer-callback tax alone. */
        NAMEONLY,
        /** parse EVERY class handed to us to decide, then instrument the matching ones. */
        PARSE,
        /** name-prefix first pass, then full parse + probe of the matching ones (production shape). */
        FULL
    }

    public static final String G4_PREFIX = "gt/g4/gen/";

    static final class G4Transformer implements ClassFileTransformer {
        private final G4Mode mode;
        G4Transformer(G4Mode mode) { this.mode = mode; }

        @Override
        public byte[] transform(ClassLoader cl, String name, Class<?> beingRedefined,
                                ProtectionDomain pd, byte[] buf) {
            try {
                classesSeen.incrementAndGet();
                if (beingRedefined != null) return null;
                boolean matches = name != null && name.startsWith(G4_PREFIX);

                if (mode == G4Mode.NAMEONLY) return null;

                if (mode == G4Mode.PARSE) {
                    ClassNode probe = new ClassNode();
                    new ClassReader(buf).accept(probe, 0);     // parse everything, even what we reject
                    if (!matches) return null;
                    return install(name, probe, buf);
                }

                if (!matches) return null;                     // FULL: cheap name-only first pass
                ClassNode cn = new ClassNode();
                new ClassReader(buf).accept(cn, 0);
                return install(name, cn, buf);
            } catch (Throwable t) {
                transformFailures.incrementAndGet();
                return null;                                    // fail open, always
            }
        }

        private byte[] install(String name, ClassNode cn, byte[] buf) {
            TreeMap<String, MethodNode> sorted = new TreeMap<>();
            for (MethodNode m : cn.methods) {
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (m.name.equals("<clinit>")) continue;
                sorted.put(m.name + m.desc, m);
            }
            ConstantDynamic condy = ProbeEmitter.condy(name, sorted.size());
            int i = 0;
            for (MethodNode m : sorted.values()) {
                ProbeEmitter.instrumentGenerated(m, ProbeStyle.BLIND, condy, new int[]{i++});
            }
            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            byte[] out = cw.toByteArray();
            classesTransformed.incrementAndGet();
            probesInstalled.addAndGet(i);
            bytesIn.addAndGet(buf.length);
            bytesOut.addAndGet(out.length);
            return out;
        }
    }
}
