package dev.auxin.staticscan.linkage;

import dev.auxin.staticscan.graph.ResolvedEdge;
import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;
import dev.auxin.staticscan.model.MethodRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses the call graph into strongly-connected components and decides, per method, whether the
 * only thing keeping it alive is its own tests (C50).
 *
 * <p><b>The trap being defended against.</b> Sensenmann, verbatim: <i>"The testing infrastructure is
 * going to run all those tests, including lib2_test, despite lib2 never being executed 'for real'.
 * This means we cannot use test runs as a 'liveness' signal."</i> Excluding test classes from the
 * inventory is necessary but not sufficient -- a library and its test can form a cycle, and a cycle
 * that is only entered from the test keeps itself alive under any naive inbound-edge rule. Tarjan's
 * algorithm collapses that cycle into one component so the rule can see through it.
 *
 * <p><b>The decision, stated exactly.</b> For method {@code m} in class {@code C}:
 * <ol>
 *   <li>{@code C} is test code, so {@code m} is test code: <b>true</b>;</li>
 *   <li>{@code m} is an entry point of a non-test class -- something outside the artifact invokes
 *       it: <b>false</b>;</li>
 *   <li>{@code m} has no inbound blocking edge at all: <b>null</b>. No edge is not evidence of no
 *       caller; reflection, dependency injection and callers in other artifacts are all invisible
 *       here;</li>
 *   <li>some inbound edge comes from a non-test method outside {@code m}'s own component:
 *       <b>false</b>;</li>
 *   <li>every inbound edge is from a test, or from inside {@code m}'s own component and that
 *       component contains test code: <b>true</b>;</li>
 *   <li>otherwise -- inbound edges exist but all come from inside a component with no test in it,
 *       a self-sustaining cycle nothing external enters: <b>null</b>.</li>
 * </ol>
 *
 * <p>Rule 4 is knowingly local: it says "a non-test caller exists", not "a live non-test caller
 * exists". That over-reports {@code false}, which keeps code alive, which is the direction to be
 * wrong in.
 *
 * <p>Only {@code blocking} edges are followed. A {@code noop} edge (C52) -- an {@code instanceof}
 * test, a {@code .class} literal -- constant-folds away with its target and therefore cannot be what
 * keeps a method alive.
 */
public final class TestLinkageAnalyzer {

    /**
     * @param classes               every scanned class
     * @param testClassNames        names of classes {@link TestClassifier} identified as test code
     * @param edges                 the resolved call graph
     * @param productionEntryPoints entry-point methods on non-test classes
     * @return one {@link MethodLinkage} per declared method of every scanned class
     */
    public Map<MethodRef, MethodLinkage> analyse(Collection<ClassModel> classes,
                                                 Set<String> testClassNames,
                                                 Collection<ResolvedEdge> edges,
                                                 Set<MethodRef> productionEntryPoints) {
        Graph graph = Graph.of(classes, edges);
        int[] component = new Tarjan(graph).components();
        int[] canonical = canonicalise(graph, component);

        boolean[] componentHasTest = new boolean[countComponents(canonical)];
        for (int node = 0; node < graph.size(); node++) {
            if (testClassNames.contains(graph.node(node).className())) {
                componentHasTest[canonical[node]] = true;
            }
        }

        Map<MethodRef, MethodLinkage> linkage = new LinkedHashMap<>();
        for (int node = 0; node < graph.size(); node++) {
            MethodRef ref = graph.node(node);
            linkage.put(ref, new MethodLinkage(canonical[node],
                    decide(graph, node, canonical, componentHasTest, testClassNames,
                            productionEntryPoints)));
        }
        return linkage;
    }

    private Boolean decide(Graph graph,
                           int node,
                           int[] component,
                           boolean[] componentHasTest,
                           Set<String> testClassNames,
                           Set<MethodRef> productionEntryPoints) {
        MethodRef ref = graph.node(node);
        if (testClassNames.contains(ref.className())) {
            return Boolean.TRUE;
        }
        if (productionEntryPoints.contains(ref)) {
            return Boolean.FALSE;
        }

        int[] inbound = graph.inbound(node);
        if (inbound.length == 0) {
            return null;
        }
        boolean sawTestCaller = false;
        for (int caller : inbound) {
            if (testClassNames.contains(graph.node(caller).className())) {
                sawTestCaller = true;
            } else if (component[caller] != component[node]) {
                return Boolean.FALSE;
            }
        }
        if (!sawTestCaller && !componentHasTest[component[node]]) {
            return null;
        }
        return Boolean.TRUE;
    }

    /**
     * Renumbers components so the ids depend only on the graph, not on traversal order: component 0
     * is the one containing the lexicographically smallest method reference, and so on. Without this
     * the ids would change between builds of identical source, and a manifest diff would be noise.
     */
    private int[] canonicalise(Graph graph, int[] component) {
        int componentCount = countComponents(component);
        int[] smallestNode = new int[componentCount];
        Arrays.fill(smallestNode, Integer.MAX_VALUE);
        for (int node = 0; node < component.length; node++) {
            int c = component[node];
            if (node < smallestNode[c]) {
                smallestNode[c] = node;
            }
        }
        Integer[] order = new Integer[componentCount];
        for (int c = 0; c < componentCount; c++) {
            order[c] = c;
        }
        // graph nodes are already in lexicographic order, so the smallest index is the smallest ref.
        Arrays.sort(order, Comparator.comparingInt(c -> smallestNode[c]));

        int[] renumbered = new int[componentCount];
        for (int rank = 0; rank < componentCount; rank++) {
            renumbered[order[rank]] = rank;
        }
        int[] result = new int[component.length];
        for (int node = 0; node < component.length; node++) {
            result[node] = renumbered[component[node]];
        }
        return result;
    }

