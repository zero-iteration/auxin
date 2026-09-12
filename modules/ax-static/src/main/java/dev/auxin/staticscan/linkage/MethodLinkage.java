package dev.auxin.staticscan.linkage;

/**
 * What the call-graph component analysis concluded about one method.
 *
 * <p>{@code testOnlyReachable} is a {@link Boolean} and not a {@code boolean} on purpose: C50
 * requires an undecidable case to be reported as {@code null}, never as {@code false}. A
 * {@code false} here is a positive claim -- "something that is not a test reaches this" -- and we
 * are not entitled to make it merely because we failed to find an inbound edge in a graph that is
 * known to miss 61% of them.
 */
public record MethodLinkage(int sccId, Boolean testOnlyReachable) {
}
