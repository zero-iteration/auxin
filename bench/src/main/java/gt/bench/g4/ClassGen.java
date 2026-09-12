package ax.bench.g4;

import ax.bench.gen.ProbeStyle;
import ax.bench.gen.WorkerGenerator;

import java.nio.file.Files;
import java.nio.file.Path;

/** Writes N uninstrumented synthetic application classes to disk; the agent probes them at load. */
public final class ClassGen {

    public static final String PKG = "gt/g4/gen";
    public static final int METHODS_PER_CLASS = 9;   // + work() = 10 methods per class

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int n = Integer.parseInt(args[1]);
        Path dir = out.resolve(PKG);
        Files.createDirectories(dir);
        long bytes = 0;
        for (int i = 0; i < n; i++) {
            String internal = PKG + "/C" + i;
            byte[] cf = WorkerGenerator.generate(internal, ProbeStyle.NONE, METHODS_PER_CLASS, 1, false);
            Files.write(dir.resolve("C" + i + ".class"), cf);
            bytes += cf.length;
        }
        System.out.println("generated " + n + " classes, " + (n * (METHODS_PER_CLASS + 1))
                           + " methods, " + bytes + " bytes into " + out);
    }
}
