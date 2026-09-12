package iso.osgi;

import iso.api.Work;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Publishes the service. Instantiating Service here is what forces the bundle to define it.
 *
 * <p>It also drives {@link Worker} from a thread the bundle started, so that the first execution
 * of an instrumented bundle class happens on a stack with no application frames at all, and
 * records whatever that produced for the driver to assert.
 */
public class Activator implements BundleActivator {

    /** "OK" or the Throwable that the bundle's own thread saw. Read through the service. */
    static volatile String workerOutcome = "not run";

    @Override
    public void start(BundleContext context) throws Exception {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Worker w = new Worker();
                    int a = w.alpha(10);
                    workerOutcome = a == 47 ? "OK" : ("WRONG:" + a);
                } catch (Throwable e) {
                    Throwable r = e;
                    while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                    workerOutcome = r.getClass().getName() + ": " + r.getMessage();
                }
            }
        }, "bundle-own-thread");
        t.start();
        t.join(10000);
        context.registerService(Work.class.getName(), new Service(), null);
    }

    @Override
    public void stop(BundleContext context) {
    }
}
