package io.auxin.demo.repo;

import io.auxin.demo.model.Order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory order store. Implements {@link Repository}, so javac emits a bridge save(Object). */
public final class OrderRepository implements Repository<Order> {

    private final Map<String, Order> byId = new ConcurrentHashMap<>();

    @Override
    public Order save(Order entity) {
        byId.put(entity.id(), entity);
        return entity;
    }

    @Override
    public Order findById(String id) {
        return byId.get(id);
    }

    /** Live: called by the admin status endpoint. */
    public int count() {
        return byId.size();
    }

    /** GROUND TRUTH — DEAD. Nothing deletes. */
    public boolean deleteById(String id) {
        return byId.remove(id) != null;
    }

    /**
     * GROUND TRUTH — DEAD.
     *
     * <p>A plausible-looking query method with no caller. Note it would be trivially
     * "reachable" from any entry point a naive static analysis decided to be generous
     * about, which is why static reachability is a corroborating signal here and never a
     * basis for deletion.
     */
    public List<Order> findByCustomer(String customerId) {
        List<Order> out = new ArrayList<>();
        for (Order o : byId.values()) {
            if (o.customerId().equals(customerId)) {
                out.add(o);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
