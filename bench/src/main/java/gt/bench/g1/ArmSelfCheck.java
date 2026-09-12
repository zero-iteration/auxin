package ax.bench.g1;

import ax.bench.gen.ProbeStyle;
import ax.bench.rt.GtBenchRuntime;

/**
 * Guard against the obvious way a probe benchmark lies to you: the probe being optimised away.
 * Builds each arm, runs it, and prints the probe array that resulted. Every arm must show all
 * 9 probes set, otherwise its ns/op number is measuring nothing.
 */
public final class ArmSelfCheck {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("### G1 arm self-check (probes must actually be set, else the numbers are meaningless)");
        System.out.println();
        int bad = 0;
        for (ProbeStyle style : ProbeStyle.values()) {
            GtBenchRuntime.reset();
            Worker w = Arms.newWorker(style);
            int x = 1;
            for (int i = 0; i < 100_000; i++) x = w.work(x);
            String owner = Arms.binaryName(style).replace('.', '/');
            String render = GtBenchRuntime.render(owner);
            boolean expectSet = style != ProbeStyle.NONE;
            boolean ok = expectSet
                ? render.length() == Arms.PROBES_PER_OP && render.indexOf('0') < 0
                : "NONE".equals(render);
            if (!ok) bad++;
            System.out.printf("| %-20s | probes=%-12s | %s | result x=%d |%n",
                style, render, ok ? "OK" : "**UNEXPECTED**", x);
        }
        System.out.println();
        System.out.println(bad == 0 ? "all arms record as designed; no probe was optimised away"
                                    : "SELF-CHECK FAILED for " + bad + " arm(s)");
        if (bad != 0) System.exit(1);
    }
}
