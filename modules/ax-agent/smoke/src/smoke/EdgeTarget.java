package smoke;

/**
 * Subject for the runtime call-edge tier (SCOPE-v3).
 *
 * <p>Deliberately shaped so every property of the tier is observable from the wire:
 * <ul>
 *   <li>{@link #root} is a tier-2 boundary method, so it is a <b>sampling root</b>, and it opens
 *       a four-deep chain plus a direct leaf call — five distinct edges from one invocation.</li>
 *   <li>{@link #outerRoot} is a root that calls a root: the inner one must be recorded as an
 *       ordinary edge and its exit must NOT end the outer trace.</li>
 *   <li>{@link #deepRoot} recurses far past {@code ax.edges.max.depth}: what is recorded must be
 *       bounded by the cap and the truncation must be counted.</li>
 *   <li>{@link #fanRoot} makes far more calls than {@code ax.edges.max.per.root} at depth one:
 *       the per-root cap must bite without the depth cap being involved.</li>
 *   <li>{@link #throwingRoot} lets an exception escape a root. The trace must still close — a
 *       leaked trace would make every instrumented call in the JVM pay a thread-local read.</li>
 *   <li>{@link #strippedRoot} is called after Tier-1b has removed this class's probes, which is
 *       the assertion that the two tiers are genuinely independent.</li>
 * </ul>
 *
 * <p>Nothing here is a single-instruction body or a constant return: C51 would mark such a
 * method not-dynamically-observable, and then it would get neither a probe nor an edge.
 */
public class EdgeTarget {

    private long calls;

    /** tier-2 boundary method =&gt; sampling root. root(3) == 18. */
    public int root(int n) {
        calls++;
        return level1(n) + leaf(n);
    }

    /** A root that calls another root. outerRoot(3) == 19. */
    public int outerRoot(int n) {
        calls++;
        return root(n) + 1;
    }

    public int level1(int n) {
        return level2(n) + 1;
    }

    public int level2(int n) {
        return level3(n) + 2;
    }

    public int level3(int n) {
        return leaf(n) + 3;
    }

    public int leaf(int n) {
        int x = n * 2;
        return x;
    }

    /** Root over unbounded recursion. deepRoot(n) == n. */
    public int deepRoot(int n) {
        calls++;
        return recurse(n);
    }

    public int recurse(int n) {
        if (n <= 0) return 0;
        return recurse(n - 1) + 1;
    }

    /** Root with a wide fan-out at depth one. fanRoot(n) == n*(n-1). */
    public int fanRoot(int n) {
        calls++;
        int sum = 0;
        for (int i = 0; i < n; i++) sum += leaf(i);
        return sum;
    }

    /** Root whose callee throws, and which does not catch it. */
    public int throwingRoot(int n) {
        calls++;
        return thrower(n);
    }

    public int thrower(int n) {
        throw new IllegalStateException("edge-" + n);
    }

    /** Root called after this class has been de-instrumented. strippedRoot(3) == 12. */
    public int strippedRoot(int n) {
        calls++;
        return level1(n);
    }

    public long calls() {
        return calls;
    }
}
