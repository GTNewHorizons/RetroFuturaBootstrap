package com.gtnewhorizons.retrofuturabootstrap.algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Modified Kahn's topological sort algorithm that maintains the input order of elements as closely as possible.
 * <a href="https://www.geeksforgeeks.org/lexicographically-smallest-topological-ordering/">Source</a>
 */
public class StableTopologicalSort {

    /**
     * Does a "stable" topological sort of the input data, creates a new list with the sorted output.
     * @param data Ordered list of input data.
     * @param edges Vertex adjacency lists describing the graph to sort, indexed by indices into data. edges.get(i).add(j) creates an edge from i to j (ordering i before j).
     * @return Sorted data.
     * @param <T> Type of data to sort.
     */
    public static <T> @NotNull List<T> sort(
            final @NotNull List<T> data, final @NotNull List<@NotNull List<@NotNull Integer>> edges)
            throws CycleException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(edges, "edges");
        if (edges.size() != data.size()) {
            throw new IllegalStateException("edges size != data size");
        }

        final int vertexCount = data.size();
        final int[] inDegree = new int[vertexCount];
        // Calculate inDegree
        for (int vtxA = 0; vtxA < vertexCount; vtxA++) {
            for (int vtxB : edges.get(vtxA)) {
                inDegree[vtxB]++;
            }
        }
        PriorityQueue<Integer> queue = new PriorityQueue<>(vertexCount);
        // Enqueue all vertices that don't have incoming edges, sorted by original index.
        for (int i = 0; i < vertexCount; i++) {
            if (inDegree[i] == 0) {
                queue.add(i);
            }
        }
        final List<T> output = new ArrayList<>(data.size());

        // Process the queue
        while (!queue.isEmpty()) {
            final int next = queue.poll();
            output.add(data.get(next));
            for (final int adjacent : edges.get(next)) {
                if ((--inDegree[adjacent]) == 0) {
                    queue.add(adjacent);
                }
            }
        }

        if (output.size() != vertexCount) {
            // Cycle found
            final Set<T> remaining = Collections.newSetFromMap(new IdentityHashMap<>());
            remaining.addAll(data);
            for (T sorted : output) {
                remaining.remove(sorted);
            }
            throw new CycleException(remaining);
        }

        return output;
    }

    public static class CycleException extends Exception {
        private final @NotNull Set<?> cyclicElements;

        CycleException(@NotNull Set<?> cyclicElements) {
            super("Cycle found");
            this.cyclicElements = cyclicElements;
        }

        @SuppressWarnings("unchecked")
        public <T> @NotNull Set<T> cyclicElements(@NotNull Class<T> elementType) {
            if (cyclicElements.isEmpty()) {
                return Collections.emptySet();
            }
            if (!elementType.isAssignableFrom(cyclicElements.iterator().next().getClass())) {
                throw new IllegalArgumentException("Wrong element type " + elementType + " for a set of "
                        + cyclicElements.iterator().next().getClass());
            }
            return (Set<T>) Collections.unmodifiableSet(cyclicElements);
        }
    }
}
