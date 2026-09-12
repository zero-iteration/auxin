package iso.api;

/**
 * The OSGi service contract. It is exported from the system bundle
 * (org.osgi.framework.system.packages.extra=iso.api) and imported by the test bundle, so the
 * framework-side driver and the bundle share exactly one Work interface.
 *
 * <p>This package is NOT the code under test: the implementation, iso.osgi.Service, is defined
 * by the bundle's own class loader, and that is the class the agent has to instrument.
 */
public interface Work {
    int alpha(int n);
    String beta(String s);
    /** Calls an interface default method and an interface static method. */
    int viaInterface(int n);

    /** What happened when a thread the BUNDLE started first touched an instrumented class. */
    String workerOutcome();

    /** Describes the loader that actually defined the implementation class. */
    String where();
}
