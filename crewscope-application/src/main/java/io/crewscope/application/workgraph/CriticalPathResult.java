package io.crewscope.application.workgraph;

import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Objects;

/**
 * Pure topological longest blocking chain ending at the seed WorkItem (ADR-033: never a
 * schedule — no dates, no effort weights), ordered from the root blocker down to the seed. When
 * either truncation flag is set the chain is a proven lower bound on the true longest chain,
 * because the bounded closure it was computed from was cut by the depth/row bounds.
 */
public record CriticalPathResult(
        List<WorkItemId> chain, boolean truncatedByDepth, boolean truncatedByRows) {

    public CriticalPathResult {
        chain = List.copyOf(Objects.requireNonNull(chain, "chain"));
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("chain must contain at least the seed WorkItem");
        }
    }
}
