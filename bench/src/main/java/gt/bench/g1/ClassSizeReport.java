package ax.bench.g1;

import ax.bench.gen.ClassFileStats;
import ax.bench.gen.ProbeStyle;
import ax.bench.gen.SimpleLoader;
import ax.bench.gen.WorkerGenerator;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GATE G1, second half - the size / load-time-verification half.
 *
 * The latency shootout only scores steady state. The other half of the probe-pattern decision is
 * what the extra bytes cost the JVM at class load: every StackMapTable entry is work for the
 * verifier, and per PLAN-v2 that verifier CPU at startup (A11) is the actual incident vector.
 *
 * Usage:  ClassSizeReport            -> size table + spawns one child JVM per arm for load timing
 *         ClassSizeReport verify X   -> child mode, times define+link of N classes for arm X
 */
public final class ClassSizeReport {

    static final int SIZE_METHODS = 100;       // leaf methods per class for the size table
    static final int LOAD_CLASSES = 400;       // classes defined+linked per arm in the load test
    static final int LOAD_METHODS = 50;        // leaf methods per class in the load test
    static final int LOAD_REPS    = 3;

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("verify")) {
            verifyChild(ProbeStyle.valueOf(args[1]));
            return;
        }
        sizeTable(1);
        sizeTable(4);
        perProbeTable();
        loadTable();
    }

    // ---------------------------------------------------------------- size

    static void sizeTable(int probesPerMethod) {
        System.out.println();
        System.out.println("### class-file size, " + SIZE_METHODS + " leaf methods, "
                           + probesPerMethod + " probe(s) per method");
        System.out.println();
        System.out.println("| arm | class bytes | vs none | Code bytes | StackMapTable bytes | frames | CP entries |");
        System.out.println("|---|---|---|---|---|---|---|");
        int base = -1;
        for (ProbeStyle st : ProbeStyle.values()) {
            byte[] cf = Arms.classFile(st, SIZE_METHODS, probesPerMethod);
            ClassFileStats s = ClassFileStats.of(cf);
            if (base < 0) base = s.fileBytes;
            String delta = st == ProbeStyle.NONE ? "-"
                : String.format("+%d (+%.1f%%)", s.fileBytes - base, 100.0 * (s.fileBytes - base) / base);
            System.out.printf("| %s | %d | %s | %d | %d | %d | %d |%n",
                st, s.fileBytes, delta, s.codeBytes, s.stackMapTableBytes, s.stackMapFrames,
                s.constantPoolEntries);
        }
    }

    static void perProbeTable() {
        System.out.println();
        System.out.println("### per-probe bytecode cost (measured from emitted Code attributes)");
        System.out.println();
        System.out.println("| arm | leaf method Code bytes | added vs none | work() Code bytes |");
        System.out.println("|---|---|---|---|");
        int baseLeaf = -1, baseWork = -1;
        for (ProbeStyle st : ProbeStyle.values()) {
            ClassFileStats s = ClassFileStats.of(Arms.classFile(st, SIZE_METHODS, 1));
            int leaf = s.codeLenByMethod.get("m0(I)I");
            int work = s.codeLenByMethod.get("work(I)I");
            if (baseLeaf < 0) { baseLeaf = leaf; baseWork = work; }
            System.out.printf("| %s | %d | +%d | %d (+%d) |%n", st, leaf, leaf - baseLeaf, work, work - baseWork);
        }
        System.out.println();
        System.out.println("HotSpot thresholds for reference: MaxTrivialSize=6, MaxInlineSize=35, FreqInlineSize=325.");
    }

    // ---------------------------------------------------------------- load / verify

    static void loadTable() throws Exception {
        System.out.println();
        System.out.println("### load-time verification cost: define+link " + LOAD_CLASSES
                           + " classes x " + (LOAD_METHODS + 1) + " methods (= "
                           + (LOAD_CLASSES * (LOAD_METHODS + 1)) + " probed methods), fresh child JVM per arm");
        System.out.println();
        System.out.println("| arm | best wall ms | best process-CPU ms | total class bytes |");
        System.out.println("|---|---|---|---|");
        String java = System.getProperty("java.home") + "/bin/java";
        String cp = System.getProperty("java.class.path");
        for (ProbeStyle st : ProbeStyle.values()) {
            List<String> cmd = new ArrayList<>(List.of(java, "-Xmx2g", "-XX:MaxMetaspaceSize=1g",
                "-XX:+UseSerialGC", "-cp", cp, ClassSizeReport.class.getName(), "verify", st.name()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            System.out.println(out);
        }
    }

    static void verifyChild(ProbeStyle style) throws Exception {
        com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        long bestWall = Long.MAX_VALUE, bestCpu = Long.MAX_VALUE;
        long totalBytes = 0;
        for (int rep = 0; rep < LOAD_REPS; rep++) {
            Map<String, byte[]> defs = new HashMap<>();
            long bytes = 0;
            for (int i = 0; i < LOAD_CLASSES; i++) {
                String bin = "ax.bench.g1.load.r" + rep + ".C" + i;
                byte[] cf = WorkerGenerator.generate(bin.replace('.', '/'), style, LOAD_METHODS, 1, false);
                defs.put(bin, cf);
                bytes += cf.length;
            }
            totalBytes = bytes;
            SimpleLoader cl = new SimpleLoader(ClassSizeReport.class.getClassLoader(), defs, false);
            System.gc();
            long cpu0 = os.getProcessCpuTime();
            long t0 = System.nanoTime();
            for (String bin : defs.keySet()) {
                // initialize=true forces linking, i.e. bytecode verification incl. StackMapTable checks
                Class.forName(bin, true, cl);
            }
            long wall = System.nanoTime() - t0;
            long cpu = os.getProcessCpuTime() - cpu0;
            bestWall = Math.min(bestWall, wall);
            bestCpu = Math.min(bestCpu, cpu);
        }
        System.out.printf("| %s | %.1f | %.1f | %d |%n",
            style, bestWall / 1e6, bestCpu / 1e6, totalBytes);
    }
}
