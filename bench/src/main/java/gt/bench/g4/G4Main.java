package ax.bench.g4;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;

/**
 * GATE G4 - startup transform cost.
 *
 * Four child-JVM configurations over the same ~5,000 generated classes:
 *   OFF       no agent at all
 *   NAMEONLY  agent registered, name-prefix check only, never parses, never rewrites
 *   FULL      name-prefix first pass, then parse + condy probe the matching classes
 *   PARSE     parse EVERY class handed to the transformer to decide, then probe the matching ones
 *
 * FULL - NAMEONLY  = what instrumenting our own classes costs.
 * PARSE - FULL     = what it costs to parse classes you were always going to reject, i.e. the
 *                    price of not doing name-only matching in the first pass (PLAN-v2 non-negotiable).
 *
 * NOT MEASURED HERE: the Kubernetes CPU-limit version of this gate (time-to-healthy on Spring
 * PetClinic at 0.5 / 1 / 2 CPU limits) needs Docker, which is not installed on this machine.
 */
public final class G4Main {

    public static void main(String[] args) throws Exception {
        Path agentJar = Path.of(args[0]);
        Path classesDir = Path.of(args[1]);
        int n = Integer.parseInt(args[2]);
        int reps = args.length > 3 ? Integer.parseInt(args[3]) : 3;

        String javaBin = System.getProperty("java.home") + "/bin/java";
        String cp = agentJar + java.io.File.pathSeparator + classesDir;

        System.out.println();
        System.out.println("### G4 startup transform cost: " + n + " synthetic classes x "
                           + (ClassGen.METHODS_PER_CLASS + 1) + " methods = "
                           + (n * (ClassGen.METHODS_PER_CLASS + 1)) + " methods; the MEDIAN-whole-JVM-CPU rep of " + reps + " reps is reported");
        System.out.println();
        System.out.println("| config | class-load wall ms | class-load CPU ms | whole-JVM wall ms | whole-JVM CPU ms | classes seen | transformed | bytes in -> out |");
        System.out.println("|---|---|---|---|---|---|---|---|");

        Map<String, Map<String, Double>> best = new LinkedHashMap<>();
        for (String mode : List.of("OFF", "NAMEONLY", "FULL", "PARSE")) {
            List<Map<String, Double>> runs = new ArrayList<>();
            for (int r = 0; r < reps; r++) {
                List<String> cmd = new ArrayList<>();
                cmd.add(javaBin);
                cmd.add("-Xmx2g");
                cmd.add("-XX:MaxMetaspaceSize=1g");
                cmd.add("-XX:+UseSerialGC");
                if (!mode.equals("OFF")) cmd.add("-javaagent:" + agentJar + "=g4:" + mode.toLowerCase());
                cmd.addAll(List.of("-cp", cp, G4Child.class.getName(), String.valueOf(n)));
                Map<String, Double> m = new LinkedHashMap<>();
                run(cmd, m);
                runs.add(m);
            }
            // median by whole-JVM CPU: more defensible than a minimum, and CPU is the metric that
            // decides whether a CFS-limited pod gets throttled at startup (A11).
            runs.sort(Comparator.comparingDouble(m -> m.get("jvmCpuMs")));
            Map<String, Double> b = runs.get(runs.size() / 2);
            best.put(mode, b);
            System.out.printf("| %s | %.1f | %.1f | %.0f | %.1f | %.0f | %.0f | %s |%n",
                mode, b.get("loadWallMs"), b.get("loadCpuMs"), b.get("jvmUptimeMs"), b.get("jvmCpuMs"),
                b.get("classesSeen"), b.get("classesTransformed"),
                b.get("bytesIn") == 0 ? "-" :
                    String.format("%.0f -> %.0f (+%.1f%%)", b.get("bytesIn"), b.get("bytesOut"),
                        100.0 * (b.get("bytesOut") - b.get("bytesIn")) / b.get("bytesIn")));
        }

        System.out.println();
        double off = best.get("OFF").get("loadWallMs");
        double offCpu = best.get("OFF").get("jvmCpuMs");
        for (String mode : List.of("NAMEONLY", "FULL", "PARSE")) {
            double w = best.get(mode).get("loadWallMs");
            double c = best.get(mode).get("jvmCpuMs");
            System.out.printf("delta vs OFF: %-8s class-load wall %+.1f ms (%+.1f%%), whole-JVM CPU %+.1f ms (%+.1f%%)%n",
                mode, w - off, 100.0 * (w - off) / off, c - offCpu, 100.0 * (c - offCpu) / offCpu);
        }
        System.out.printf("FULL - NAMEONLY (cost of parsing+probing our own classes): %+.1f ms wall%n",
            best.get("FULL").get("loadWallMs") - best.get("NAMEONLY").get("loadWallMs"));
        System.out.printf("PARSE - FULL (cost of NOT doing name-only matching first): %+.1f ms wall, %+.1f ms whole-JVM CPU%n",
            best.get("PARSE").get("loadWallMs") - best.get("FULL").get("loadWallMs"),
            best.get("PARSE").get("jvmCpuMs") - best.get("FULL").get("jvmCpuMs"));
        System.out.println();
        System.out.println("NOT MEASURED: time-to-healthy on Spring PetClinic at 0.5 / 1 / 2 CPU limits. "
                           + "Requires Docker; Docker is not installed (see docs/TOOLCHAIN.md).");
    }

    static String run(List<String> cmd, Map<String, Double> into) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String found = "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("GT_G4")) continue;
                found = line;
                for (String kv : line.split("\\s+")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) into.put(kv.substring(0, eq), Double.parseDouble(kv.substring(eq + 1)));
                }
            }
        }
        p.waitFor();
        if (into.isEmpty()) throw new IllegalStateException("child produced no GT_G4 line: " + cmd);
        return found;
    }
}
