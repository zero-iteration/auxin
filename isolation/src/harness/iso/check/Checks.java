package iso.check;

/**
 * PASS/FAIL bookkeeping shared by every class-path driver in this suite. One line per assertion
 * on stdout; the shell harness counts them and the exit code carries the verdict.
 */
public final class Checks {

    private int n;
    private int bad;
    private final String scenario;

    public Checks(String scenario) {
        this.scenario = scenario;
        System.out.println("-- " + scenario);
    }

    public void check(boolean ok, String what) {
        n++;
        if (ok) {
            System.out.println("  PASS  " + what);
        } else {
            System.out.println("  FAIL  " + what);
            bad++;
        }
    }

    public void info(String what) { System.out.println("  info  " + what); }

    public void failed(String what, Throwable t) {
        n++;
        bad++;
        System.out.println("  FAIL  " + what + ": " + root(t));
        root(t).printStackTrace(System.out);
    }

    public static Throwable root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r;
    }

    /** Prints the summary and halts: halt() avoids shutdown hooks perturbing the agent. */
    public void report() {
        System.out.println("CHECKS " + scenario + " " + (n - bad) + "/" + n
                + (bad == 0 ? " OK" : " FAILED"));
        System.out.flush();
        Runtime.getRuntime().halt(bad == 0 ? 0 : 1);
    }

    /** Renders a probe array as a bit string, LSB=idx 0, for the log. */
    public static String bits(boolean[] p) {
        if (p == null) return "<null>";
        StringBuilder sb = new StringBuilder(p.length);
        for (int i = 0; i < p.length; i++) sb.append(p[i] ? '1' : '0');
        return sb.toString();
    }
}
