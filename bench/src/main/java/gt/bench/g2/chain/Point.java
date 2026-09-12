package ax.bench.g2.chain;

/** Trivial accessors: 5-byte bodies, i.e. under HotSpot's MaxTrivialSize=6. */
public final class Point {
    private int x, y, z, w;

    public Point(int x, int y, int z, int w) { this.x = x; this.y = y; this.z = z; this.w = w; }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getW() { return w; }

    public void setX(int v) { x = v; }

    /** ~15 bytes: still comfortably inlinable either way. */
    public int sum() { return x + y + z + w; }

    /** ~30 bytes: sits just under MaxInlineSize=35, so one probe pushes it over. */
    public int scaled(int k) {
        int h = x * k;
        h += y * 31;
        h ^= z << 3;
        h -= w;
        return h ^ (h >>> 7);
    }
}
