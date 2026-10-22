package io.crewscope.infrastructure.persistence.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workgraph.BlockedItemPosition;
import io.crewscope.application.workgraph.BlockedUpstreamClosure;
import io.crewscope.application.workgraph.BlockedWorkItemPage;
import io.crewscope.application.workgraph.BlockedWorkItemRow;
import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.application.workgraph.WorkItemRelationRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workgraph.CriticalPath;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedJpaTestApplication;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M11-A02 derived-query acceptance on real PostgreSQL (ADR-033): the bounded closure feeds
 * the domain CriticalPath walk and reports the 2000-deep chain as cut; the "currently blocked"
 * aggregation drops terminal blockers, flags cross-project ones and keyset-pages without
 * overlap — including the (blockedSince, workItemId) tie-break; and the new reads hold the
 * adjacency 100ms / closure-and-page 500ms p95 budgets on the D01 scale graph.
 */
@SpringBootTest(
        classes = SharedJpaTestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkGraphDerivedQueryM11A02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final int WARMUP = 10;
    private static final int SAMPLES = 20;
    private static final long ADJACENCY_BUDGET_MILLIS = 100;
    private static final long DERIVED_BUDGET_MILLIS = 500;

    @Autowired
    private WorkItemRelationRepository relationRepository;

    @Autowired
    private WorkItemRelationGraphRepository graphRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private M11D01GraphScaleFixtures.Graph graph;
    private OrganizationId organizationId;
    private TeamId teamId;

    @BeforeEach
    void seedScaleGraph() {
        graph = new M11D01GraphScaleFixtures(jdbcTemplate).seed();
        organizationId = OrganizationId.from(graph.organizationId().toString());
        teamId = TeamId.from(graph.teamId().toString());
    }

    /** The 2000-deep chain stops the closure at depth 32, says so, and still feeds the walk. */
    @Test
    void theDeepChainClosureReportsDepthTruncationAndFeedsTheCriticalPath() {
        BlockedUpstreamClosure closure = graphRepository.upstreamClosure(organizationId, graph.chainBottom());

        assertTrue(closure.truncatedByDepth(), "the 2000-deep chain must report depth truncation");
        assertFalse(closure.truncatedByRows());
        assertEquals(BlockingChain.MAX_DEPTH, closure.upstreamOf().size());
        for (int depth = 1; depth <= BlockingChain.MAX_DEPTH; depth++) {
            assertEquals(
                    List.of(M11D01GraphScaleFixtures.chainItem(depth + 1)),
                    closure.upstreamOf().get(M11D01GraphScaleFixtures.chainItem(depth)),
                    "item_" + depth + " must be blocked exactly by item_" + (depth + 1));
        }
        List<WorkItemId> chain =
                CriticalPath.longestUpstreamChain(
                        graph.chainBottom(),
                        node -> closure.upstreamOf().getOrDefault(node, List.of()));
        assertEquals(BlockingChain.MAX_DEPTH + 1, chain.size());
        assertEquals(M11D01GraphScaleFixtures.chainItem(1 + BlockingChain.MAX_DEPTH), chain.get(0));
        assertEquals(graph.chainBottom(), chain.get(chain.size() - 1));
    }

    /** A diamond keeps one pair per (blocked, blocker) and the walk returns the longer branch. */
    @Test
    void diamondClosureDeduplicatesPairsAndYieldsTheLongestBranch() {
        CustomGraph custom = seedCustomGraph();
        WorkItemId seed = custom.item("SEED", WorkItemStatus.BACKLOG);
        WorkItemId branch = custom.item("BRANCH", WorkItemStatus.BACKLOG);
        WorkItemId root = custom.item("ROOT", WorkItemStatus.BACKLOG);
        custom.blocks(root, seed, "2026-10-11T10:00:00Z");
        custom.blocks(branch, seed, "2026-10-11T10:00:00Z");
        custom.blocks(root, branch, "2026-10-11T10:00:00Z");

        BlockedUpstreamClosure closure = graphRepository.upstreamClosure(organizationId(custom), seed);

        assertFalse(closure.truncatedByDepth());
        assertFalse(closure.truncatedByRows());
        // ROOT reaches SEED directly (depth 1) and through BRANCH (depth 2); the adjacency
        // keeps each (blocked, blocker) pair exactly once
        assertEquals(List.of(branch, root), closure.upstreamOf().get(seed));
        assertEquals(List.of(root), closure.upstreamOf().get(branch));
        List<WorkItemId> chain =
                CriticalPath.longestUpstreamChain(
                        seed, node -> closure.upstreamOf().getOrDefault(node, List.of()));
        assertEquals(List.of(root, branch, seed), chain);
    }

    /** Only executing blockers count: terminal src edges drop out of rows, counts and windows. */
    @Test
    void terminalBlockersDropOutOfTheCurrentlyBlockedAggregation() {
        CustomGraph custom = seedCustomGraph();
        WorkItemId stillWorking = custom.item("X1", WorkItemStatus.IN_PROGRESS);
        WorkItemId done = custom.item("X2", WorkItemStatus.DONE);
        WorkItemId archived = custom.item("X3", WorkItemStatus.ARCHIVED);
        WorkItemId splitChild = custom.item("X4", WorkItemStatus.BACKLOG);
        WorkItemId partlyBlocked = custom.item("Y1", WorkItemStatus.BACKLOG);
        WorkItemId formerlyBlocked = custom.item("Y2", WorkItemStatus.BACKLOG);
        WorkItemId splitSource = custom.item("Y3", WorkItemStatus.BACKLOG);
        custom.blocks(stillWorking, partlyBlocked, "2026-10-11T10:00:00Z");
        custom.blocks(done, partlyBlocked, "2026-10-11T10:05:00Z");
        custom.blocks(archived, formerlyBlocked, "2026-10-11T10:10:00Z");
        custom.splitFrom(splitChild, splitSource, "2026-10-11T10:15:00Z");

        BlockedWorkItemPage page =
                graphRepository.findCurrentlyBlocked(
                        organizationId(custom), teamId(custom), Optional.empty(), Optional.empty(), 20);

        assertEquals(1, page.items().size());
        BlockedWorkItemRow row = page.items().get(0);
        assertEquals(partlyBlocked, row.workItemId());
        assertEquals(1, row.blockerCount());
        // the DONE in-edge is newer than the active one yet blockedSince stays the active MAX
        assertEquals(UtcTimestamp.parse("2026-10-11T10:00:00Z"), row.blockedSince());
        assertEquals(List.of(stillWorking), row.blockers().stream()
                .map(BlockedWorkItemRow.Blocker::workItemId).toList());
        assertFalse(row.crossesProject());
        assertTrue(page.nextPosition().isEmpty());
    }

    /** Cross-project blockers flag the row, and the project filter restricts the blocked side. */
    @Test
    void crossProjectBlockersFlagTheRowAndTheProjectFilterRestrictsTheBlockedSide() {
        CustomGraph custom = seedCustomGraph();
        WorkItemId foreignBlocker = custom.item("X1", WorkItemStatus.READY);
        WorkItemId sameProjectBlocked = custom.item("Y1", WorkItemStatus.BACKLOG);
        WorkItemId crossProjectBlocked = custom.item("WGSKY2", WorkItemStatus.BACKLOG);
        custom.blocks(foreignBlocker, sameProjectBlocked, "2026-10-11T10:00:00Z");
        custom.blocks(foreignBlocker, crossProjectBlocked, "2026-10-11T10:00:00Z");

        BlockedWorkItemPage wholeTeam =
                graphRepository.findCurrentlyBlocked(
                        organizationId(custom), teamId(custom), Optional.empty(), Optional.empty(), 20);
        assertEquals(2, wholeTeam.items().size());
        BlockedWorkItemRow crossProjectRow = wholeTeam.items().stream()
                .filter(row -> row.workItemId().equals(crossProjectBlocked))
                .findFirst()
                .orElseThrow();
        assertTrue(crossProjectRow.crossesProject());
        assertEquals(custom.projectB, crossProjectRow.projectId());
        assertEquals(custom.projectA, crossProjectRow.blockers().get(0).projectId());
        assertFalse(wholeTeam.items().stream()
                .filter(row -> row.workItemId().equals(sameProjectBlocked))
                .findFirst()
                .orElseThrow()
                .crossesProject());

        BlockedWorkItemPage projectBPage =
                graphRepository.findCurrentlyBlocked(
                        organizationId(custom),
                        teamId(custom),
                        Optional.of(custom.projectB),
                        Optional.empty(),
                        20);
        assertEquals(List.of(crossProjectBlocked), projectBPage.items().stream()
                .map(BlockedWorkItemRow::workItemId).toList());
    }

    /**
     * Keyset pages partition the aggregation: ties on blockedSince break by workItemId DESC,
     * the returned cursor restarts exclusively, and the two pages never overlap.
     */
    @Test
    void keysetPagesAreDisjointOrderedAndTieBrokenByWorkItemId() {
        CustomGraph custom = seedCustomGraph();
        WorkItemId blocker = custom.item("X1", WorkItemStatus.BACKLOG);
        WorkItemId first = custom.item("Y1", WorkItemStatus.BACKLOG);
        WorkItemId second = custom.item("Y2", WorkItemStatus.BACKLOG);
        WorkItemId third = custom.item("Y3", WorkItemStatus.BACKLOG);
        custom.blocks(blocker, first, "2026-10-11T10:00:00Z");
        custom.blocks(blocker, second, "2026-10-11T10:00:00Z");
        custom.blocks(blocker, third, "2026-10-11T09:59:00Z");

        BlockedWorkItemPage firstPage =
                graphRepository.findCurrentlyBlocked(
                        organizationId(custom), teamId(custom), Optional.empty(), Optional.empty(), 2);

        assertEquals(List.of(second, first), firstPage.items().stream()
                .map(BlockedWorkItemRow::workItemId).toList());
        BlockedItemPosition cursor = firstPage.nextPosition().orElseThrow();
        assertEquals(UtcTimestamp.parse("2026-10-11T10:00:00Z"), cursor.blockedSince());
        assertEquals(first, cursor.workItemId());

        BlockedWorkItemPage secondPage =
                graphRepository.findCurrentlyBlocked(
                        organizationId(custom), teamId(custom), Optional.empty(),
                        Optional.of(cursor), 2);

        assertEquals(List.of(third), secondPage.items().stream()
                .map(BlockedWorkItemRow::workItemId).toList());
        assertTrue(secondPage.nextPosition().isEmpty());
    }

    /** The ADR-033 budgets on the D01 scale graph: adjacency 100ms, closure and page 500ms p95. */
    @Test
    void derivedReadsHoldTheLatencyBudgetsAtScale() {
        List<WorkItemId> nodes =
                List.of(graph.chainBottom(), graph.chainMiddle(), graph.hub(), graph.leaf());
        for (int index = 0; index < WARMUP; index++) {
            WorkItemId node = nodes.get(index % nodes.size());
            relationRepository.findByEndpoint(organizationId, node);
            graphRepository.upstreamChain(organizationId, node);
            graphRepository.upstreamClosure(organizationId, node);
            graphRepository.findCurrentlyBlocked(
                    organizationId, teamId, Optional.empty(), Optional.empty(), 20);
        }
        List<Long> adjacencySamples = new ArrayList<>();
        List<Long> chainSamples = new ArrayList<>();
        List<Long> closureSamples = new ArrayList<>();
        List<Long> pageSamples = new ArrayList<>();
        for (int index = 0; index < SAMPLES; index++) {
            WorkItemId node = nodes.get(index % nodes.size());
            long startedAt = System.nanoTime();
            int adjacent = relationRepository.findByEndpoint(organizationId, node).size();
            adjacencySamples.add((System.nanoTime() - startedAt) / 1_000_000);
            assertTrue(adjacent >= 1, "node " + node + " must have at least one edge");

            startedAt = System.nanoTime();
            graphRepository.upstreamChain(organizationId, node);
            chainSamples.add((System.nanoTime() - startedAt) / 1_000_000);

            startedAt = System.nanoTime();
            graphRepository.upstreamClosure(organizationId, node);
            closureSamples.add((System.nanoTime() - startedAt) / 1_000_000);

            startedAt = System.nanoTime();
            BlockedWorkItemPage page =
                    graphRepository.findCurrentlyBlocked(
                            organizationId, teamId, Optional.empty(), Optional.empty(), 20);
            pageSamples.add((System.nanoTime() - startedAt) / 1_000_000);
            assertEquals(20, page.items().size(), "the scale graph has 4999 blocked rows");
            assertTrue(page.nextPosition().isPresent());
        }
        assertTrue(
                nearestRankP95(adjacencySamples) <= ADJACENCY_BUDGET_MILLIS,
                "adjacency lookups must hold p95 <= 100ms, measured "
                        + nearestRankP95(adjacencySamples) + "ms over " + adjacencySamples);
        assertTrue(
                nearestRankP95(chainSamples) <= DERIVED_BUDGET_MILLIS,
                "upstream walks must hold p95 <= 500ms, measured "
                        + nearestRankP95(chainSamples) + "ms over " + chainSamples);
        assertTrue(
                nearestRankP95(closureSamples) <= DERIVED_BUDGET_MILLIS,
                "upstream closures must hold p95 <= 500ms, measured "
                        + nearestRankP95(closureSamples) + "ms over " + closureSamples);
        assertTrue(
                nearestRankP95(pageSamples) <= DERIVED_BUDGET_MILLIS,
                "blocked pages must hold p95 <= 500ms, measured "
                        + nearestRankP95(pageSamples) + "ms over " + pageSamples);
    }

    private static long nearestRankP95(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compare);
        return sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
    }

    private OrganizationId organizationId(CustomGraph custom) {
        return OrganizationId.from(custom.organizationId.toString());
    }

    private TeamId teamId(CustomGraph custom) {
        return TeamId.from(custom.teamId.toString());
    }

    /**
     * Small two-project Team for the semantics tests; crafted item ids keep UUID ordering
     * deterministic wherever a tie-break assertion needs it.
     */
    private CustomGraph seedCustomGraph() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        CustomGraph custom = new CustomGraph();
        custom.insertTenant();
        return custom;
    }

    /** Fresh ids per seed; fixed suffixes keep the crafted WorkItem ids ordered. */
    private final class CustomGraph {
        private static final String ITEM_PREFIX = "66666666-0000-0000-0000-";
        private final UUID organizationId = UUID.randomUUID();
        private final UUID teamId = UUID.randomUUID();
        private final UUID workspaceId = UUID.randomUUID();
        private final UUID projectIdA = UUID.randomUUID();
        private final UUID projectIdB = UUID.randomUUID();
        private final UUID actorId = UUID.randomUUID();
        private final WorkProjectId projectA = WorkProjectId.from(projectIdA.toString());
        private final WorkProjectId projectB = WorkProjectId.from(projectIdB.toString());
        private int sequence;

        private void insertTenant() {
            jdbcTemplate.update(
                    "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                    organizationId);
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.team (id, organization_id, name, status)
                    VALUES (?, ?, 'Custom Team', 'ACTIVE')
                    """,
                    teamId,
                    organizationId);
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.workspace (
                        id, organization_id, team_id, workspace_type, name, status
                    ) VALUES (?, ?, ?, 'TEAM', 'Custom Workspace', 'ACTIVE')
                    """,
                    workspaceId,
                    organizationId,
                    teamId);
            insertProject(projectIdA, "CRW");
            insertProject(projectIdB, "WGS");
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.principal (
                        id, organization_id, principal_type, display_name, status
                    ) VALUES (?, ?, 'USER', 'Custom Actor', 'ACTIVE')
                    """,
                    actorId,
                    organizationId);
        }

        private void insertProject(UUID projectId, String key) {
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.work_project (
                        id, organization_id, team_id, workspace_id, project_key, name
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    projectId,
                    organizationId,
                    teamId,
                    workspaceId,
                    key,
                    key + " Project");
        }

        /** Inserts one WorkItem in project A unless the key starts with "WGS". */
        private WorkItemId item(String key, WorkItemStatus status) {
            WorkItemId id = WorkItemId.from(ITEM_PREFIX + M11D01GraphScaleFixtures.pad(++sequence));
            UUID projectId = key.startsWith("WGS") ? projectIdB : projectIdA;
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.work_item (
                        id, organization_id, team_id, workspace_id, project_id,
                        item_key, item_type, title, status, priority
                    ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, ?, 'MEDIUM')
                    """,
                    id.value(),
                    organizationId,
                    teamId,
                    workspaceId,
                    projectId,
                    key,
                    "Task " + key,
                    status.name());
            return id;
        }

        private void blocks(WorkItemId src, WorkItemId dst, String createdAt) {
            insertEdge(src, dst, "BLOCKS", createdAt);
        }

        private void splitFrom(WorkItemId child, WorkItemId source, String createdAt) {
            insertEdge(child, source, "SPLIT_FROM", createdAt);
        }

        private void insertEdge(WorkItemId src, WorkItemId dst, String kind, String createdAt) {
            OffsetDateTime at = UtcTimestamp.parse(createdAt).toOffsetDateTime();
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.work_item_relation (
                        id, organization_id, team_id,
                        src_workspace_id, src_project_id, src_work_item_id,
                        dst_workspace_id, dst_project_id, dst_work_item_id,
                        relation_kind, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) SELECT gen_random_uuid(), ?, ?, s.workspace_id, s.project_id, s.id,
                             d.workspace_id, d.project_id, d.id, ?, ?, ?, ?, ?
                      FROM crewscope.work_item s, crewscope.work_item d
                     WHERE s.id = ? AND d.id = ?
                    """,
                    organizationId,
                    teamId,
                    kind,
                    at,
                    actorId,
                    at,
                    actorId,
                    src.value(),
                    dst.value());
        }
    }
}
