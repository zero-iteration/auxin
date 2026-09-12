package iso.app;

/** Ordinary application code living inside a named module. Every method has a real body. */
public class Target {

    private int state;

    public Target() {
        this.state = 1;
        touch();
    }

    private void touch() {
        this.state = this.state + 1;
    }

    /** called by the test */
    public int alpha(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) acc += i;
        return acc + state;
    }

    /** called by the test */
    public String beta(String s) {
        if (s == null || s.isEmpty()) return "empty";
        return s.toUpperCase() + ":" + s.length();
    }

    /** NEVER called: its probe must stay false. */
    public int never(int n) {
        int acc = 1;
        for (int i = 1; i <= n; i++) acc *= i;
        return acc;
    }
}
