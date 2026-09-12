package io.auxin.demo.service;

import io.auxin.demo.model.Customer;
import io.auxin.demo.model.Order;

import java.util.Map;

/** Quotes a price. Has a live {@code <clinit>} and one never-called method. */
public final class PricingService {

    /**
     * A static initializer that DOES run. {@code <clinit>} is the single most-missed
     * method kind in static call graphs (56,708 misses across the 13 tools measured in
     * the ISSTA-2024 study), which is why the rule is: treat every {@code <clinit>} of a
     * reachable type as reachable, and never nominate one for deletion.
     */
    private static final Map<String, Integer> TIER_DISCOUNT_BPS = Map.of(
            "vip", 1000,
            "gold", 500,
            "standard", 0);

    public int quote(Order order, Customer customer) {
        int base = order.amountCents();
        // A real lambda body: javac emits a synthetic `lambda$quote$0` method and the JVM
        // spins a `$$Lambda` class at runtime. Both must be excluded from the manifest
        // (synthetic) and from the runtime class inventory (generated name pattern).
        java.util.function.IntUnaryOperator surcharge = cents -> cents + (cents / 100);
        int withSurcharge = surcharge.applyAsInt(base);
        return applyTierDiscount(withSurcharge, customer);
    }

    /** Live: called from {@link #quote}. */
    public int applyTierDiscount(int cents, Customer customer) {
        int bps = TIER_DISCOUNT_BPS.getOrDefault(customer.tier(), 0);
        if (customer.isVip()) {
            bps += 250;
        }
        return cents - (cents * bps / 10000);
    }

    /**
     * GROUND TRUTH — DEAD. The previous pricing scheme, left behind after a migration.
     *
     * <p>89.5% mean / 96.3% median of dead methods in the literature were "born dead" —
     * never executed from the moment they were committed. This one is the other kind: it
     * used to run, and stopped. Both have to end up in the same bucket.
     */
    public int applyLegacyDiscount(int cents) {
        return cents - (cents / 20);
    }
}
