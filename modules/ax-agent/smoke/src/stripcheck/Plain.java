package stripcheck;

/**
 * A class inside {@code ax.include.packages} and OUTSIDE {@code ax.trace.include.packages}.
 *
 * <p>It exists to assert the half of the SCOPE-v3.1 trade that is easy to lose: when the trace
 * tier disables Tier-1b's auto-strip, it must disable it <b>only for the intersection of the two
 * scopes</b>. A class nobody asked to trace must still strip to zero — otherwise "scoped" is a
 * word in a document rather than a property of the code.
 *
 * <p>Every method here is deliberately more than one instruction, so none of them is C51-exempt
 * and each really does carry a probe Tier-1b can remove.
 */
public final class Plain {

    private int calls;

    public int work(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) acc += i * 3;
        calls++;
        return acc;
    }

    public int describe(int n) {
        if (n < 0) return -1;
        return n + calls;
    }
}
