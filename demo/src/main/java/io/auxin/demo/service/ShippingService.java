package io.auxin.demo.service;

import io.auxin.demo.Counters;
import io.auxin.demo.model.Order;

/** Shipping rates: one hot method, one rare method, one dead method. */
public final class ShippingService {

    /** Live on every request. Small on purpose (inlining-sensitive). */
    public int standardRate(Order order) {
        return 500 + (order.amountCents() / 200);
    }

    /**
     * GROUND TRUTH — RARE. Executed only when {@code index % 250 == 0 && index > 0}.
     *
     * <p>Guaranteed to run at least twice with the default {@code --min-requests=600}.
     * A shorter run can miss it entirely — which is the observation-window sensitivity
     * question this whole product is about, reproduced in miniature.
     */
    public int expressRate(Order order) {
        Counters.inc("rare.expressRate");
        return 1500 + (order.amountCents() / 100);
    }

    /** GROUND TRUTH — DEAD. No caller anywhere. */
    public int internationalRate(Order order, String countryCode) {
        return 2500 + (order.amountCents() / 50) + countryCode.length();
    }
}
