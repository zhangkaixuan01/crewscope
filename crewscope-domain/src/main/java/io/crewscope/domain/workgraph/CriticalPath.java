package io.crewscope.domain.workgraph;

import io.crewscope.domain.workitem.WorkItemId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Longest blocking chain in pure topological terms (ADR-033): the chain with the most nodes,
 * never a schedule — no dates, no effort weights, no predicted completion.
 *
 * <p>The walk is iterative with an explicit in-progress set, so deep chains cannot overflow the
 * call stack and an accidentally cyclic upstream graph fails fast instead of looping.
 */
public final class CriticalPath {

    private CriticalPath() {}

    /**
     * Returns the longest upstream blocking chain ending at {@code from}, ordered from the root
     * blocker down to {@code from}. Ties resolve to the first chain encountered.
     *
     * @param upstreamOf adjacency returning, per node, the WorkItems blocking it (stored BLOCKS
     *     edges where the node is the dst side); must describe an acyclic graph
     * @throws IllegalArgumentException if the upstream graph reachable from {@code from} contains
     *     a cycle
     */
    public static List<WorkItemId> longestUpstreamChain(
            WorkItemId from, Function<WorkItemId, ? extends Iterable<WorkItemId>> upstreamOf) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(upstreamOf, "upstreamOf");
        Map<WorkItemId, List<WorkItemId>> adjacency = new HashMap<>();
        Map<WorkItemId, List<WorkItemId>> longest = new HashMap<>();
        Set<WorkItemId> inProgress = new HashSet<>();
        Deque<WorkItemId> path = new ArrayDeque<>();
        Deque<Iterator<WorkItemId>> pending = new ArrayDeque<>();

        path.push(from);
        inProgress.add(from);
        pending.push(expanded(adjacency, from, upstreamOf).iterator());

        while (!pending.isEmpty()) {
            if (pending.peek().hasNext()) {
                WorkItemId next = Objects.requireNonNull(pending.peek().next(), "upstream");
                if (inProgress.contains(next)) {
                    throw new IllegalArgumentException("upstream graph must be acyclic");
                }
                if (longest.containsKey(next)) {
                    continue;
                }
                path.push(next);
                inProgress.add(next);
                pending.push(expanded(adjacency, next, upstreamOf).iterator());
            } else {
                pending.pop();
                WorkItemId node = path.pop();
                inProgress.remove(node);
                longest.put(node, longestChainEndingAt(node, adjacency.get(node), longest));
            }
        }
        return longest.get(from);
    }

    /** node plus the longest already-computed upstream chain among its blockers. */
    private static List<WorkItemId> longestChainEndingAt(
            WorkItemId node, List<WorkItemId> upstreams, Map<WorkItemId, List<WorkItemId>> longest) {
        List<WorkItemId> best = List.of(node);
        for (WorkItemId upstream : upstreams) {
            List<WorkItemId> chain = longest.get(upstream);
            if (chain != null && chain.size() + 1 > best.size()) {
                List<WorkItemId> extended = new ArrayList<>(chain.size() + 1);
                extended.addAll(chain);
                extended.add(node);
                best = List.copyOf(extended);
            }
        }
        return best;
    }

    private static List<WorkItemId> expanded(
            Map<WorkItemId, List<WorkItemId>> adjacency,
            WorkItemId node,
            Function<WorkItemId, ? extends Iterable<WorkItemId>> upstreamOf) {
        return adjacency.computeIfAbsent(node, key -> {
            List<WorkItemId> upstreams = new ArrayList<>();
            for (WorkItemId upstream : upstreamOf.apply(key)) {
                upstreams.add(Objects.requireNonNull(upstream, "upstream"));
            }
            return List.copyOf(upstreams);
        });
    }
}
