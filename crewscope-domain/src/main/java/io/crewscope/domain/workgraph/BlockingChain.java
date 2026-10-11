package io.crewscope.domain.workgraph;

import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Objects;

/**
 * Bounded upstream blocking walk from one WorkItem (ADR-033).
 *
 * <p>Depth is capped at {@link #MAX_DEPTH} and returned rows at {@link #MAX_ROWS}; hitting a cap
 * is reported through the explicit truncated flags instead of silently dropping results. This is
 * a presentation query — cycle *detection* is unbounded by design.
 */
public record BlockingChain(WorkItemId root, List<Step> steps, boolean truncatedByDepth, boolean truncatedByRows) {

    public static final int MAX_DEPTH = 32;
    public static final int MAX_ROWS = 5000;

    /** One node on the walk, {@code depth} counting steps away from the root. */
    public record Step(WorkItemId node, int depth) {
        public Step {
            Objects.requireNonNull(node, "node");
            if (depth < 1 || depth > MAX_DEPTH) {
                throw new IllegalArgumentException("depth must be within 1.." + MAX_DEPTH);
            }
        }
    }

    public BlockingChain {
        Objects.requireNonNull(root, "root");
        steps = List.copyOf(steps);
        if (steps.size() > MAX_ROWS) {
            throw new IllegalArgumentException("steps must not exceed " + MAX_ROWS + " rows");
        }
    }
}
