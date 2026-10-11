package io.crewscope.application.workgraph;

import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded upstream blocking closure of one WorkItem (ADR-033): every stored BLOCKS edge within
 * depth {@link BlockingChain#MAX_DEPTH} and row {@link BlockingChain#MAX_ROWS}, shaped as the
 * adjacency the domain CriticalPath walk consumes. Truncation is reported, never silent — a
 * truncated closure yields a critical path that is a lower bound, not a fact.
 */
public record BlockedUpstreamClosure(
        Map<WorkItemId, List<WorkItemId>> upstreamOf,
        boolean truncatedByDepth,
        boolean truncatedByRows) {

    public BlockedUpstreamClosure {
        upstreamOf = copyAdjacency(upstreamOf);
    }

    /** Per-node copy that never lets one (blocked, blocker) pair repeat. */
    private static Map<WorkItemId, List<WorkItemId>> copyAdjacency(
            Map<WorkItemId, List<WorkItemId>> upstreamOf) {
        Objects.requireNonNull(upstreamOf, "upstreamOf");
        Map<WorkItemId, List<WorkItemId>> copy = new LinkedHashMap<>();
        upstreamOf.forEach(
                (blocked, blockers) -> {
                    Objects.requireNonNull(blocked, "blocked");
                    copy.put(
                            blocked,
                            blockers.stream()
                                    .map(blocker -> Objects.requireNonNull(blocker, "blocker"))
                                    .distinct()
                                    .toList());
                });
        return Map.copyOf(copy);
    }
}
