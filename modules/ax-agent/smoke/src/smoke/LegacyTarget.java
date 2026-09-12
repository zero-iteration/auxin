package smoke;

/**
 * Compiled with --release 8 (class file major 52 &lt; 55), so condy is unavailable and the
 * agent must fall back to a synthetic static field plus a &lt;clinit&gt; prologue. This class has
 * no &lt;clinit&gt; of its own, so the agent has to create one.
 */
public class LegacyTarget {

    public int compute(int x) {
        int r = 0;
        for (int i = 0; i < x; i++) r += i * 2;
        return r;
    }

    /** Constant return: C51 marks it not dynamically observable, so it gets no probe. */
    public String name() {
        return "legacy";
    }
}
