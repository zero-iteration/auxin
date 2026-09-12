package ax.bench.g2;

import ax.bench.gen.ClassFileStats;
import ax.bench.gen.ProbeEmitter;
import ax.bench.gen.ProbeStyle;
import ax.bench.gen.SimpleLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Takes the javac-compiled chain classes and installs Tier-1 probes into them with raw ASM,
 * exactly as the real ProbeInstaller would: one probe at the entry of every method, condy array,
 * ClassWriter(0), no COMPUTE_FRAMES.
 *
 * Probe indices come from a sorted (name+descriptor) key, not from ASM visit order - PLAN-v2 C7.
 *
 * Constructors are skipped here. Tier-1 proper does probe them (JaCoCo does too); they are
 * excluded from G2 only because they are not on the hot path and probing before the super() call
 * would add an unrelated verifier question to an inlining experiment.
 * The hoisted arms are not offered: parking the array in a new local requires rewriting every
 * pre-existing StackMapTable frame in the method (JaCoCo shifts all locals up by one to do it).
 */
public final class ChainInstrumenter {

    public static final String[] CHAIN = {
        "ax.bench.g2.chain.Point",
        "ax.bench.g2.chain.Mixer",
        "ax.bench.g2.chain.Engine",
    };

    private ChainInstrumenter() {}

    public static byte[] raw(String binaryName) {
        String res = "/" + binaryName.replace('.', '/') + ".class";
        try (InputStream in = ChainInstrumenter.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("missing " + res);
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] instrument(byte[] original, String internalName, ProbeStyle style) {
        if (style == ProbeStyle.NONE) return original;
        if (style.hoists()) throw new UnsupportedOperationException(
            "hoisted arms need full StackMapTable rewriting on pre-compiled methods; G1 only");

        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);

        // C7: deterministic, sorted, build-time-style index assignment
        TreeMap<String, MethodNode> sorted = new TreeMap<>();
        for (MethodNode m : cn.methods) {
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            if (m.name.equals("<init>") || m.name.equals("<clinit>")) continue;
            sorted.put(m.name + m.desc, m);
        }
        ConstantDynamic condy = ProbeEmitter.condy(internalName, sorted.size());
        int i = 0;
        for (MethodNode m : sorted.values()) {
            ProbeEmitter.instrumentGenerated(m, style, condy, new int[]{i++});
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static Map<String, byte[]> defs(ProbeStyle style) {
        Map<String, byte[]> defs = new LinkedHashMap<>();
        for (String bin : CHAIN) {
            defs.put(bin, instrument(raw(bin), bin.replace('.', '/'), style));
        }
        return defs;
    }

    public static ChainWork newEngine(ProbeStyle style) {
        try {
            ClassLoader cl = new SimpleLoader(ChainWork.class.getClassLoader(), defs(style), true);
            return (ChainWork) cl.loadClass("ax.bench.g2.chain.Engine")
                                 .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build chain for " + style, e);
        }
    }

    /** Per-method bytecode size before/after probing, scored against the HotSpot inline thresholds. */
    public static void printSizeTable() {
        System.out.println();
        System.out.println("### G2 bytecode size vs HotSpot inline thresholds (MaxTrivialSize=6, MaxInlineSize=35)");
        System.out.println();
        System.out.println("| method | bytes | +blind | +read-store | crosses 6 | crosses 35 |");
        System.out.println("|---|---|---|---|---|---|");
        for (String bin : CHAIN) {
            byte[] r = raw(bin);
            String in = bin.replace('.', '/');
            ClassFileStats b0 = ClassFileStats.of(r);
            ClassFileStats b1 = ClassFileStats.of(instrument(r, in, ProbeStyle.BLIND));
            ClassFileStats b2 = ClassFileStats.of(instrument(r, in, ProbeStyle.READ_STORE));
            for (Map.Entry<String, Integer> e : b0.codeLenByMethod.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("<init>") || k.startsWith("<clinit>")) continue;
                int base = e.getValue();
                Integer blind = b1.codeLenByMethod.get(k);
                Integer read = b2.codeLenByMethod.get(k);
                if (blind == null || read == null) continue;
                String simple = bin.substring(bin.lastIndexOf('.') + 1) + "." + k;
                System.out.printf("| %s | %d | %d | %d | %s | %s |%n", simple, base, blind, read,
                    (base <= 6 && blind > 6) ? "**yes**" : "no",
                    (base <= 35 && blind > 35) ? "**yes**" : (base <= 35 && read > 35 ? "read-store only" : "no"));
            }
        }
    }
}
