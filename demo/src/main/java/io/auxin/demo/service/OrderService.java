package io.auxin.demo.service;

import io.auxin.demo.Counters;
import io.auxin.demo.model.Customer;
import io.auxin.demo.model.Order;
import io.auxin.demo.repo.CustomerRepository;
import io.auxin.demo.repo.OrderRepository;

/**
 * The one service with real branching. Every branch is keyed on the request index, so the
 * fixture is deterministic regardless of thread scheduling: with N requests,
 * {@code deepScan} runs exactly {@code floor((N-1)/500)} times, and so on.
 */
public final class OrderService {

    private static final int CUSTOMERS = 25;

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final PricingService pricing;
    private final ShippingService shipping;
    private final FraudService fraud;

    public OrderService(OrderRepository orders, CustomerRepository customers,
                        PricingService pricing, ShippingService shipping, FraudService fraud) {
        this.orders = orders;
        this.customers = customers;
        this.pricing = pricing;
        this.shipping = shipping;
        this.fraud = fraud;
    }

    /**
     * Hot path. Runs on every request.
     *
     * @param index global, monotonically increasing request index; all rare branches key
     *              off it so the ground truth is reproducible.
     * @return the total in cents, or -1 when the simulated downstream failure fired.
     */
    public int placeOrder(int index) {
        Counters.inc("placeOrder");

        String customerId = "cust-" + (index % CUSTOMERS);
        Customer customer = customers.findById(customerId);
        if (customer == null) {
            // Only the first CUSTOMERS requests take this branch — warm-up code, live but
            // only at the very start of the window. A short observation window that begins
            // after warm-up never sees it.
            Counters.inc("warmup.createCustomer");
            customer = customers.save(new Customer(customerId, "Customer " + (index % CUSTOMERS),
                    (index % 7 == 0) ? "vip" : (index % 3 == 0) ? "gold" : "standard"));
        }

        // Deterministic amount: 1000..50999 cents. No Random, so concurrency cannot make
        // the fixture non-reproducible.
        int amountCents = 1000 + (index * 37) % 50000;
        Order order = new Order("ord-" + index, customerId, amountCents, "NEW");

        int total = pricing.quote(order, customer) + shipping.standardRate(order);

        if (index > 0 && index % 250 == 0) {
            total += shipping.expressRate(order);
        }
        if (index > 0 && index % 500 == 0) {
            fraud.deepScan(order);
        }

        orders.save(order.withStatus("PLACED"));

        if (index > 0 && index % 137 == 0) {
            // Simulated downstream failure -> error path. This is the only place `refund`
            // is reached, and it is also what gives tier-2 a non-zero errorTypes bucket.
            Counters.inc("errors");
            refund(order, "simulated-downstream-failure");
            return -1;
        }
        return total;
    }

    /**
     * GROUND TRUTH — RARE (error path only). Executed when {@code index % 137 == 0 && index > 0}.
     * Guaranteed at least four times with the default {@code --min-requests=600}.
     */
    public Order refund(Order order, String reason) {
        Counters.inc("rare.refund");
        return orders.save(order.withStatus("REFUNDED"));
    }

    /** Live: the admin status endpoint reads this. */
    public int placedCount() {
        return orders.count();
    }

    /**
     * GROUND TRUTH — DEAD. The break-glass method.
     *
     * <p>This is the Sensenmann anecdote in code form: a "stop the robot uprising" button
     * that runs once every couple of years and looks dead for all intents and purposes.
     * The correct verdict for it is UNKNOWN via the suppression file, never DEAD — which
     * is why {@code demo/.auxin/suppress.txt} lists it.
     */
    public int cancelAll() {
        Counters.inc("dead.cancelAll");
        return 0;
    }
}
