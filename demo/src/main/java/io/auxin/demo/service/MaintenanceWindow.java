package io.auxin.demo.service;

/**
 * GROUND TRUTH — LOADED BUT NEVER INVOKED.
 *
 * <p>{@code App} loads this class with {@code Class.forName}, which runs its
 * {@code <clinit>} and nothing else. No constructor, no instance method and no static
 * method here is ever called.
 *
 * <p>Expected verdicts:
 * <ul>
 *   <li>the class appears in {@code classesLoaded};</li>
 *   <li>every declared method has an unset probe;</li>
 *   <li>{@code <clinit>} gets no probe at all (never probed, never nominated);</li>
 *   <li>the methods are legitimate DEAD_CANDIDATEs — unlike a never-loaded class, where
 *       the honest answer is UNKNOWN.</li>
 * </ul>
 * If a pipeline reports these two cases identically, it is wrong.
 */
public final class MaintenanceWindow {

    private static final long OPENED_AT;

    static {
        // This DOES execute (Class.forName initialises the class).
        OPENED_AT = System.currentTimeMillis();
    }

    public MaintenanceWindow() {
        // Never invoked: nothing constructs this class.
    }

    public boolean isOpen() {
        return System.currentTimeMillis() - OPENED_AT < 3_600_000L;
    }

    public void start() {
        // never invoked
    }

    public void stop() {
        // never invoked
    }
}
