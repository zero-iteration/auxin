package iso.child;

/**
 * The class loaded twice on purpose: once by the application class loader and once by a
 * child-first (parent-last) loader, the shape Tomcat's WebappClassLoader and Spring Boot's
 * LaunchedClassLoader use. Two runtime classes, one binary name.
 */
public class Widget {

    private int state;

    public Widget() {
        this.state = 1;
        bump();
    }

    private void bump() {
        this.state = this.state + 1;
    }

    public int alpha(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) acc += i;
        return acc + state;
    }

    public String beta(String s) {
        if (s == null || s.isEmpty()) return "empty";
        return s.toUpperCase() + ":" + s.length();
    }

    /** Only ever called through the child-first copy. */
    public int childOnly(int n) {
        int acc = 3;
        for (int i = 0; i < n; i++) acc += 2;
        return acc;
    }

    /** Never called by anything. */
    public int never(int n) {
        int acc = 1;
        for (int i = 1; i <= n; i++) acc *= i;
        return acc;
    }
}
