package ax.bench.g3;

/** G3 subject. Has fields as well as methods so the "schema unchanged" assertion is non-vacuous. */
public class Subject {

    public int field1 = 7;
    public static long field2 = 9L;
    private String field3 = "three";

    public int alpha() { return 11; }
    public int beta()  { return 22; }
    public int gamma() { return 33; }
    public int delta(int x) { return x * 2 + field1; }
    public static String epsilon() { return "eps"; }

    public String field3() { return field3; }
}
