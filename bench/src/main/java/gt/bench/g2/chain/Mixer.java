package ax.bench.g2.chain;

/** Static helpers sized deliberately around MaxInlineSize=35. */
public final class Mixer {

    private Mixer() {}

    /** ~31 bytes. +1 probe = ~39-40 bytes, i.e. across MaxInlineSize. */
    public static int mix(int a, int b) {
        int h = a * 0x9E3779B1;
        h ^= b + 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        return h ^ (h >>> 16);
    }

    /** ~28 bytes. */
    public static int fold(int a, int b, int c) {
        int h = a ^ b;
        h = h * 31 + c;
        h ^= h >>> 11;
        return h + 0x27D4EB2F;
    }

    /** 4 bytes, trivially inlinable. */
    public static int inc(int a) { return a + 1; }

    /** 5 bytes. */
    public static int neg(int a) { return -a; }
}
