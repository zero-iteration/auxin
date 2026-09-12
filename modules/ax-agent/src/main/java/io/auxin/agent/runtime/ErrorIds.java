package io.auxin.agent.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Throwable class -&gt; small dense id, for the 8-bit error field of a packed event.
 *
 * <p>Id 0 means "no error"; ids 1..254 are assigned on first sight; 255 is the overflow bucket.
 * {@code ClassValue} keeps the steady-state lookup allocation-free (the {@code Integer} is
 * allocated once per exception class, on the first throw of that type, and cached on the Class
 * itself). The error path is by definition not the steady-state hot path.
 */
public final class ErrorIds {

    public static final int NONE = 0;
    public static final int OVERFLOW = 255;

    private static final List<String> NAMES = new ArrayList<String>();

    static {
        synchronized (NAMES) {
            NAMES.add("");            // id 0 = no error
        }
    }

    private static final ClassValue<Integer> IDS = new ClassValue<Integer>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            synchronized (NAMES) {
                if (NAMES.size() >= OVERFLOW) return Integer.valueOf(OVERFLOW);
                NAMES.add(type.getName());
                return Integer.valueOf(NAMES.size() - 1);
            }
        }
    };

    public static int idFor(Class<?> throwableClass) {
        try {
            return IDS.get(throwableClass).intValue();
        } catch (Throwable t) {
            return OVERFLOW;
        }
    }

    /** Drain thread. @return "" for id 0, "other" for the overflow bucket. */
    public static String name(int id) {
        synchronized (NAMES) {
            if (id == OVERFLOW) return "other";
            if (id < 0 || id >= NAMES.size()) return "unknown";
            return NAMES.get(id);
        }
    }

    private ErrorIds() { throw new AssertionError(); }
}
