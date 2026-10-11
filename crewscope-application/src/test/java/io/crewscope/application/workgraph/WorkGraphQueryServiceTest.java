package io.crewscope.application.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Proves the derived WorkGraph reads: anchor-relative adjacency, critical path wiring, keyset paging. */
class WorkGraphQueryServiceTest {

    private static final UtcTimestamp OCCURRED_AT = UtcTimestamp.parse("2026-10-11T10:00:00Z");

    private final WorkItemRelationRepository relationRepository =
            mock(WorkItemRelationRepository.class);
    private final WorkItemRelationGraphRepository graphRepository =
            mock(WorkItemRelationGraphRepository.class);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final TransactionExecutor transactionExecutor = mock(TransactionExecutor.class);

    private final WorkGraphQueryService service =
            new WorkGraphQueryService(relationRepository, graphRepository, accessPolicy, transactionExecutor);

    @Test
    void relationsMapStoredEdgesIntoAnchorRelativeViews() {
        WorkItemId anchor = WorkItemId.generate();
        Tenant tenant = tenant();
        WorkItemRelation crossProjectBlocking =
                relation(anchor, projectScope(tenant, "11111111-0000-0000-0000-000000000001"),
                        WorkItemId.generate(), projectScope(tenant, "11111111-0000-0000-0000-000000000002"),
                        WorkItemRelationType.BLOCKS);
        WorkItemRelation sameProjectSplit =
                relation(anchor, projectScope(tenant, "11111111-0000-0000-0000-000000000001"),
                        WorkItemId.generate(), projectScope(tenant, "11111111-0000-0000-0000-000000000001"),
                        WorkItemRelationType.SPLIT_FROM);
        WorkItemRelation incoming =
                relation(WorkItemId.generate(), projectScope(tenant, "11111111-0000-0000-0000-000000000001"),
                        anchor, projectScope(tenant, "11111111-0000-0000-0000-000000000001"),
                        WorkItemRelationType.BLOCKS);
        when(relationRepository.findByEndpoint(any(), eq(anchor)))
                .thenReturn(List.of(crossProjectBlocking, sameProjectSplit, incoming));

        WorkItemRelationAdjacency adjacency =
                service.relations(access(), OrganizationId.generate(), TeamId.generate(),
                        WorkProjectId.generate(), anchor);

        assertEquals(anchor, adjacency.anchor());
        assertTrue(!adjacency.truncatedByRows());
        assertEquals(3, adjacency.edges().size());
        WorkItemRelationAdjacency.Edge outgoing =
                adjacency.edges().get(0);
        assertEquals(WorkItemRelationAdjacency.Direction.OUTGOING, outgoing.direction());
        assertEquals(crossProjectBlocking.dst().workItemId(), outgoing.otherWorkItemId());
        assertTrue(outgoing.crossProject());
        assertEquals(2, outgoing.views().size());
        WorkItemRelationAdjacency.Edge split = adjacency.edges().get(1);
        assertEquals(WorkItemRelationAdjacency.Direction.OUTGOING, split.direction());
        assertTrue(!split.crossProject());
        assertEquals(1, split.views().size());
        WorkItemRelationAdjacency.Edge blocking = adjacency.edges().get(2);
        assertEquals(WorkItemRelationAdjacency.Direction.INCOMING, blocking.direction());
        assertTrue(!blocking.crossProject());
        assertEquals(2, blocking.views().size());
    }

    @Test
    void relationsTruncateExplicitlyAtTheRowBound() {
        WorkItemId anchor = WorkItemId.generate();
        Tenant tenant = tenant();
        WorkItemRelation edge =
                relation(anchor, projectScope(tenant, "11111111-0000-0000-0000-000000000001"),
                        WorkItemId.generate(), projectScope(tenant, "11111111-0000-0000-0000-000000000001"),
                        WorkItemRelationType.BLOCKS);
        when(relationRepository.findByEndpoint(any(), eq(anchor)))
                .thenReturn(Collections.nCopies(BlockingChain.MAX_ROWS + 1, edge));

        WorkItemRelationAdjacency adjacency =
                service.relations(access(), OrganizationId.generate(), TeamId.generate(),
                        WorkProjectId.generate(), anchor);

        assertTrue(adjacency.truncatedByRows());
        assertEquals(BlockingChain.MAX_ROWS, adjacency.edges().size());
    }

    @Test
    void blockingChainDelegatesToTheGraphWalk() {
        WorkItemId anchor = WorkItemId.generate();
        BlockingChain chain =
                new BlockingChain(anchor, List.of(
                        new BlockingChain.Step(WorkItemId.generate(), 1)), false, false);
        when(graphRepository.upstreamChain(any(), eq(anchor))).thenReturn(chain);

        assertSame(chain, service.blockingChain(access(), OrganizationId.generate(),
                TeamId.generate(), WorkProjectId.generate(), anchor));
    }

