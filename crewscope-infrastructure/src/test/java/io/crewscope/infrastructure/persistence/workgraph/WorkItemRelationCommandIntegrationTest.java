package io.crewscope.infrastructure.persistence.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.audit.CrewScopeAuditEventTypes;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workgraph.CreateWorkItemRelationCommand;
import io.crewscope.application.workgraph.RemoveWorkItemRelationCommand;
import io.crewscope.application.workgraph.WorkItemRelationCommandService;
import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.application.workgraph.WorkItemRelationRepository;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationCycleException;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workgraph.WorkItemSplitSourceConflictException;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.event.projection.AuditEventProjector;
import io.crewscope.infrastructure.event.projection.ProjectionEvent;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedJpaTestApplication;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full WorkItemRelation command protocol against migrated PostgreSQL: normalization, the
 * lock-and-probe cycle defense, two-project permission adjudication, Team boundaries, event
 * stream entries, receipts, and audit projection (ADR-033).
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
class WorkItemRelationCommandIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp OCCURRED_AT = UtcTimestamp.parse("2026-10-11T09:00:00Z");

    @Autowired
    private WorkItemRelationRepository relationRepository;

    @Autowired
    private WorkItemRelationGraphRepository graphRepository;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkProjectRepository projectRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMembershipQuery membershipQuery;

    @Autowired
    private TeamRoleRepository teamRoleRepository;

    @Autowired
    private MemberRoleRepository memberRoleRepository;

    @Autowired
    private DomainEventStore domainEventStore;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private CommandReceiptStore receiptStore;

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Fixture fixture;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        fixture = seedFixture();
    }

    @Test
    void normalizesDependsOnAndStreamsTheCreatedFact() {
        WorkItemId itemX = fixture.workItem("CRW");
        WorkItemId itemY = fixture.workItem("CRW");
        TeamCommandContext context = fixture.context(fixture.ownerId(), "create-depends-on-1");

        CommandExecution<WorkItemRelation> execution =
                service()
                        .create(
                                context,
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(
                                        WorkItemRelationType.DEPENDS_ON, itemX, itemY));

        assertEquals(WorkItemRelationType.BLOCKS, execution.result().orElseThrow().kind());
        assertEquals(itemY, execution.result().orElseThrow().src().workItemId());
        assertEquals(itemX, execution.result().orElseThrow().dst().workItemId());

        Map<String, Object> relationRow =
                jdbcTemplate.queryForMap(
                        """
                        SELECT src_work_item_id, dst_work_item_id, relation_kind
                        FROM crewscope.work_item_relation
                        """);
        assertEquals(itemY.value(), relationRow.get("src_work_item_id"));
        assertEquals(itemX.value(), relationRow.get("dst_work_item_id"));
        assertEquals("BLOCKS", relationRow.get("relation_kind"));

        Map<String, Object> event = jdbcTemplate.queryForMap(
                """
                SELECT event_type, subject_type, subject_id, aggregate_version,
                       team_id, workspace_id, actor_id, payload::TEXT AS payload
                FROM crewscope.domain_event
                """);
        assertEquals("WORK_ITEM_RELATION_CREATED", event.get("event_type"));
        assertEquals("WORK_ITEM_RELATION", event.get("subject_type"));
        assertEquals(
                execution.result().orElseThrow().id().value(), event.get("subject_id"));
        assertEquals(0L, ((Number) event.get("aggregate_version")).longValue());
        assertEquals(fixture.teamId().value(), event.get("team_id"));
        assertEquals(fixture.workspaceId().value(), event.get("workspace_id"));
        assertEquals(fixture.ownerId().value(), event.get("actor_id"));
        JsonNode payload = objectMapper.readTree((String) event.get("payload"));
        assertEquals("BLOCKS", payload.get("kind").asString());
        assertEquals(itemY.value().toString(), payload.get("srcWorkItemId").asString());
        assertEquals(itemX.value().toString(), payload.get("dstWorkItemId").asString());

        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
        assertEquals(
                "COMPLETED",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM crewscope.command_receipt", String.class));
    }

    @Test
    void rejectsEdgesThatWouldCloseACycle() {
        WorkItemId itemX = fixture.workItem("CRW");
        WorkItemId itemY = fixture.workItem("CRW");
        WorkItemId itemZ = fixture.workItem("CRW");
        WorkItemRelationCommandService service = service();
        service.create(
                fixture.context(fixture.ownerId(), "cycle-1"),
                fixture.teamId(),
                new CreateWorkItemRelationCommand(WorkItemRelationType.BLOCKS, itemX, itemY));
        service.create(
                fixture.context(fixture.ownerId(), "cycle-2"),
                fixture.teamId(),
                new CreateWorkItemRelationCommand(WorkItemRelationType.BLOCKS, itemY, itemZ));

        WorkItemRelationCycleException cycle =
                assertThrows(
                        WorkItemRelationCycleException.class,
                        () -> service.create(
                                fixture.context(fixture.ownerId(), "cycle-3"),
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(
                                        WorkItemRelationType.BLOCKS, itemZ, itemX)));

        assertEquals(
                DomainErrorCode.WORK_ITEM_RELATION_CYCLE, cycle.error().code());
        assertEquals(2, relationCount());
    }

    @Test
    void enforcesTheSplitForestRules() {
        WorkItemId itemA = fixture.workItem("CRW");
        WorkItemId itemB = fixture.workItem("CRW");
        WorkItemId itemC = fixture.workItem("CRW");
        WorkItemRelationCommandService service = service();
        // stored direction: src=child, dst=source — C spawns from A
        service.create(
                fixture.context(fixture.ownerId(), "split-1"),
                fixture.teamId(),
                new CreateWorkItemRelationCommand(
                        WorkItemRelationType.SPLIT_FROM, itemC, itemA));

        // one SPLIT_FROM source per child
        assertThrows(
                WorkItemSplitSourceConflictException.class,
                () -> service.create(
                        fixture.context(fixture.ownerId(), "split-2"),
                        fixture.teamId(),
                        new CreateWorkItemRelationCommand(
                                WorkItemRelationType.SPLIT_FROM, itemC, itemB)));

        // ancestry ring: B spawns from C over the existing chain, so B descends from
        // {C, A}; making A spawn from B would make A its own ancestor
        service.create(
                fixture.context(fixture.ownerId(), "split-3"),
                fixture.teamId(),
                new CreateWorkItemRelationCommand(
                        WorkItemRelationType.SPLIT_FROM, itemB, itemC));
        WorkItemRelationCycleException ring =
                assertThrows(
                        WorkItemRelationCycleException.class,
                        () -> service.create(
                                fixture.context(fixture.ownerId(), "split-4"),
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(
                                        WorkItemRelationType.SPLIT_FROM, itemA, itemB)));
        assertEquals(
                DomainErrorCode.WORK_ITEM_RELATION_CYCLE, ring.error().code());
        assertEquals(2, relationCount());
    }

    @Test
    void requiresParticipationOnBothEndpointProjects() {
        WorkItemId coreItem = fixture.workItem("CRW");
        WorkItemId graphItem = fixture.workItem("WGS");
        CreateWorkItemRelationCommand command =
                new CreateWorkItemRelationCommand(
                        WorkItemRelationType.BLOCKS, coreItem, graphItem);

        // the project member holds WORK_PARTICIPATE for CRW only — the WGS side refuses
        assertThrows(
                PolicyDeniedException.class,
                () -> service()
                        .create(
                                fixture.context(fixture.projectMemberId(), "perm-1"),
                                fixture.teamId(),
                                command));

        // once the second project grant exists the cross-project edge goes through
        fixture.grantProjectRole(fixture.projectMemberId(), fixture.projectBId());
        CommandExecution<WorkItemRelation> execution =
                service()
                        .create(
                                fixture.context(fixture.projectMemberId(), "perm-2"),
                                fixture.teamId(),
                                command);
        assertEquals(WorkItemRelationType.BLOCKS, execution.result().orElseThrow().kind());
        assertEquals(1, relationCount());
    }

    @Test
    void refusesCrossTeamEdgesExplicitly() {
        WorkItemId ownItem = fixture.workItem("CRW");
        WorkItemId foreignItem = fixture.foreignWorkItem();

        PolicyDeniedException crossTeam =
                assertThrows(
                        PolicyDeniedException.class,
                        () -> service()
                                .create(
                                        fixture.context(fixture.ownerId(), "cross-team-1"),
                                        fixture.teamId(),
                                        new CreateWorkItemRelationCommand(
                                                WorkItemRelationType.BLOCKS,
                                                ownItem,
                                                foreignItem)));

        assertTrue(crossTeam.getMessage().contains("across Teams"));
        assertEquals(0, relationCount());
    }

    @Test
    void rejectsSelfReferenceAsValidation() {
        WorkItemId item = fixture.workItem("CRW");

        assertThrows(
                DomainValidationException.class,
                () -> service()
                        .create(
                                fixture.context(fixture.ownerId(), "self-1"),
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(
                                        WorkItemRelationType.BLOCKS, item, item)));
        assertEquals(0, relationCount());
    }

    /** Archived endpoints stop accepting new edges (M11-A02), in both directions. */
    @Test
    void rejectsNewEdgesTouchingArchivedEndpoints() {
        WorkItemId active = fixture.workItem("CRW");
        WorkItemId archivedSrc = fixture.workItem("CRW");
        WorkItemId archivedDst = fixture.workItem("CRW");
        jdbcTemplate.update(
                "UPDATE crewscope.work_item SET status = 'ARCHIVED' WHERE id IN (?, ?)",
                archivedSrc.value(), archivedDst.value());

        DomainValidationException srcSide =
                assertThrows(
                        DomainValidationException.class,
                        () -> service()
                                .create(
                                        fixture.context(fixture.ownerId(), "archived-src-1"),
                                        fixture.teamId(),
                                        new CreateWorkItemRelationCommand(
                                                WorkItemRelationType.BLOCKS, archivedSrc, active)));
        assertEquals(
                "workItemRelation.endpoints", srcSide.error().details().get("field"));
        DomainValidationException dstSide =
                assertThrows(
                        DomainValidationException.class,
                        () -> service()
                                .create(
                                        fixture.context(fixture.ownerId(), "archived-dst-1"),
                                        fixture.teamId(),
                                        new CreateWorkItemRelationCommand(
                                                WorkItemRelationType.BLOCKS, active, archivedDst)));
        assertEquals(
                "workItemRelation.endpoints", dstSide.error().details().get("field"));
        assertEquals(0, relationCount());
    }

    /** The guard applies to create only: an existing edge stays visible and removable. */
    @Test
    void anArchivedEndpointsExistingEdgeMayStillBeRemoved() {
        WorkItemId itemA = fixture.workItem("CRW");
        WorkItemId itemB = fixture.workItem("CRW");
        WorkItemRelationCommandService service = service();
        WorkItemRelation created =
                service
                        .create(
                                fixture.context(fixture.ownerId(), "archive-remove-1"),
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(
                                        WorkItemRelationType.BLOCKS, itemA, itemB))
                        .result()
                        .orElseThrow();
        jdbcTemplate.update(
                "UPDATE crewscope.work_item SET status = 'ARCHIVED' WHERE id = ?",
                itemA.value());

        CommandExecution<WorkItemRelation> removal =
                service.remove(
                        fixture.context(fixture.ownerId(), "archive-remove-2"),
                        fixture.teamId(),
                        new RemoveWorkItemRelationCommand(created.id()));

        assertEquals(created.id(), removal.result().orElseThrow().id());
        assertEquals(0, relationCount());
    }

    @Test
    void replaysTheSameCreateCommandIdempotently() {
        WorkItemId itemA = fixture.workItem("CRW");
        WorkItemId itemB = fixture.workItem("CRW");
        TeamCommandContext context = fixture.context(fixture.ownerId(), "replay-1");
        CreateWorkItemRelationCommand command =
                new CreateWorkItemRelationCommand(WorkItemRelationType.BLOCKS, itemA, itemB);

        CommandExecution<WorkItemRelation> first = service().create(context, fixture.teamId(), command);
        CommandExecution<WorkItemRelation> replay = service().create(context, fixture.teamId(), command);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertTrue(replay.result().isEmpty());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(1, relationCount());
        assertEquals(1, eventCount());
    }

    @Test
    void removesOneEdgeAndStreamsTheRemovedFact() {
        WorkItemId itemA = fixture.workItem("CRW");
        WorkItemId itemB = fixture.workItem("CRW");
        WorkItemRelationCommandService service = service();
        WorkItemRelation created =
                service
                        .create(
                                fixture.context(fixture.ownerId(), "remove-1"),
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(
                                        WorkItemRelationType.BLOCKS, itemA, itemB))
                        .result()
                        .orElseThrow();

        CommandExecution<WorkItemRelation> removal =
                service.remove(
                        fixture.context(fixture.ownerId(), "remove-2"),
                        fixture.teamId(),
                        new RemoveWorkItemRelationCommand(created.id()));

        assertEquals(created.id(), removal.result().orElseThrow().id());
        assertEquals(0, relationCount());
        // both events share the fixed clock, so order by type — CREATED sorts before REMOVED
        List<String> eventTypes =
                jdbcTemplate.queryForList(
                        "SELECT event_type FROM crewscope.domain_event ORDER BY event_type",
                        String.class);
        assertEquals(List.of("WORK_ITEM_RELATION_CREATED", "WORK_ITEM_RELATION_REMOVED"), eventTypes);
    }

    @Test
    void cascadesAllTouchingEdgesWithOneRemovalEventPerEdge() {
        WorkItemId hub = fixture.workItem("CRW");
        WorkItemId spokeOne = fixture.workItem("CRW");
        WorkItemId spokeTwo = fixture.workItem("WGS");
        WorkItemId unrelatedOne = fixture.workItem("CRW");
        WorkItemId unrelatedTwo = fixture.workItem("CRW");
        WorkItemRelationCommandService service = service();
        for (int index = 0; index < 3; index++) {
            WorkItemId target = index == 0 ? spokeOne : index == 1 ? spokeTwo : unrelatedOne;
            WorkItemId source = index == 2 ? unrelatedTwo : hub;
            service.create(
                    fixture.context(fixture.ownerId(), "cascade-" + index),
                    fixture.teamId(),
                    // hub blocks spokeOne, spokeTwo; unrelatedTwo blocks unrelatedOne
                    new CreateWorkItemRelationCommand(
                            WorkItemRelationType.BLOCKS, source, target));
        }
        assertEquals(3, relationCount());

        List<WorkItemRelation> removed =
                service.removeAllForWorkItem(
                        fixture.context(fixture.ownerId(), "cascade-delete"), fixture.teamId(), hub);

        assertEquals(2, removed.size());
        assertEquals(1, relationCount());
        // three Created events plus one Removed per cascaded edge
        assertEquals(
                5,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.domain_event", Integer.class));
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.domain_event"
                                + " WHERE event_type = 'WORK_ITEM_RELATION_REMOVED'",
                        Integer.class));
        // a cascade is an effect of a future delete command, never a command of its own
        assertEquals(
                3,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
        // so the cascaded Removed envelopes carry no idempotency key — NULL stays free for
        // system facts that do not originate from an idempotent command
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.domain_event"
                                + " WHERE event_type = 'WORK_ITEM_RELATION_REMOVED'"
                                + " AND idempotency_key IS NULL",
                        Integer.class));
    }

    @Test
    void projectsCreatedAndRemovedEventsIntoAuditRows() throws Exception {
        // fixed letter-bearing ids: the projected summary carries these as values, and a random
        // UUID occasionally looks like a phone number to the AuditSummarySchema heuristic
        WorkItemId itemA =
                fixture.workItem("CRW", "aaaaaaa1-bbbb-4ccc-8ddd-eeeeeeeef001");
        WorkItemId itemB =
                fixture.workItem("CRW", "bbbbbbb2-cccc-4ddd-8eee-fffffffff002");
        WorkItemRelationCommandService service = service();
        WorkItemRelation created =
                service
                        .create(
                                fixture.context(fixture.ownerId(), "audit-1"),
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(
                                        WorkItemRelationType.BLOCKS, itemA, itemB))
                        .result()
                        .orElseThrow();
        service.remove(
                fixture.context(fixture.ownerId(), "audit-2"),
                fixture.teamId(),
                new RemoveWorkItemRelationCommand(created.id()));

        AuditEventProjector projector =
                new AuditEventProjector(
                        jdbcTemplate, objectMapper, CrewScopeAuditEventTypes.reviewedRegistry());
        for (ProjectionEvent event : loadRelationEvents()) {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(ignored -> projector.project(event));
        }

        List<Map<String, Object>> auditRows =
                jdbcTemplate.queryForList(
                        """
                        SELECT event_type, event_category, outcome, payload::TEXT AS payload
                        FROM crewscope.audit_event
                        WHERE subject_type = 'WORK_ITEM_RELATION'
                        ORDER BY event_type
                        """);
        assertEquals(2, auditRows.size());
        assertEquals("WORK_ITEM_RELATION_CREATED", auditRows.get(0).get("event_type"));
        assertEquals("WORK_ITEM_RELATION_REMOVED", auditRows.get(1).get("event_type"));
        for (Map<String, Object> row : auditRows) {
            assertEquals("WORK", row.get("event_category"));
            assertEquals("SUCCEEDED", row.get("outcome"));
            JsonNode payload = objectMapper.readTree((String) row.get("payload"));
            assertEquals("BLOCKS", payload.get("kind").asString());
            assertEquals(itemA.value().toString(), payload.get("srcWorkItemId").asString());
            assertEquals(itemB.value().toString(), payload.get("dstWorkItemId").asString());
        }
    }

    private WorkItemRelationCommandService service() {
        TimeProvider fixedTime =
                TimeProvider.from(
                        Clock.fixed(
                                Instant.parse("2026-10-11T09:00:00.123456789Z"), ZoneOffset.UTC));
        return new WorkItemRelationCommandService(
                relationRepository,
                graphRepository,
                workItemRepository,
                projectRepository,
                teamRepository,
                membershipQuery,
                teamRoleRepository,
                memberRoleRepository,
                domainEventStore,
                outboxRepository,
                receiptStore,
                transactionExecutor,
                fixedTime);
    }

    private List<ProjectionEvent> loadRelationEvents() {
        return jdbcTemplate.query(
                """
                SELECT event_id, event_type, schema_version, organization_id, team_id,
                       workspace_id, subject_id, actor_type, actor_id, correlation_id,
                       causation_id, occurred_at, payload::TEXT AS payload
                FROM crewscope.domain_event
                WHERE subject_type = 'WORK_ITEM_RELATION'
                """,
                (rs, rowNum) ->
                        new ProjectionEvent(
                                rs.getObject("event_id", UUID.class),
                                rs.getString("event_type"),
                                rs.getString("schema_version"),
                                rs.getObject("organization_id", UUID.class),
                                Optional.ofNullable(rs.getObject("team_id", UUID.class)),
                                Optional.ofNullable(rs.getObject("workspace_id", UUID.class)),
                                "WORK_ITEM_RELATION",
                                rs.getObject("subject_id", UUID.class),
                                0,
                                io.crewscope.domain.shared.event.EventActorType.valueOf(
                                        rs.getString("actor_type")),
                                Optional.ofNullable(rs.getObject("actor_id", UUID.class)),
                                rs.getObject("correlation_id", UUID.class),
                                Optional.ofNullable(rs.getObject("causation_id", UUID.class)),
                                UtcTimestamp.from(rs.getTimestamp("occurred_at").toInstant()),
                                rs.getString("payload")));
    }

    private int relationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.work_item_relation", Integer.class);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.domain_event", Integer.class);
    }

    private Fixture seedFixture() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkProjectId projectAId = WorkProjectId.generate();
        WorkProjectId projectBId = WorkProjectId.generate();
        PrincipalId ownerId = PrincipalId.generate();
        PrincipalId projectMemberId = PrincipalId.generate();
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
        insertPrincipal(organizationId, ownerId, "Owner");
        insertPrincipal(organizationId, projectMemberId, "Project Member");
        UUID ownerMemberRow = insertMember(organizationId, teamId, ownerId, "ACTIVE");
        UUID projectMemberRow = insertMember(organizationId, teamId, projectMemberId, "ACTIVE");
        // custom (non-built-in) roles: TeamRole.reconstitute accepts any non-reserved key, and
        // only built-in roles are pinned to TEAM scope and product-owned permissions
        UUID teamRoleId =
                insertRole(organizationId, teamId, "RELATION_AUTHOR", "TEAM", null,
                        "[\"WORK_PARTICIPATE\", \"WORK_CREATE\"]");
        insertGrant(organizationId, teamId, ownerMemberRow, teamRoleId, "TEAM", null, ownerId);
        // a Team is usable only once initialization closes: owner member + default workspace
        jdbcTemplate.update(
                "UPDATE crewscope.team SET owner_member_id = ?, default_workspace_id = ?"
                        + " WHERE id = ?",
                ownerMemberRow,
                workspaceId.value(),
                teamId.value());
        // the project member participates in CRW only, via a WORK_PROJECT-scoped grant
        UUID coreProjectRoleId =
                insertRole(organizationId, teamId, "CRW_PARTICIPANT", "WORK_PROJECT", projectAId,
                        "[\"WORK_PARTICIPATE\"]");
        insertGrant(
                organizationId, teamId, projectMemberRow, coreProjectRoleId, "WORK_PROJECT",
                projectAId, ownerId);
        // a foreign Team with one item, for the cross-Team boundary
        TeamId foreignTeamId = TeamId.generate();
        WorkspaceId foreignWorkspaceId = WorkspaceId.generate();
        WorkProjectId foreignProjectId = WorkProjectId.generate();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Foreign Team', 'ACTIVE')
                """,
                foreignTeamId.value(),
                organizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Foreign Workspace', 'ACTIVE')
                """,
                foreignWorkspaceId.value(),
                organizationId.value(),
                foreignTeamId.value());
        insertProject(organizationId, foreignTeamId, foreignWorkspaceId, foreignProjectId, "FRN");
        return new Fixture(
                organizationId,
                teamId,
                workspaceId,
                projectAId,
                projectBId,
                ownerId,
                projectMemberId);
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

    private void insertPrincipal(
            OrganizationId organizationId, PrincipalId principalId, String displayName) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                principalId.value(),
                organizationId.value(),
                displayName);
    }

    private UUID insertMember(
            OrganizationId organizationId, TeamId teamId, PrincipalId user, String status) {
        UUID memberId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method,
                    joined_at
                ) VALUES (?, ?, ?, ?, ?, 'IMPORT', ?)
                """,
                memberId,
                organizationId.value(),
                teamId.value(),
                user.value(),
                status,
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC));
        return memberId;
    }

    private UUID insertRole(
            OrganizationId organizationId,
            TeamId teamId,
            String roleKey,
            String scopeType,
            WorkProjectId scopeProject,
            String permissions) {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in, permissions,
                    scope_type, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, FALSE, CAST(? AS JSONB), ?, 'ACTIVE', ?, ?)
                """,
                roleId,
                organizationId.value(),
                teamId.value(),
                roleKey,
                roleKey + " Role",
                permissions,
                scopeType,
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC));
        return roleId;
    }

    private void insertGrant(
            OrganizationId organizationId,
            TeamId teamId,
            UUID memberRow,
            UUID roleId,
            String scopeType,
            WorkProjectId scopeProject,
            PrincipalId grantedBy) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id, scope_type,
                    scope_id, granted_by_principal_id, granted_at, valid_from,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                organizationId.value(),
                teamId.value(),
                memberRow,
                roleId,
                scopeType,
                scopeProject == null ? null : scopeProject.value(),
                grantedBy.value(),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC));
    }

    private UUID memberRowOf(OrganizationId organizationId, TeamId teamId, PrincipalId user) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM crewscope.team_member WHERE organization_id = ? AND team_id = ?"
                        + " AND user_principal_id = ?",
                UUID.class,
                organizationId.value(),
                teamId.value(),
                user.value());
    }

    private final class Fixture {
        private final OrganizationId organizationId;
        private final TeamId teamId;
        private final WorkspaceId workspaceId;
        private final WorkProjectId projectAId;
        private final WorkProjectId projectBId;
        private final PrincipalId ownerId;
        private final PrincipalId projectMemberId;
        private final AtomicInteger itemSequence = new AtomicInteger();
        private final Map<PrincipalId, Principal> principals = new java.util.HashMap<>();

        private Fixture(
                OrganizationId organizationId,
                TeamId teamId,
                WorkspaceId workspaceId,
                WorkProjectId projectAId,
                WorkProjectId projectBId,
                PrincipalId ownerId,
                PrincipalId projectMemberId) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.workspaceId = workspaceId;
            this.projectAId = projectAId;
            this.projectBId = projectBId;
            this.ownerId = ownerId;
            this.projectMemberId = projectMemberId;
        }

        private TeamId teamId() {
            return teamId;
        }

        private WorkspaceId workspaceId() {
            return workspaceId;
        }

        private WorkProjectId projectBId() {
            return projectBId;
        }

        private PrincipalId ownerId() {
            return ownerId;
        }

        private PrincipalId projectMemberId() {
            return projectMemberId;
        }

        private TeamCommandContext context(PrincipalId actor, String idempotencyKey) {
            return new TeamCommandContext(
                    new TeamAccessContext(principal(actor), false),
                    IdempotencyKey.from(idempotencyKey),
                    UUID.randomUUID(),
                    Optional.empty());
        }

        private Principal principal(PrincipalId principalId) {
            return principals.computeIfAbsent(
                    principalId,
                    id ->
                            Principal.create(
                                    id,
                                    PrincipalScope.organization(organizationId),
                                    PrincipalType.USER,
                                    Optional.empty(),
                                    "Actor " + id,
                                    Optional.empty(),
                                    PrincipalVisibility.ORGANIZATION,
                                    OCCURRED_AT));
        }

        private WorkItemId workItem(String projectKey) {
            return workItem(projectKey, WorkItemId.generate().value().toString());
        }

        /**
         * Fixed ids keep the audit-projection case deterministic: a random UUID whose segment
         * is all digits trips the AuditSummarySchema phone heuristic and would fail projection
         * roughly once per hundred runs. Every fixed id below carries a letter in each segment.
         */
        private WorkItemId workItem(String projectKey, String fixedId) {
            WorkProjectId projectId = "CRW".equals(projectKey) ? projectAId : projectBId;
            String itemKey = projectKey + "-" + itemSequence.incrementAndGet();
            WorkItemId id = WorkItemId.from(fixedId);
            insertWorkItemRow(organizationId, teamId, workspaceId, projectId, id, itemKey);
            return id;
        }

        private WorkItemId foreignWorkItem() {
            WorkItemId id = WorkItemId.generate();
            insertForeignWorkItemRow(organizationId, id, "FRN-1");
            return id;
        }

        private void grantProjectRole(PrincipalId member, WorkProjectId projectId) {
            UUID memberRow = memberRowOf(organizationId, teamId, member);
            UUID roleId =
                    insertRole(
                            organizationId, teamId, "WGS_PARTICIPANT", "WORK_PROJECT", projectId,
                            "[\"WORK_PARTICIPATE\"]");
            insertGrant(
                    organizationId, teamId, memberRow, roleId, "WORK_PROJECT", projectId,
                    ownerId);
        }
    }

    private void insertWorkItemRow(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            WorkItemId id,
            String itemKey) {
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
    }

    private void insertForeignWorkItemRow(
            OrganizationId organizationId, WorkItemId id, String itemKey) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) SELECT ?, ?, team_id, workspace_id, id, ?, 'TASK', ?, 'BACKLOG', 'MEDIUM'
                  FROM crewscope.work_project WHERE project_key = 'FRN'
                """,
                id.value(),
                organizationId.value(),
                itemKey,
                "Foreign Task");
    }
}
