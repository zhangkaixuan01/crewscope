package io.crewscope.infrastructure.persistence.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.application.workgraph.WorkItemRelationRepository;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationConflictException;
import io.crewscope.domain.workgraph.WorkItemRelationCycleException;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workgraph.WorkItemSplitSourceConflictException;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedJpaTestApplication;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CRUD round trips, ADR-033 direction normalization, recursive-CTE probes, and unique-index
 * backstop translations against migrated PostgreSQL.
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
class WorkItemRelationPersistenceIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp OCCURRED_AT = UtcTimestamp.parse("2026-10-11T08:00:00Z");

    @Autowired
    private WorkItemRelationRepository relationRepository;

    @Autowired
    private WorkItemRelationGraphRepository graphRepository;

    @Autowired
    private WorkProjectRepository projectRepository;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void roundTripsStoredEdgesAndAnswersEndpointLookups() {
        Fixture fixture = seedFixture();
        WorkItem itemA = item(fixture, fixture.workItem("CRW"));
        WorkItem itemB = item(fixture, fixture.workItem("WGS"));
        WorkItemRelation relation = relate(fixture, WorkItemRelationType.BLOCKS, itemA, itemB);

        relationRepository.create(relation);

        WorkItemRelation stored =
                relationRepository.findById(fixture.organizationId(), relation.id())
                        .orElseThrow();
        assertEquals(relation.id(), stored.id());
        assertEquals(WorkItemRelationType.BLOCKS, stored.kind());
        assertEquals(itemA.id(), stored.src().workItemId());
        assertEquals(itemB.id(), stored.dst().workItemId());
        assertEquals(fixture.projectAId(), stored.src().scope().projectId());
        assertEquals(fixture.projectBId(), stored.dst().scope().projectId());
        assertEquals(
                fixture.actorId().value(),
                jdbcTemplate.queryForObject(
                        "SELECT created_by_principal_id FROM crewscope.work_item_relation",
                        UUID.class));

        assertEquals(
                relation.id(),
                relationRepository
                        .findByStoredEndpoints(fixture.organizationId(), itemA.id(), itemB.id())
                        .orElseThrow()
                        .id());
        assertTrue(
                relationRepository
                        .findByStoredEndpoints(fixture.organizationId(), itemB.id(), itemA.id())
                        .isEmpty());
        assertEquals(
                List.of(relation.id()),
                ids(relationRepository.findByEndpoint(fixture.organizationId(), itemA.id())));
        assertEquals(
                List.of(relation.id()),
                ids(relationRepository.findByEndpoint(fixture.organizationId(), itemB.id())));

        assertTrue(graphRepository.blocksReaches(fixture.organizationId(), itemA.id(), itemB.id()));
        assertFalse(
                graphRepository.blocksReaches(fixture.organizationId(), itemB.id(), itemA.id()));
    }

    @Test
    void normalizesDependsOnSubmissionIntoTheStoredReverseEdge() {
        Fixture fixture = seedFixture();
        WorkItem itemX = item(fixture, fixture.workItem("CRW"));
        WorkItem itemY = item(fixture, fixture.workItem("CRW"));

        relationRepository.create(relate(fixture, WorkItemRelationType.DEPENDS_ON, itemX, itemY));

        WorkItemRelation stored =
                relationRepository
                        .findByStoredEndpoints(fixture.organizationId(), itemY.id(), itemX.id())
                        .orElseThrow();
        assertEquals(WorkItemRelationType.BLOCKS, stored.kind());
        assertTrue(
                relationRepository
                        .findByStoredEndpoints(fixture.organizationId(), itemX.id(), itemY.id())
                        .isEmpty());
        assertTrue(
                graphRepository.blocksReaches(fixture.organizationId(), itemY.id(), itemX.id()));
        assertFalse(
                graphRepository.blocksReaches(fixture.organizationId(), itemX.id(), itemY.id()));
    }

    @Test
    void probesSplitAncestryAlongTheForestOnly() {
        Fixture fixture = seedFixture();
        WorkItem source = item(fixture, fixture.workItem("CRW"));
        WorkItem middle = item(fixture, fixture.workItem("CRW"));
        WorkItem latest = item(fixture, fixture.workItem("CRW"));
        // SPLIT_FROM chain: latest <- middle <- source (the child sits on the src side)
        relationRepository.create(
                relate(fixture, WorkItemRelationType.SPLIT_FROM, middle, source));
        relationRepository.create(
                relate(fixture, WorkItemRelationType.SPLIT_FROM, latest, middle));
        // the ADR-legal overlap: source BLOCKS middle must not leak into the ancestry probe
        relationRepository.create(relate(fixture, WorkItemRelationType.BLOCKS, source, middle));

        assertTrue(
                graphRepository.splitAncestorOf(
                        fixture.organizationId(), latest.id(), middle.id()));
        assertTrue(
                graphRepository.splitAncestorOf(
                        fixture.organizationId(), latest.id(), source.id()));
        assertFalse(
                graphRepository.splitAncestorOf(
                        fixture.organizationId(), middle.id(), latest.id()));
        // without the kind filter this would walk the BLOCKS edge and answer true
        assertFalse(
                graphRepository.splitAncestorOf(
                        fixture.organizationId(), source.id(), middle.id()));
        assertTrue(
                graphRepository.blocksReaches(
                        fixture.organizationId(), source.id(), middle.id()));
    }

    @Test
    void walksTheUpstreamChainWithinTheDepthBound() {
        Fixture fixture = seedFixture();
        WorkItem root = item(fixture, fixture.workItem("CRW"));
        WorkItem middle = item(fixture, fixture.workItem("CRW"));
        WorkItem top = item(fixture, fixture.workItem("CRW"));
        relationRepository.create(relate(fixture, WorkItemRelationType.BLOCKS, middle, root));
        relationRepository.create(relate(fixture, WorkItemRelationType.BLOCKS, top, middle));

        BlockingChain shortChain = graphRepository.upstreamChain(fixture.organizationId(), root.id());
        assertEquals(root.id(), shortChain.root());
        assertEquals(
                List.of(new BlockingChain.Step(middle.id(), 1), new BlockingChain.Step(top.id(), 2)),
                shortChain.steps());
        assertFalse(shortChain.truncatedByDepth());
        assertFalse(shortChain.truncatedByRows());

        // a 33-node upstream chain must stop reporting at depth 32 and say so
        WorkItemId[] deep = new WorkItemId[34];
        deep[0] = root.id();
        for (int index = 1; index <= 33; index++) {
            deep[index] = fixture.workItem("CRW");
        }
        for (int index = 0; index < 33; index++) {
            insertStoredEdge(fixture, deep[index], deep[index + 1], "BLOCKS");
        }

        BlockingChain deepChain = graphRepository.upstreamChain(fixture.organizationId(), deep[33]);
        assertEquals(BlockingChain.MAX_DEPTH, deepChain.steps().size());
        // depth 1 is the direct blocker deep[32]; the walk stops reporting at depth 32, so the
        // deepest visible step is deep[1] and deep[0] is explicitly truncated away
        assertEquals(deep[32], deepChain.steps().get(0).node());
        assertEquals(1, deepChain.steps().get(0).depth());
        assertEquals(deep[1], deepChain.steps().get(BlockingChain.MAX_DEPTH - 1).node());
        assertEquals(
                BlockingChain.MAX_DEPTH,
                deepChain.steps().get(BlockingChain.MAX_DEPTH - 1).depth());
        assertTrue(deepChain.truncatedByDepth());
        assertFalse(deepChain.truncatedByRows());
    }

    @Test
    void translatesUniqueViolationsIntoDomainConflicts() {
        Fixture fixture = seedFixture();
        WorkItem itemA = item(fixture, fixture.workItem("CRW"));
        WorkItem itemB = item(fixture, fixture.workItem("CRW"));
        WorkItem itemC = item(fixture, fixture.workItem("CRW"));
        relationRepository.create(relate(fixture, WorkItemRelationType.BLOCKS, itemA, itemB));

        assertThrows(
                WorkItemRelationConflictException.class,
                () -> relationRepository.create(
                        relate(fixture, WorkItemRelationType.BLOCKS, itemA, itemB)));

        relationRepository.create(relate(fixture, WorkItemRelationType.SPLIT_FROM, itemC, itemA));
        assertThrows(
                WorkItemSplitSourceConflictException.class,
                () -> relationRepository.create(
                        relate(fixture, WorkItemRelationType.SPLIT_FROM, itemC, itemB)));

        // the undirected pair backstop reads as a 2-cycle, not a duplicate
        assertThrows(
                WorkItemRelationCycleException.class,
                () -> relationRepository.create(
                        relate(fixture, WorkItemRelationType.BLOCKS, itemB, itemA)));
    }

    @Test
    void removesEdgesByIdAndByWorkItem() {
        Fixture fixture = seedFixture();
        WorkItem itemA = item(fixture, fixture.workItem("CRW"));
        WorkItem itemB = item(fixture, fixture.workItem("WGS"));
        WorkItem itemC = item(fixture, fixture.workItem("CRW"));
        WorkItemRelation first = relate(fixture, WorkItemRelationType.BLOCKS, itemA, itemB);
        WorkItemRelation second = relate(fixture, WorkItemRelationType.SPLIT_FROM, itemA, itemC);
        WorkItemRelation untouched = relate(fixture, WorkItemRelationType.BLOCKS, itemB, itemC);
        relationRepository.create(first);
        relationRepository.create(second);
        relationRepository.create(untouched);

        assertEquals(
                second.id(),
                relationRepository
                        .removeById(fixture.organizationId(), second.id())
                        .orElseThrow()
                        .id());
        assertTrue(
                relationRepository.removeById(fixture.organizationId(), second.id()).isEmpty());

        List<WorkItemRelation> cascaded =
                relationRepository.removeByWorkItem(fixture.organizationId(), itemA.id());
        assertEquals(List.of(first.id()), ids(cascaded));
        assertEquals(1, relationCount());
        assertEquals(
                List.of(untouched.id()),
                ids(relationRepository.findByEndpoint(fixture.organizationId(), itemC.id())));
    }

    @Test
    void locksProjectsInAscendingOrderAndReportsMissingRows() {
        Fixture fixture = seedFixture();

        List<WorkProjectId> locked =
                projectRepository.lockByIds(
                        fixture.organizationId(),
                        List.of(fixture.projectBId(), fixture.projectAId()));
        // the SQL sorts with PostgreSQL's unsigned uuid order; java.util.UUID.compareTo is
        // signed, so the expected order must compare the hex rendering instead
        assertEquals(
                List.of(fixture.projectAId(), fixture.projectBId()).stream()
                        .sorted(
                                Comparator.comparing(
                                        id -> id.value().toString().replace("-", "")))
                        .toList(),
                locked);

        WorkProjectId missing = WorkProjectId.generate();
        AggregateNotFoundException notFound =
                assertThrows(
                        AggregateNotFoundException.class,
                        () -> projectRepository.lockByIds(
                                fixture.organizationId(),
                                List.of(fixture.projectAId(), missing)));
        assertTrue(notFound.getMessage().contains(missing.value().toString()));
    }

    private WorkItem item(Fixture fixture, WorkItemId id) {
        return workItemRepository.findById(fixture.organizationId(), id).orElseThrow();
    }

    private WorkItemRelation relate(
            Fixture fixture, WorkItemRelationType type, WorkItem from, WorkItem to) {
        return WorkItemRelation.relate(
                WorkItemRelationId.generate(), type, from, to, fixture.actorId(), OCCURRED_AT);
    }

    private static List<WorkItemRelationId> ids(List<WorkItemRelation> relations) {
        return relations.stream().map(WorkItemRelation::id).toList();
    }

    private void insertStoredEdge(Fixture fixture, WorkItemId src, WorkItemId dst, String kind) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_item_relation (
                    id, organization_id, team_id,
                    src_workspace_id, src_project_id, src_work_item_id,
                    dst_workspace_id, dst_project_id, dst_work_item_id,
                    relation_kind, created_by_principal_id, updated_by_principal_id
                ) SELECT ?, ?, team_id, workspace_id, project_id, ?,
                         workspace_id, project_id, ?, ?, ?, ?
                  FROM crewscope.work_item WHERE id = ?
                """,
                UUID.randomUUID(),
                fixture.organizationId().value(),
                src.value(),
                dst.value(),
                kind,
                fixture.actorId().value(),
                fixture.actorId().value(),
                src.value());
    }

    private int relationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.work_item_relation", Integer.class);
    }

    private Fixture seedFixture() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkProjectId projectAId = WorkProjectId.generate();
        WorkProjectId projectBId = WorkProjectId.generate();
        PrincipalId actorId = PrincipalId.generate();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Team', 'ACTIVE')
                """,
                teamId.value(),
                organizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace', 'ACTIVE')
                """,
                workspaceId.value(),
                organizationId.value(),
                teamId.value());
        insertProject(organizationId, teamId, workspaceId, projectAId, "CRW");
        insertProject(organizationId, teamId, workspaceId, projectBId, "WGS");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Actor', 'ACTIVE')
                """,
                actorId.value(),
                organizationId.value());
        return new Fixture(organizationId, teamId, workspaceId, projectAId, projectBId, actorId);
    }

    private void insertProject(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            String key) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                projectId.value(),
                organizationId.value(),
                teamId.value(),
                workspaceId.value(),
                key,
                key + " Project");
    }

    private final class Fixture {
        private final OrganizationId organizationId;
        private final TeamId teamId;
        private final WorkspaceId workspaceId;
        private final WorkProjectId projectAId;
        private final WorkProjectId projectBId;
        private final PrincipalId actorId;
        private final AtomicInteger itemSequence = new AtomicInteger();

        private Fixture(
                OrganizationId organizationId,
                TeamId teamId,
                WorkspaceId workspaceId,
                WorkProjectId projectAId,
                WorkProjectId projectBId,
                PrincipalId actorId) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.workspaceId = workspaceId;
            this.projectAId = projectAId;
            this.projectBId = projectBId;
            this.actorId = actorId;
        }

        private OrganizationId organizationId() {
            return organizationId;
        }

        private WorkProjectId projectAId() {
            return projectAId;
        }

        private WorkProjectId projectBId() {
            return projectBId;
        }

        private PrincipalId actorId() {
            return actorId;
        }

        /** Inserts one work_item row into the given project and returns its fresh id. */
        private WorkItemId workItem(String projectKey) {
            WorkProjectId projectId = "CRW".equals(projectKey) ? projectAId : projectBId;
            int sequence = itemSequence.incrementAndGet();
            String itemKey = projectKey + "-" + sequence;
            WorkItemId id = WorkItemId.generate();
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.work_item (
                        id, organization_id, team_id, workspace_id, project_id,
                        item_key, item_type, title, status, priority
                    ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'BACKLOG', 'MEDIUM')
                    """,
                    id.value(),
                    organizationId.value(),
                    teamId.value(),
                    workspaceId.value(),
                    projectId.value(),
                    itemKey,
                    "Task " + itemKey);
            return id;
        }
    }
}
