package dev.auxin.staticscan.graph;

import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The subtype relation over the scanned artifact, and nothing else.
 *
 * <p>WHY the hierarchy stops at the artifact boundary: we scan one jar. Its superclasses in the JDK
 * and in third-party libraries are not present, so we cannot enumerate their implementors. Pretending
 * otherwise is how a call graph acquires false confidence -- and A5 already measured that 61% of
 * executed methods are missing from graphs built this way. {@link #isScanned(String)} is what lets
 * {@link CallGraphBuilder} say {@code unresolved} out loud instead of guessing.
 *
 * <p>Consciously NOT done: points-to analysis, and any attempt to reason about what a receiver
 * variable actually holds. JackEE -- the best published Spring-aware attempt -- reaches 58% of
 * application methods in 2 to 28 hours, and points-to measured <em>worse</em> recall than CHA. An
 * honestly-labelled over-approximation is the better trade.
 */
public final class ClassHierarchy {

    private final Map<String, ClassModel> byName = new HashMap<>();
    private final Map<String, Set<String>> directSubtypes = new HashMap<>();
    private final Map<String, Set<String>> transitiveSubtypes = new HashMap<>();

    public ClassHierarchy(Collection<ClassModel> classes) {
        for (ClassModel cls : classes) {
            byName.put(cls.name(), cls);
        }
        for (ClassModel cls : classes) {
            if (cls.superName() != null) {
                directSubtypes.computeIfAbsent(cls.superName(), k -> new LinkedHashSet<>()).add(cls.name());
            }
            for (String iface : cls.interfaceNames()) {
                directSubtypes.computeIfAbsent(iface, k -> new LinkedHashSet<>()).add(cls.name());
            }
        }
    }

    /** Whether this class was part of the scan; if not, nothing about its dispatch is knowable. */
    public boolean isScanned(String className) {
        return byName.containsKey(className);
    }

    public ClassModel get(String className) {
        return byName.get(className);
    }

    public Collection<ClassModel> classes() {
        return byName.values();
    }

    /** Every scanned transitive subtype of {@code className}, excluding {@code className} itself. */
    public Set<String> subtypesOf(String className) {
        Set<String> cached = transitiveSubtypes.get(className);
        if (cached != null) {
            return cached;
        }
        Set<String> found = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.push(className);
        visited.add(className);
        while (!pending.isEmpty()) {
            Set<String> children = directSubtypes.get(pending.pop());
            if (children == null) {
                continue;
            }
            for (String child : children) {
                if (visited.add(child)) {
                    found.add(child);
                    pending.push(child);
                }
            }
        }
        transitiveSubtypes.put(className, found);
        return found;
    }

    /** Whether {@code className} itself declares a member with this {@code name + desc}. */
    public boolean declares(String className, String nameAndDesc) {
        ClassModel cls = byName.get(className);
        if (cls == null) {
            return false;
        }
        for (MethodModel method : cls.methods()) {
            if (method.nameAndDesc().equals(nameAndDesc)) {
                return true;
            }
        }
        return false;
    }
}
