package io.auxin.demo.repo;

import io.auxin.demo.model.Customer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory customer store. Also produces a bridge save(Object). */
public final class CustomerRepository implements Repository<Customer> {

    private final Map<String, Customer> byId = new ConcurrentHashMap<>();

    @Override
    public Customer save(Customer entity) {
        byId.put(entity.id(), entity);
        return entity;
    }

    @Override
    public Customer findById(String id) {
        return byId.get(id);
    }

    /**
     * GROUND TRUTH — DEAD.
     *
     * <p>Shaped like a scheduled maintenance job: the kind of method that is genuinely
     * dead in one deployment and genuinely live in another, which is why a verdict needs
     * a window and the phases it spanned, not just a boolean.
     */
    public int purgeInactive(long olderThanEpochMs) {
        int before = byId.size();
        byId.clear();
        return before;
    }
}
