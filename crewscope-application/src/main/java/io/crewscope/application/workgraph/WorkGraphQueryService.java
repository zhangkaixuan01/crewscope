package io.crewscope.application.workgraph;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workgraph.CriticalPath;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read side of the WorkGraph projections (ADR-033): anchored adjacency, bounded blocking
 * chains, pure topological critical paths and the derived "currently blocked" aggregation.
 * Everything here is a derived query over stored edges and WorkItem status — no state is copied,
 * so the projections cannot drift from the relations they read.
 */
public final class WorkGraphQueryService {

    private final WorkItemRelationRepository relationRepository;
    private final WorkItemRelationGraphRepository graphRepository;
    private final WorkItemAccessPolicy accessPolicy;
    private final TransactionExecutor transactionExecutor;

    public WorkGraphQueryService(
            WorkItemRelationRepository relationRepository,
            WorkItemRelationGraphRepository graphRepository,
            WorkItemAccessPolicy accessPolicy,
            TransactionExecutor transactionExecutor) {
        this.relationRepository =
                Objects.requireNonNull(relationRepository, "relationRepository");
        this.graphRepository = Objects.requireNonNull(graphRepository, "graphRepository");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    /** Every relation touching one visible anchor, bounded at 5000 rows with explicit truncation. */
    public WorkItemRelationAdjacency relations(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId) {
        accessPolicy.requireVisibleWorkItem(context, organizationId, teamId, projectId, workItemId);
        List<WorkItemRelation> touching =
                relationRepository.findByEndpoint(organizationId, workItemId);
        boolean truncatedByRows = touching.size() > BlockingChain.MAX_ROWS;
        List<WorkItemRelation> bounded =
                truncatedByRows ? touching.subList(0, BlockingChain.MAX_ROWS) : touching;
        return new WorkItemRelationAdjacency(
                workItemId,
                bounded.stream()
                        .map(edge -> WorkItemRelationAdjacency.Edge.of(workItemId, edge))
                        .toList(),
                truncatedByRows);
    }

    /** Bounded upstream blocking walk of one visible anchor (depth 32 / rows 5000, explicit). */
    public BlockingChain blockingChain(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId) {
        accessPolicy.requireVisibleWorkItem(context, organizationId, teamId, projectId, workItemId);
        return graphRepository.upstreamChain(organizationId, workItemId);
    }

    /**
     * Pure topological longest blocking chain ending at one visible anchor (ADR-033: never a
     * schedule). Computed over the bounded upstream closure, so on truncation the chain is a
     * reported lower bound.
     */
    public CriticalPathResult criticalPath(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId) {
        accessPolicy.requireVisibleWorkItem(context, organizationId, teamId, projectId, workItemId);
        BlockedUpstreamClosure closure = graphRepository.upstreamClosure(organizationId, workItemId);
        List<WorkItemId> chain =
                CriticalPath.longestUpstreamChain(
                        workItemId,
                        node -> closure.upstreamOf().getOrDefault(node, List.of()));
        return new CriticalPathResult(
                chain, closure.truncatedByDepth(), closure.truncatedByRows());
    }

    /**
     * One keyset page of the Team's currently blocked WorkItems. With a project filter the
     * blocked side is restricted and that project is access-checked; without it any active
     * member of the Team reads the whole Team. Both underlying queries run in one snapshot.
     */
    public BlockedWorkItemPage currentlyBlocked(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            Optional<BlockedItemPosition> after,
            int limit) {
        projectId.ifPresentOrElse(
                filtered ->
                        accessPolicy.requireVisibleProject(
                                context, organizationId, teamId, filtered),
                () -> accessPolicy.requireVisibleTeam(context, organizationId, teamId));
        return transactionExecutor.required(
                () ->
                        graphRepository.findCurrentlyBlocked(
                                organizationId, teamId, projectId, after, limit));
    }
}
