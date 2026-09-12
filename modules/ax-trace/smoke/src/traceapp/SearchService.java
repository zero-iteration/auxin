package traceapp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The subject. Every construct the tracer claims to capture appears here exactly once, so each
 * assertion has one unambiguous source.
 *
 * <table>
 *   <tr><td>a collection that shrinks</td><td>{@link #dropNonRefundable} — 126 in, 94 out, in
 *       place, so the drop is visible ONLY as an entry/exit size delta on the parameter</td></tr>
 *   <tr><td>a branch on a flag read</td><td>{@link #select} — {@code if (flags.refundableOnly())}
 *       is an {@code IFEQ} fed by an {@code INVOKEVIRTUAL}, i.e. the {@code predicates}
 *       selection rule's "call" case</td></tr>
 *   <tr><td>a loop-counter branch that must NOT be recorded</td><td>{@link #dropNonRefundable}'s
 *       {@code while} over an iterator, and {@link #sumStops}'s indexed loop</td></tr>
 *   <tr><td>a throw</td><td>{@link #priceLeg} throws {@code IllegalStateException}</td></tr>
 *   <tr><td>a thread pool</td><td>{@link #enrichAsync} — {@code ExecutorService.submit}</td></tr>
 *   <tr><td>a parallelStream</td><td>{@link #scoreAll} — the named trap</td></tr>
 *   <tr><td>a pass-through</td><td>{@link #passThrough} — observes the same thing at entry and
 *       exit, throws nothing, takes no recorded branch: it must be COLLAPSED</td></tr>
 * </table>
 */
public final class SearchService {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Flags flags;

    public SearchService(Flags flags) { this.flags = flags; }

    public void shutdown() { pool.shutdownNow(); }

    /** The request-scoped entry the filter chain reaches. */
    public int handle(String route, int legs) {
        List<Fare> fares = load(126);
        // A domain object as a parameter, on the REQUEST thread, so the per-service projection
        // can be asserted independently of the parallelStream window.
        int first = score(fares.get(0));
        int kept = select(fares) + (first & 0);
        int stops = sumStops(fares);
        long score = scoreAll(fares);
        int enriched = enrichAsync(fares);
        int priced = 0;
        try {
            priced = priceLeg(legs);
        } catch (IllegalStateException e) {
            priced = -1;
        }
        return kept + stops + (int) score + enriched + priced;
    }

    private List<Fare> load(int n) {
        List<Fare> out = new ArrayList<Fare>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Fare(Fare.Carrier.values()[i % 3], i % 3, 500000L + i,
                    // 94 of 126 refundable: i % 4 != 0 keeps 3 in every 4, and 126 - 32 = 94.
                    i % 4 != 0, "passenger" + i + "@example.test"));
        }
        return out;
    }

    /** The flag branch. {@code flags.refundableOnly()} is a CALL, so the arm is recorded. */
    private int select(List<Fare> fares) {
        List<Fare> working = passThrough(fares);
        if (flags.refundableOnly()) {
            dropNonRefundable(working);
        }
        return working.size();
    }

    /**
     * A PASS-THROUGH. Its parameter is observed at entry and again at exit, and the two are
     * identical; it records no branch arm and throws nothing. The volume control must collapse
     * it out of the document, and its child frames must survive.
     */
    private List<Fare> passThrough(List<Fare> fares) {
        return fares;
    }

    /**
     * THE SIGNAL. 126 in, 94 out, mutated IN PLACE — so the only way to see the drop is the
     * entry/exit size delta on the parameter. A return-value-only tracer sees nothing here.
     */
    private void dropNonRefundable(List<Fare> fares) {
        Iterator<Fare> it = fares.iterator();
        while (it.hasNext()) {                       // a loop branch: must NOT be recorded
            if (!it.next().isRefundable()) it.remove();
        }
    }

    /** An indexed loop. {@code i < n} is fed by two local loads: must NOT be recorded. */
    private int sumStops(List<Fare> fares) {
        int total = 0;
        for (int i = 0, n = fares.size(); i < n; i++) {
            total += fares.get(i).getStops();
        }
        return total;
    }

    /**
     * THE NAMED TRAP. {@code parallelStream()} runs on {@code ForkJoinPool.commonPool()}, where
     * no {@code Runnable} or {@code Callable} ever crosses an instrumented call site, so
     * call-site wrapping cannot see it. The lambda below and {@link #score} both run on common
     * pool workers.
     */
    private long scoreAll(List<Fare> fares) {
        return fares.parallelStream().mapToLong(f -> score(f)).sum();
    }

    /** Runs on a common-pool worker during a parallelStream. */
    int score(Fare f) {
        return f.getStops() * 10 + (f.isRefundable() ? 1 : 0);
    }

    /** A thread pool. The submitted Callable's body must be recorded under the submit frame. */
    private int enrichAsync(final List<Fare> fares) {
        Future<Integer> f = pool.submit(new Callable<Integer>() {
            @Override public Integer call() {
                return enrich(fares);
            }
        });
        try {
            return f.get().intValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (ExecutionException e) {
            return 0;
        }
    }

    /** Runs on a pool thread. Its frame must carry {@code via: "executor"}. */
    int enrich(List<Fare> fares) {
        return fares.isEmpty() ? 0 : fares.size();
    }

    /** THE THROW. Only the class name may reach the document, never the message. */
    private int priceLeg(int leg) {
        if (leg < 0) {
            throw new IllegalStateException("leg " + leg + " for passenger jane@example.test");
        }
        return leg * 100;
    }

    /** A flag source, so the branch's operand comes from a call rather than a constant. */
    public static final class Flags {
        private final boolean refundableOnly;

        public Flags(boolean refundableOnly) { this.refundableOnly = refundableOnly; }

        public boolean refundableOnly() { return refundableOnly; }
    }
}
