package ax.bench.g2;

import ax.bench.gen.ProbeStyle;

/** Child JVM entry point for the PrintInlining diff. Deterministic, fixed iteration count. */
public final class InliningRunner {

    public static void main(String[] args) {
        ProbeStyle style = ProbeStyle.valueOf(args[0]);
        int iters = args.length > 1 ? Integer.parseInt(args[1]) : 400_000;
        ChainWork e = ChainInstrumenter.newEngine(style);
        int x = 0x2545F491;
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) x = e.work(x) + i;
        long ns = System.nanoTime() - t0;
        System.out.println("GT_RESULT style=" + style + " iters=" + iters
                           + " ns=" + ns + " nsPerOp=" + (ns / (double) iters)
                           + " checksum=" + e.checksum() + " x=" + x);
    }
}
