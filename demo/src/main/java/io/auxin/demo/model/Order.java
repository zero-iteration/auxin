package io.auxin.demo.model;

/** An order. Plain data; no framework, no annotations. */
public final class Order {

    private final String id;
    private final String customerId;
    private final int amountCents;
    private final String status;

    public Order(String id, String customerId, int amountCents, String status) {
        this.id = id;
        this.customerId = customerId;
        this.amountCents = amountCents;
        this.status = status;
    }

    public String id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public int amountCents() {
        return amountCents;
    }

    public String status() {
        return status;
    }

    public Order withStatus(String newStatus) {
        return new Order(id, customerId, amountCents, newStatus);
    }

    /**
     * GROUND TRUTH — DEAD. Never called by anything, on a class that is very much alive.
     *
     * <p>This is the case that matters most: a dead method inside a hot class. Static
     * reachability will not save you here and neither will "was the class loaded".
     */
    public String toLegacyCsv() {
        return id + "," + customerId + "," + amountCents + "," + status;
    }

    @Override
    public String toString() {
        return "Order{" + id + ", " + amountCents + ", " + status + "}";
    }
}
