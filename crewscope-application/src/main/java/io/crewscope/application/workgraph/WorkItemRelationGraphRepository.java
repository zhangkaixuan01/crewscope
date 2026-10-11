package io.crewscope.application.workgraph;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workitem.WorkItemId;

/**
 * Persistence Port for the recursive-CTE WorkGraph probes (ADR-033).
 *
 * <p>Stored edges read {@code (src blocks dst)}; the split forest reads {@code (child, source)}.
 * The probes must run inside the WorkProject row locks held by the relation command protocol.
 * Their CTEs are written with {@code UNION} — never {@code UNION ALL} — and, being correctness
 * mechanisms, carry no depth or row bound.
 */
public interface WorkItemRelationGraphRepository {

    /**
     * Blocking-graph reachability: whether {@code blocker} blocks {@code candidate} directly or
     * transitively along stored BLOCKS edges. Unbounded by design — truncation could admit a
     * cycle. Inserting {@code src BLOCKS dst} would close a cycle exactly when this returns
     * true for {@code blocker = dst}, {@code candidate = src}.
     */
    boolean blocksReaches(OrganizationId organizationId, WorkItemId blocker, WorkItemId candidate);

    /**
     * Split-forest ancestry: whether {@code candidate} already sits on the ancestor chain of
     * {@code start} along stored SPLIT_FROM edges. Unbounded by design. Inserting {@code child
     * SPLIT_FROM source} would break the forest exactly when this returns true for {@code
     * start = source}, {@code candidate = child}.
     */
    boolean splitAncestorOf(OrganizationId organizationId, WorkItemId start, WorkItemId candidate);

    /**
     * Bounded upstream blocking walk for presentation (ADR-033): depth capped at {@link
     * BlockingChain#MAX_DEPTH}, rows at {@link BlockingChain#MAX_ROWS}, truncation reported
     * explicitly instead of silently dropped.
     */
    BlockingChain upstreamChain(OrganizationId organizationId, WorkItemId node);
}