    @Test
    void criticalPathWalksTheLongestChainOverTheClosure() {
        WorkItemId seed = WorkItemId.generate();
        WorkItemId left = WorkItemId.generate();
        WorkItemId right = WorkItemId.generate();
        WorkItemId tail = WorkItemId.generate();
        BlockedUpstreamClosure closure =
                new BlockedUpstreamClosure(
                        Map.of(seed, List.of(left, right), right, List.of(tail)), false, false);
        when(graphRepository.upstreamClosure(any(), eq(seed))).thenReturn(closure);

        CriticalPathResult result =
                service.criticalPath(access(), OrganizationId.generate(), TeamId.generate(),
                        WorkProjectId.generate(), seed);

        assertEquals(List.of(tail, right, seed), result.chain());
        assertTrue(!result.truncatedByDepth());
        assertTrue(!result.truncatedByRows());
    }

    @Test
    void criticalPathCarriesTheClosureTruncationFlags() {
        WorkItemId seed = WorkItemId.generate();
        when(graphRepository.upstreamClosure(any(), eq(seed)))
                .thenReturn(new BlockedUpstreamClosure(
                        Map.of(seed, List.of(WorkItemId.generate())), true, true));

        CriticalPathResult result =
                service.criticalPath(access(), OrganizationId.generate(), TeamId.generate(),
                        WorkProjectId.generate(), seed);

        assertTrue(result.truncatedByDepth());
        assertTrue(result.truncatedByRows());
    }

    @Test
    void currentlyBlockedChecksTheFilteredProjectInsideOneSnapshot() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkProjectId projectId = WorkProjectId.generate();
        BlockedWorkItemPage page = new BlockedWorkItemPage(List.of(), Optional.empty());
        when(transactionExecutor.required(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        when(graphRepository.findCurrentlyBlocked(eq(organizationId), eq(teamId),
                eq(Optional.of(projectId)), eq(Optional.empty()), eq(20)))
                .thenReturn(page);

        assertSame(page, service.currentlyBlocked(access(), organizationId, teamId,
                Optional.of(projectId), Optional.empty(), 20));

        verify(accessPolicy).requireVisibleProject(any(), eq(organizationId), eq(teamId), eq(projectId));
        verify(accessPolicy, never()).requireVisibleTeam(any(), any(), any());
    }

    @Test
    void currentlyBlockedRequiresOnlyVisibleTeamWithoutAProjectFilter() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        BlockedWorkItemPage page = new BlockedWorkItemPage(List.of(), Optional.empty());
        when(transactionExecutor.required(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        when(graphRepository.findCurrentlyBlocked(eq(organizationId), eq(teamId),
                eq(Optional.empty()), eq(Optional.empty()), eq(20)))
                .thenReturn(page);

        assertSame(page, service.currentlyBlocked(access(), organizationId, teamId,
                Optional.empty(), Optional.empty(), 20));

        verify(accessPolicy).requireVisibleTeam(any(), eq(organizationId), eq(teamId));
        verify(accessPolicy, never()).requireVisibleProject(any(), any(), any(), any());
        verify(accessPolicy, never()).requireVisibleWorkItem(any(), any(), any(), any(), any());
    }

    private static TeamAccessContext access() {
        PrincipalId actorId = PrincipalId.generate();
        return new TeamAccessContext(
                Principal.create(
                        actorId,
                        PrincipalScope.organization(OrganizationId.generate()),
                        PrincipalType.USER,
                        Optional.empty(),
                        "Actor " + actorId,
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        OCCURRED_AT),
                false);
    }

    /** One tenant triple shared by both endpoint scopes — edges must stay inside one org. */
    private record Tenant(OrganizationId organizationId, TeamId teamId, WorkspaceId workspaceId) {}

    private static Tenant tenant() {
        return new Tenant(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate());
    }

    private static WorkItemScope projectScope(Tenant tenant, String projectIdLiteral) {
        return new WorkItemScope(
                tenant.organizationId(),
                tenant.teamId(),
                tenant.workspaceId(),
                WorkProjectId.from(projectIdLiteral));
    }

    private static WorkItemRelation relation(
            WorkItemId src, WorkItemScope srcScope, WorkItemId dst, WorkItemScope dstScope,
            WorkItemRelationType kind) {
        return WorkItemRelation.reconstitute(
                WorkItemRelationId.generate(),
                new WorkItemRelation.RelationEndpoint(src, srcScope),
                new WorkItemRelation.RelationEndpoint(dst, dstScope),
                kind,
                AuditMetadata.createdBy(PrincipalId.generate(), OCCURRED_AT));
    }
}
