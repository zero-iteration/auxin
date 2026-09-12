package ax.bench.g2;

import ax.bench.gen.ProbeStyle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GATE G2 - the inlining diff.
 *
 * Runs an identical workload in child JVMs, once with the chain classes untouched and once with
 * Tier-1 probes installed, both under -XX:+PrintInlining, and reports which methods STOPPED
 * being inlined.
 *
 * TWO SCENARIOS, because the first one alone would have been misleading:
 *
 *  S1 "hot"      stock C2 flags. Every call site in a benchmark loop is judged frequent, so C2
 *                raises the inline budget from MaxInlineSize=35 to FreqInlineSize=325 and the
 *                probe is absorbed. This is the truth about HOT code.
 *  S2 "lukewarm" -XX:FreqInlineSize=35 collapses the frequency exemption onto MaxInlineSize, which
 *                is the budget C2 actually applies to call sites that are warm enough to compile
 *                but not frequent enough to earn the exemption - i.e. most of a real application.
 *                This is where VALIDATION A2's mechanism becomes visible.
 *
 * -XX:-TieredCompilation isolates C2's decision. The throughput numbers in G2Bench are taken with
 * the default tiered compiler, which is what production runs.
 */
public final class InliningDiff {

    /** PrintInlining callee lines: "@ 12   pkg.Cls::m (30 bytes)   <verdict>" */
    private static final Pattern LINE =
        Pattern.compile("@\\s+(\\d+)\\s+([\\w.$]+)::([\\w$<>]+)\\s+\\((\\d+)\\s+bytes\\)\\s*(.*)");

    static final Set<String> OK_PREFIX = Set.of("inline", "accessor", "force", "intrinsic");

    /** Plain class rather than a record: the bench jar targets Java 11 so G3 can run on JDK 11,
     *  which is the version that actually decides the JDK-8216970 CHECKCAST question. */
    static final class Scenario {
        private final String id;
        private final String label;
        private final List<String> extraFlags;
        Scenario(String id, String label, List<String> extraFlags) {
            this.id = id; this.label = label; this.extraFlags = extraFlags;
        }
        String id() { return id; }
        String label() { return label; }
        List<String> extraFlags() { return extraFlags; }
    }

    public static void main(String[] args) throws Exception {
        int iters = args.length > 0 ? Integer.parseInt(args[0]) : 400_000;

        ChainInstrumenter.printSizeTable();

        List<Scenario> scenarios = List.of(
            new Scenario("S1", "hot call sites, stock C2 flags (FreqInlineSize=325 applies)", List.of()),
            new Scenario("S2", "lukewarm call sites, -XX:FreqInlineSize=35 (MaxInlineSize governs)",
                         List.of("-XX:FreqInlineSize=35")));

        for (Scenario sc : scenarios) {
            Map<String, Set<String>> base = new TreeMap<>();
            Map<String, Set<String>> instr = new TreeMap<>();
            Map<String, Integer> sizeBase = new TreeMap<>();
            Map<String, Integer> sizeInstr = new TreeMap<>();
            String baseResult = run(ProbeStyle.NONE, iters, sc.extraFlags(), base, sizeBase);
            String instrResult = run(ProbeStyle.BLIND, iters, sc.extraFlags(), instr, sizeInstr);

            System.out.println();
            System.out.println("### G2 " + sc.id() + " - " + sc.label());
            System.out.println();
            System.out.println("`-XX:-TieredCompilation -XX:-BackgroundCompilation "
                               + String.join(" ", sc.extraFlags()) + "`, " + iters + " iterations");
            System.out.println();
            System.out.println("    baseline     : " + baseResult);
            System.out.println("    instrumented : " + instrResult);
            double b = nsPerOp(baseResult), i = nsPerOp(instrResult);
            if (b > 0 && i > 0) {
                System.out.printf("    throughput delta: %.1f -> %.1f ns/op = %+.1f%%%n", b, i, 100.0 * (i - b) / b);
            }

            Set<String> all = new TreeSet<>();
            all.addAll(base.keySet());
            all.addAll(instr.keySet());

            List<String> stopped = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            for (String m : all) {
                Set<String> vb = base.getOrDefault(m, Set.of());
                Set<String> vi = instr.getOrDefault(m, Set.of());
                boolean bOk = vb.stream().anyMatch(InliningDiff::isOk);
                boolean iOk = vi.stream().anyMatch(InliningDiff::isOk);
                String row = String.format("| `%s` | %s -> %s | %s | %s |", m,
                    sizeBase.getOrDefault(m, -1), sizeInstr.getOrDefault(m, -1), vb, vi);
                if (bOk && !iOk) stopped.add(row);
                else if (!vb.equals(vi)) changed.add(row);
            }

            System.out.println();
            System.out.println("**methods that STOPPED being inlined: " + stopped.size() + "**");
            if (!stopped.isEmpty()) {
                System.out.println();
                System.out.println("| method | bytes clean -> probed | baseline verdict | instrumented verdict |");
                System.out.println("|---|---|---|---|");
                stopped.forEach(System.out::println);
            }
            if (!changed.isEmpty()) {
                System.out.println();
                System.out.println("other verdict changes (still inlined):");
                System.out.println();
                System.out.println("| method | bytes clean -> probed | baseline verdict | instrumented verdict |");
                System.out.println("|---|---|---|---|");
                changed.forEach(System.out::println);
            }
            System.out.printf("%ninline-success call sites: baseline=%d instrumented=%d%n",
                countOk(base), countOk(instr));
        }
    }

    static double nsPerOp(String resultLine) {
        for (String kv : resultLine.split("\\s+")) {
            if (kv.startsWith("nsPerOp=")) return Double.parseDouble(kv.substring(8));
        }
        return -1;
    }

    static boolean isOk(String verdict) {
        String v = verdict.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return true;
        if (v.startsWith("failed to inline")) return false;
        for (String p : OK_PREFIX) if (v.startsWith(p)) return true;
        return false;
    }

    static int countOk(Map<String, Set<String>> m) {
        int n = 0;
        for (Set<String> v : m.values()) if (v.stream().anyMatch(InliningDiff::isOk)) n++;
        return n;
    }

    static String run(ProbeStyle style, int iters, List<String> extraFlags,
                      Map<String, Set<String>> sink, Map<String, Integer> sizes) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            System.getProperty("java.home") + "/bin/java",
            "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintInlining",
            "-XX:-TieredCompilation", "-XX:-BackgroundCompilation"));
        cmd.addAll(extraFlags);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"),
                           InliningRunner.class.getName(), style.name(), String.valueOf(iters)));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String result = "(no GT_RESULT line)";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("GT_RESULT")) { result = line; continue; }
                Matcher m = LINE.matcher(line);
                if (!m.find()) continue;
                String cls = m.group(2);
                if (!cls.startsWith("ax.bench.g2.chain.")) continue;
                String key = cls.substring(cls.lastIndexOf('.') + 1) + "::" + m.group(3);
                sizes.put(key, Integer.parseInt(m.group(4)));
                sink.computeIfAbsent(key, k -> new TreeSet<>()).add(m.group(5).trim());
            }
        }
        p.waitFor();
        return result;
    }
}
