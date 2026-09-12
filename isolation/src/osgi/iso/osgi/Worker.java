package iso.osgi;

/**
 * Exercised ONLY from a thread the bundle itself started, so the call stack at first execution
 * contains nothing but java.lang.Thread and bundle classes.
 *
 * <p>Felix's implicit boot delegation (felix.bootdelegation.implicit, default TRUE) decides
 * whether to delegate a failed bundle class load by inspecting that stack. Which means the same
 * bundle class loader can answer "yes, I can see the agent" when the agent asks from its own
 * stack and "no" when the instrumented class asks from a bundle thread. This class is how the
 * suite catches that.
 */
public class Worker {

    private int state;

    public Worker() {
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
}