    private int countComponents(int[] component) {
        int max = -1;
        for (int c : component) {
            max = Math.max(max, c);
        }
        return max + 1;
    }

    /**
     * The call graph as dense integer adjacency.
     *
     * <p>Nodes are every declared method of every scanned class, sorted by their canonical reference
     * so that node ids -- and therefore component ids -- are a function of the artifact alone. Edges
     * whose target is outside the artifact are excluded: they cannot participate in a cycle, and
     * they cannot make an in-artifact method reachable.
     */
    private static final class Graph {

        private final List<MethodRef> nodes;
        private final int[][] successors;
        private final int[][] predecessors;

        private Graph(List<MethodRef> nodes, int[][] successors, int[][] predecessors) {
            this.nodes = nodes;
            this.successors = successors;
            this.predecessors = predecessors;
        }

        static Graph of(Collection<ClassModel> classes, Collection<ResolvedEdge> edges) {
            List<MethodRef> nodes = new ArrayList<>();
            for (ClassModel cls : classes) {
                for (MethodModel method : cls.methods()) {
                    nodes.add(new MethodRef(cls.name(), method.name(), method.desc()));
                }
            }
            nodes.sort(Comparator.comparing(MethodRef::toRef));

            Map<MethodRef, Integer> ids = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                ids.put(nodes.get(i), i);
            }

            List<List<Integer>> out = new ArrayList<>(nodes.size());
            List<List<Integer>> in = new ArrayList<>(nodes.size());
            for (int i = 0; i < nodes.size(); i++) {
                out.add(new ArrayList<>());
                in.add(new ArrayList<>());
            }
            for (ResolvedEdge edge : edges) {
                if (!edge.isBlocking()) {
                    continue;
                }
                Integer from = ids.get(edge.from());
                Integer to = ids.get(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                out.get(from).add(to);
                in.get(to).add(from);
            }
            return new Graph(nodes, compact(out), compact(in));
        }

        private static int[][] compact(List<List<Integer>> lists) {
            int[][] arrays = new int[lists.size()][];
            for (int i = 0; i < lists.size(); i++) {
                List<Integer> list = lists.get(i);
                int[] array = new int[list.size()];
                for (int j = 0; j < array.length; j++) {
                    array[j] = list.get(j);
                }
                arrays[i] = array;
            }
            return arrays;
        }

        int size() {
            return nodes.size();
        }

        MethodRef node(int id) {
            return nodes.get(id);
        }

        int[] outbound(int id) {
            return successors[id];
        }

        int[] inbound(int id) {
            return predecessors[id];
        }
    }

    /**
     * Tarjan's strongly-connected-components algorithm, iterative.
     *
     * <p>Iterative rather than recursive because the recursion depth equals the longest simple path
     * in the call graph. On a real service that is thousands of frames deep, and a
     * {@link StackOverflowError} inside a build step would surface as a mysteriously missing
     * manifest rather than as an error anyone could act on.
     */
    private static final class Tarjan {

        private static final int UNVISITED = -1;

        private final Graph graph;
        private final int[] index;
        private final int[] lowLink;
        private final int[] component;
        private final boolean[] onStack;
        private final Deque<Integer> stack = new ArrayDeque<>();
        private int nextIndex;
        private int nextComponent;

        Tarjan(Graph graph) {
            this.graph = graph;
            int n = graph.size();
            this.index = new int[n];
            this.lowLink = new int[n];
            this.component = new int[n];
            this.onStack = new boolean[n];
            Arrays.fill(index, UNVISITED);
            Arrays.fill(component, UNVISITED);
        }

        int[] components() {
            int n = graph.size();
            int[] frameNode = new int[n + 1];
            int[] frameChild = new int[n + 1];
            for (int root = 0; root < n; root++) {
                if (index[root] != UNVISITED) {
                    continue;
                }
                int depth = 0;
                push(root);
                frameNode[0] = root;
                frameChild[0] = 0;
                while (depth >= 0) {
                    int v = frameNode[depth];
                    int[] successors = graph.outbound(v);
                    if (frameChild[depth] < successors.length) {
                        int w = successors[frameChild[depth]++];
                        if (index[w] == UNVISITED) {
                            push(w);
                            depth++;
                            frameNode[depth] = w;
                            frameChild[depth] = 0;
                        } else if (onStack[w]) {
                            lowLink[v] = Math.min(lowLink[v], index[w]);
                        }
                    } else {
                        if (lowLink[v] == index[v]) {
                            closeComponent(v);
                        }
                        depth--;
                        if (depth >= 0) {
                            int parent = frameNode[depth];
                            lowLink[parent] = Math.min(lowLink[parent], lowLink[v]);
                        }
                    }
                }
            }
            return component;
        }

        private void push(int v) {
            index[v] = nextIndex;
            lowLink[v] = nextIndex;
            nextIndex++;
            stack.push(v);
            onStack[v] = true;
        }

        private void closeComponent(int root) {
            int w;
            do {
                w = stack.pop();
                onStack[w] = false;
                component[w] = nextComponent;
            } while (w != root);
            nextComponent++;
        }
    }
}
