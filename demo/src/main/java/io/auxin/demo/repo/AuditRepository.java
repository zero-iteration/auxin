package io.auxin.demo.repo;

import java.util.ArrayList;
import java.util.List;

/**
 * GROUND TRUTH — NEVER LOADED.
 *
 * <p>No other class in this application references {@code AuditRepository}: not in code,
 * not in a constant pool. The JVM therefore never loads it, so at runtime it contributes
 * <b>no class entry and no probe array at all</b> — it is absent from the data rather than
 * present-and-zero.
 *
 * <p>That distinction is the whole point of {@code classesLoaded} in the wire protocol
 * (CONTRACTS.md §2). A pipeline that cannot tell "absent because never loaded" from
 * "present but never invoked" will report lazily-loaded code as dead.
 *
 * <p>Its {@code <clinit>} also never runs, which is the correct behaviour to assert for
 * the never-nominate-a-clinit rule.
 */
public final class AuditRepository {

    private static final List<String> ENTRIES = new ArrayList<>();

    static {
        // Never executed: the class is never loaded.
        ENTRIES.add("bootstrap");
    }

    public void record(String event) {
        ENTRIES.add(event);
    }

    public List<String> findAll() {
        return ENTRIES;
    }

    public int size() {
        return ENTRIES.size();
    }
}
