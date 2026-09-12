package ax.bench.g2.chain;

import ax.bench.g2.ChainWork;

/**
 * Small-method-heavy call graph with the shape real application code has:
 * trivial getters, short delegating methods, a deep-ish delegation chain, and a wide fan-out.
 * This is the G2 subject. It is compiled by javac, then the SAME bytes are either loaded
 * unchanged or run through the probe installer, so PrintInlining output is line-comparable.
 */
public final class Engine implements ChainWork {

    private final Point[] pts = new Point[16];
    private long sink;

    public Engine() {
        for (int i = 0; i < pts.length; i++) pts[i] = new Point(i, i * 3, i * 7, i * 11);
    }

    @Override
    public long checksum() { return sink; }

    @Override
    public int work(int x) {
        int acc = 0;
        for (int i = 0; i < 16; i++) {
            acc += level0(pts[i], x + i);
        }
        // deliberately LUKEWARM call site: taken ~1 in 1024 iterations, so the C2 frequency
        // exemption (FreqInlineSize=325) should NOT apply and MaxInlineSize=35 should govern.
        if ((x & 0x1F) == 0) acc += coldish(x);
        sink += acc;
        return acc;
    }

    private int coldish(int x) { return pad(x) ^ Mixer.mix(x, 5); }

    /** ~33 bytes: under MaxInlineSize=35 before probing, over it after. */
    private int pad(int x) {
        int h = x + 0x7ED55D16;
        h ^= h >>> 12;
        h += h << 3;
        h ^= h >>> 4;
        return h * 0x27D4EB2F;
    }

    // --- deep delegation chain of short methods -------------------------------------------
    private int level0(Point p, int x) { return level1(p, x) + p.getX(); }
    private int level1(Point p, int x) { return level2(p, x) ^ p.getY(); }
    private int level2(Point p, int x) { return level3(p, x) + p.getZ(); }
    private int level3(Point p, int x) { return level4(p, x) ^ p.getW(); }
    private int level4(Point p, int x) { return level5(p, x) + p.sum(); }
    private int level5(Point p, int x) { return level6(p, x) ^ Mixer.inc(x); }
    private int level6(Point p, int x) { return level7(p, x) + Mixer.neg(x); }
    private int level7(Point p, int x) { return fan(p, x); }

    // --- wide fan-out over methods sized around the inline thresholds ----------------------
    private int fan(Point p, int x) {
        int a = p.scaled(x);
        int b = Mixer.mix(x, a);
        int c = Mixer.fold(a, b, x);
        int d = s0(x) + s1(x) + s2(x) + s3(x) + s4(x) + s5(x) + s6(x) + s7(x);
        return a ^ b ^ c ^ d;
    }

    private int s0(int x) { return Mixer.mix(x, 1); }
    private int s1(int x) { return Mixer.mix(x, 2); }
    private int s2(int x) { return Mixer.fold(x, 3, 5); }
    private int s3(int x) { return Mixer.fold(x, 4, 6); }
    private int s4(int x) { return x * 31 + 7; }
    private int s5(int x) { return x ^ (x >>> 5); }
    private int s6(int x) { return Mixer.inc(Mixer.neg(x)); }
    private int s7(int x) { return Mixer.neg(Mixer.inc(x)); }
}
