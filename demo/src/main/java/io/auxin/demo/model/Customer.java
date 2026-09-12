package io.auxin.demo.model;

/** A customer. {@code tier} drives the only real branch in pricing. */
public final class Customer {

    private final String id;
    private final String name;
    private final String tier;

    public Customer(String id, String name, String tier) {
        this.id = id;
        this.name = name;
        this.tier = tier;
    }

    public String id() {
        return id;
    }

    public String tier() {
        return tier;
    }

    /** Live: PricingService branches on this. */
    public boolean isVip() {
        return "vip".equals(tier);
    }

    /**
     * GROUND TRUTH — DEAD. A getter nobody calls.
     *
     * <p>Deliberately a trivial method (bytecode size below {@code MaxTrivialSize=6}), so
     * that a JIT-inlining regression check has a natural subject next to a dead one.
     */
    public String name() {
        return name;
    }

    /** GROUND TRUTH — DEAD. Compliance-shaped method, never wired up. */
    public Customer anonymize() {
        return new Customer(id, "REDACTED", tier);
    }
}
