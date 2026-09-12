package com.datadog.trace;

/**
 * A customer class in a package that collides with an ignore-list entry (G5-BUG-1, defect 1).
 *
 * <p>{@code datadog/}, {@code com/newrelic/}, {@code com/dynatrace/} and
 * {@code com/appdynamics/} are on the ignore list so that a <i>broad</i> scope does not drag a
 * real APM's runtime classes into our transformer. Matched with {@code startsWith} and consulted
 * before the include scope, they also veto any customer whose own packages begin with those
 * strings — and a name filter must never quietly overrule a package the operator named.
 *
 * <p>So precedence is by specificity, and this one class exercises both directions.
 * {@code ax.include.packages=com.datadog.trace} is more specific than the veto
 * {@code com/datadog/} and wins: the class is instrumented, with one WARN saying so.
 * {@code ax.include.packages=com} -- an entirely ordinary company-wide scope -- is broader than
 * the veto and loses: the class is skipped, counted as {@code ignoredPrefix} AND
 * {@code inScopeVetoed}, with one WARN naming the class and the prefix responsible. Both
 * outcomes are announced; neither is silent, which is the whole of VALIDATION C37.
 */
public class CustomerCode {

    private long calls;

    public int handle(int n) {
        calls++;
        return n * 2 - 1;
    }

    public long calls() {
        return calls;
    }
}
