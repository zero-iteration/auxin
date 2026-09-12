package smoke;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberately awkward to instrument: loops, branches, try/catch/finally, a switch, a
 * synchronized method, a lambda (synthetic -> must be skipped), long and double locals.
 * If ClassWriter(0) plus hand-written frames were wrong, this class would fail verification.
 */
public class SmokeTarget {

    private long counter;
    private final List<String> log = new ArrayList<String>();

    public SmokeTarget() {
        this(0);
    }

    /**
     * Carries a branch, so it has stack map frames of its own — and in a class that also holds a
     * tier-2 method every frame is read EXPANDED, which forces the probe's entry frame to be
     * expanded too. At that point local 0 is still {@code uninitializedThis}: naming the class
     * there instead is the classic constructor VerifyError.
     */
    public SmokeTarget(int seed) {
        counter = seed > 0 ? seed : -seed;
    }

    public int alpha(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            if ((i & 1) == 0) sum += i;
            else sum -= i;
        }
        return sum;
    }

    public String beta(String s) {
        StringBuilder sb = new StringBuilder();
        try {
            switch (s.length() % 3) {
                case 0: sb.append("zero"); break;
                case 1: sb.append("one"); break;
                default: sb.append("many"); break;
            }
            if (s.isEmpty()) throw new IllegalArgumentException("empty");
        } catch (IllegalArgumentException e) {
            sb.append("!caught");
        } finally {
            sb.append(":").append(s.length());
        }
        return sb.toString();
    }

    public synchronized double gamma(double x) {
        double y = x * 2.0d;
        List<Integer> xs = new ArrayList<Integer>();
        for (int i = 0; i < 3; i++) xs.add(Integer.valueOf(i));
        int total = xs.stream().mapToInt(i -> i.intValue() + 1).sum();
        return y + total;
    }

    /** tier-2 boundary method: timed, counted, and its errors classified. */
    public int boundary(int n) {
        counter += n;
        if (n < 0) throw new IllegalStateException("negative");
        long t = 0;
        for (int i = 0; i < n * 10; i++) t += i;
        log.add("b" + n);
        return (int) (t & 0xFF);
    }

    /**
     * The first instruction of this method is already a branch target with a stack map frame
     * (javac compiles {@code for (;;)} to a backward jump to offset 0). The read-then-store
     * probe must reuse that frame as its merge point: emitting a second frame at the same
     * bytecode offset is an illegal StackMapTable.
     */
    public int spin(int n) {
        for (;;) {
            n -= 3;
            if (n <= 0) return n;
        }
    }

    /** C51: constant-returning accessor. Not dynamically observable -> must never be probed. */
    public int answer() {
        return 42;
    }

    public long counter() {
        return counter;
    }

    public int logSize() {
        return log.size();
    }
}
