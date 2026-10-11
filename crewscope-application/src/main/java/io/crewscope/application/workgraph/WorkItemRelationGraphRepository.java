package io.crewscope.application.workgraph;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Optional;

/**
 * Persistence Port for the recursive-CTE WorkGraph probes and derived reads (ADR-033).
 *
 * <p>Stored edges read {@code (src blocks dst)}; the split forest reads {@code (child, source)}.
 * The two reachability probes must run inside the WorkProject row locks held by the relation
 * command protocol; their CTEs are written with {@code UNION} — never {@code UNION ALL} — and,
 * being correctness mechanisms, carry no depth or row bound. The presentation reads below apply
 * the frozen depth 32 / row 5000 bounds and report truncation explicitly instead.
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

    /**
     * Bounded upstream blocking closure of {@code node} shaped as CriticalPath adjacency: every
     * stored BLOCKS edge within the same depth/row bounds, truncation reported explicitly. A
     * presentation mechanism, not a correctness one — the cycle probes above stay unbounded.
     */
    BlockedUpstreamClosure upstreamClosure(OrganizationId organizationId, WorkItemId node);

    /**
     * One keyset page of the derived "currently blocked" aggregation (ADR-033): the Team's
     * WorkItems with at least one BLOCKS in-edge whose src is still executing, ordered by
     * {@code (blockedSince DESC, workItemId DESC)}. The optional project filter restricts the
     * blocked side; blockers may come from any project of the Team. The page query and its
     * blocker window must observe one database snapshot.
     */
    BlockedWorkItemPage findCurrentlyBlocked(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            Optional<BlockedItemPosition> after,
            int limit);
}
