package iso.osgi;

import iso.api.Work;

/** Loaded by a Felix bundle class loader, which delegates nothing but java.* to its parent. */
public class Service implements Work, Helper {

    private int state;

    public Service() {
        this.state = 1;
        bump();
    }

    private void bump() {
        this.state = this.state + 1;
    }

    @Override
    public int alpha(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) acc += i;
        return acc + state;
    }

    @Override
    public String beta(String s) {
        if (s == null || s.isEmpty()) return "empty";
        return s.toUpperCase() + ":" + s.length();
    }

    /** Drives the interface's default method and its static method. */
    public int viaInterface(int n) {
        return twice(n) + Helper.thrice(n);
    }

    @Override
    public String workerOutcome() {
        return Activator.workerOutcome;
    }

    @Override
    public String where() {
        ClassLoader cl = Service.class.getClassLoader();
        return String.valueOf(cl);
    }

    /** Never called. Its probe must stay false. */
    public int never(int n) {
        int acc = 1;
        for (int i = 1; i <= n; i++) acc *= i;
        return acc;
    }
}
