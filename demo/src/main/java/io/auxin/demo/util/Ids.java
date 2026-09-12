package io.auxin.demo.util;

import java.util.concurrent.atomic.AtomicLong;

/** Id helper. One live method, one dead method. */
public final class Ids {

    private static final AtomicLong SEQ = new AtomicLong();

    private Ids() {
    }

    /** Live: used by the admin endpoints. */
    public static String newId(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    /** GROUND TRUTH — DEAD. */
    public static String shortId() {
        return Long.toHexString(SEQ.get());
    }
}
