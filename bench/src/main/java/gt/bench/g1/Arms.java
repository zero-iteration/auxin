package ax.bench.g1;

import ax.bench.gen.ProbeStyle;
import ax.bench.gen.SimpleLoader;
import ax.bench.gen.WorkerGenerator;

import java.util.Collections;
import java.util.Map;

public final class Arms {

    /** work() plus 8 small leaf methods; 1 probe per method = Tier-1 method granularity = 9 probes/op. */
    public static final int LEAF_METHODS = 8;
    public static final int PROBES_PER_OP = LEAF_METHODS + 1;

    private Arms() {}

    public static String binaryName(ProbeStyle style) {
        return "ax.bench.g1.gen.W_" + style.name();
    }

    public static byte[] classFile(ProbeStyle style, int leafMethods, int probesPerMethod) {
        return WorkerGenerator.generate(binaryName(style).replace('.', '/'),
                                        style, leafMethods, probesPerMethod, true);
    }

    /**
     * Loads ONLY the arm under test. Each JMH benchmark runs in its own fork, so the
     * Worker.work() call site stays monomorphic and no arm pollutes another arm's inlining profile.
     */
    public static Worker newWorker(ProbeStyle style) {
        try {
            String bin = binaryName(style);
            Map<String, byte[]> defs =
                Collections.singletonMap(bin, classFile(style, LEAF_METHODS, 1));
            ClassLoader cl = new SimpleLoader(Worker.class.getClassLoader(), defs, false);
            return (Worker) cl.loadClass(bin).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("cannot load arm " + style, e);
        }
    }
}
