package dev.auxin.staticscan.tier2;

import java.util.List;

/**
 * Thrown when tier-2 selection is larger than {@code --tier2-max}.
 *
 * <p>WHY this is a failed scan and not a truncation: tier-2 is the one tier that spends application
 * latency -- two {@code nanoTime} reads plus a ring write, measured at roughly 80ns per call per
 * method (PLAN-v2, "the tiers") -- and PLAN-v2 sizes the allowlist at 50-200 boundary methods for
 * that reason. Silently keeping the first N would ship an allowlist nobody chose and quietly drop
 * the timings the author asked for; silently proceeding would ship the cost. Both are the same
 * defect: a number nobody decided. So the scan stops, says what the number was, says what is
 * spending it, and leaves raising the limit to a human who has read that.
 */
public final class Tier2BudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How many reasons to list before summarising the rest. */
    private static final int TOP_CONTRIBUTORS = 5;

    private final int selected;
    private final int max;

    public Tier2BudgetExceededException(Tier2Selection over, int max) {
        super(message(over, max));
        this.selected = over.count();
        this.max = max;
    }

    /** How many methods the selection came to. */
    public int selected() {
        return selected;
    }

    /** The budget it broke. */
    public int max() {
        return max;
    }

    private static String message(Tier2Selection over, int max) {
        StringBuilder sb = new StringBuilder();
        sb.append("tier-2 selection is ").append(over.count())
                .append(" methods, which exceeds --tier2-max ").append(max);
        sb.append("\n  top contributors, by selection reason:");
        List<String> lines = over.breakdownLines();
        for (int i = 0; i < lines.size() && i < TOP_CONTRIBUTORS; i++) {
            sb.append("\n    ").append(lines.get(i));
        }
        if (lines.size() > TOP_CONTRIBUTORS) {
            sb.append("\n    ... and ").append(lines.size() - TOP_CONTRIBUTORS)
                    .append(" further reason(s)");
        }
        sb.append("\n  Tier-2 costs the application roughly 80ns per call per method (two nanoTime")
                .append("\n  reads plus a ring write), and PLAN-v2 sizes the allowlist at 50-200")
                .append("\n  boundary methods. Narrow the --tier2 patterns, drop methods with")
                .append("\n  --tier2-exclude, or raise --tier2-max deliberately.");
        return sb.toString();
    }
}
