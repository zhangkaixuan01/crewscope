package io.crewscope.domain.workgraph;

import io.crewscope.domain.workitem.WorkItemId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure single-threaded cycle probing over the WorkGraph (ADR-033, framework-free).
 *
 * <p>This detector serves fast-fail validation only. Concurrent correctness is owned by the
 * transaction protocol — locking the two WorkProject rows, then running the recursive-CTE probe
 * inside the lock — so a {@code depthExhausted} result here means "undetermined", never "safe".
 *
 * <p>The two probes are deliberately isolated: SPLIT_FROM edges never join blocking cycle
 * detection (provenance is not ordering), and blocking edges never join ancestry walks.
 */
public final class RelationCycleDetector {

    /** ADR-033 frozen depth bound; also bounds the single-threaded walks. */
    public static final int MAX_DEPTH = 32;

    private RelationCycleDetector() {}

    /**
     * Outcome of a probe: {@code reachable} answers the query, {@code depthExhausted} marks that
     * the walk hit {@link #MAX_DEPTH} without deciding, which callers must treat as undecided.
     */
    public record Probe(boolean reachable, boolean depthExhausted) {}

    /**
     * Returns whether inserting the blocking edge {@code src BLOCKS dst} would close a cycle in
     * the existing blocking graph — i.e. whether {@code src} is transitively reachable from
     * {@code dst} along stored blocking edges.
     *
     * @param src node the new edge blocks behind (stored src side)
     * @param dst node being blocked by the new edge (stored dst side)
     * @param dependentsOf adjacency returning, per node, the WorkItems it blocks (stored edges
     *     where the node is the src side)
     */
    public static Probe probeBlocksCycle(
            WorkItemId src,
            WorkItemId dst,
            Function<WorkItemId, ? extends Iterable<WorkItemId>> dependentsOf) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dst, "dst");
        Objects.requireNonNull(dependentsOf, "dependentsOf");
        return walk(dst, src, dependentsOf);
    }

    /**
     * Returns whether inserting {@code child SPLIT_FROM source} would break the forest — i.e.
     * whether {@code child} already sits on the ancestor chain of {@code source}.
     *
     * @param child node receiving a new provenance edge (stored src side)
     * @param source WorkItem the child is split from (stored dst side)
     * @param parentOf adjacency returning, per node, its current split sources (stored SPLIT_FROM
     *     edges where the node is the src side)
     */
    public static Probe probeSplitAncestry(
            WorkItemId child,
            WorkItemId source,
            Function<WorkItemId, ? extends Iterable<WorkItemId>> parentOf) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parentOf, "parentOf");
        return walk(source, child, parentOf);
    }

    /**
     * Breadth-first walk from {@code start} towards {@code target}, one adjacency layer per
     * depth step. The start node sits at depth 0; hitting {@link #MAX_DEPTH} with an unexplored
     * frontier yields an undecided probe instead of a silent negative.
     */
    private static Probe walk(
            WorkItemId start,
            WorkItemId target,
            Function<WorkItemId, ? extends Iterable<WorkItemId>> adjacency) {
        Set<WorkItemId> visited = new HashSet<>();
        Deque<WorkItemId> frontier = new ArrayDeque<>();
        visited.add(start);
        frontier.add(start);
        for (int depth = 0; !frontier.isEmpty(); depth++) {
            if (frontier.contains(target)) {
                return new Probe(true, false);
            }
            if (depth == MAX_DEPTH) {
                return new Probe(false, true);
            }
            Deque<WorkItemId> next = new ArrayDeque<>();
            for (WorkItemId current : frontier) {
                for (WorkItemId neighbour : adjacency.apply(current)) {
                    Objects.requireNonNull(neighbour, "neighbour");
                    if (visited.add(neighbour)) {
                        next.add(neighbour);
                    }
                }
            }
            frontier = next;
        }
        return new Probe(false, false);
    }
}
