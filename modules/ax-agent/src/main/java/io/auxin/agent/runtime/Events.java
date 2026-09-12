package io.auxin.agent.runtime;

/**
 * One tier-2 event packed into one {@code long} (C28).
 *
 * <pre>
 *   bit 63     : marker, always 1 (0 means "slot unpublished" in the ring)
 *   bit 62     : timed (0 when the sample was not timed, e.g. 1-in-64 clock sampling)
 *   bits 48-61 : unused
 *   bits 32-47 : methodId    (16 bits, 65536 tier-2 methods)
 *   bits 24-31 : errorClassId ( 8 bits, 0 = no error, 255 = overflow bucket)
 *   bits 10-23 : unused
 *   bits  0-9  : log-linear duration bucket (10 bits, long[1024])
 * </pre>
 */
public final class Events {

    public static final long MARKER = 1L << 63;
    public static final long TIMED = 1L << 62;
    public static final int MAX_METHOD_ID = 0xFFFF;
    public static final int MAX_ERROR_ID = 0xFF;

    public static long pack(int methodId, int errorClassId, int bucket, boolean timed) {
        return MARKER
                | (timed ? TIMED : 0L)
                | ((long) (methodId & 0xFFFF) << 32)
                | ((long) (errorClassId & 0xFF) << 24)
                | (long) (bucket & 0x3FF);
    }

    public static int methodId(long e) { return (int) ((e >>> 32) & 0xFFFF); }
    public static int errorClassId(long e) { return (int) ((e >>> 24) & 0xFF); }
    public static int bucket(long e) { return (int) (e & 0x3FF); }
    public static boolean timed(long e) { return (e & TIMED) != 0; }

    private Events() { throw new AssertionError(); }
}
