package io.auxin.demo.repo;

/**
 * Generic repository port.
 *
 * <p>Exists to force javac to emit <b>bridge methods</b> in the implementations:
 * {@code OrderRepository.save(Object)} and {@code CustomerRepository.save(Object)} are
 * synthetic bridges generated for the erased signature. CONTRACTS.md §1 says synthetic and
 * bridge methods get no probe and no manifest entry — this interface is what makes that
 * rule testable on a real class file rather than in theory.
 */
public interface Repository<T> {

    T save(T entity);

    T findById(String id);
}
