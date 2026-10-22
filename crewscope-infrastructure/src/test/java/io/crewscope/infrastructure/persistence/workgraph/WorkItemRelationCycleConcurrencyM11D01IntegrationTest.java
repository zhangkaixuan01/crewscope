package io.crewscope.infrastructure.persistence.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workgraph.CreateWorkItemRelationCommand;
import io.crewscope.application.workgraph.WorkItemRelationCommandService;
import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.application.workgraph.WorkItemRelationRepository;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.WorkItemRelationCycleException;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedJpaTestApplication;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M11-D01 core acceptance proof on real PostgreSQL: the lock-then-probe protocol keeps two
 * racing edges from ever committing both halves of a cycle — a direct 2-cycle, the two halves of
 * a diamond that would close a longer ring, and the SPLIT_FROM ancestry equivalent — and the
 * ascending project-lock order keeps the cross-project variant deadlock-free (ADR-033).
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
class WorkItemRelationCycleConcurrencyM11D01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp OCCURRED_AT = UtcTimestamp.parse("2026-10-11T10:00:00Z");

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

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private Fixture fixture;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        fixture = seedFixture();
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void oppositeEdgesCommitExactlyOnce() throws Exception {
        WorkItemId itemA = fixture.workItem("CRW");
        WorkItemId itemB = fixture.workItem("CRW");

        Race race =
                race(
                        "opposite-forward",
                        blocks("opposite-1", itemA, itemB),
                        blocks("opposite-2", itemB, itemA));

        assertTrue(
                race.outcomes().containsAll(List.of(Outcome.COMMITTED, Outcome.CYCLE)),
                "exactly one opposite edge may commit, got " + race.outcomes());
        assertEquals(1, relationCount());
        assertEquals(1, receiptCount());
    }

    @Test
    void diamondHalfEdgesCommitExactlyOnceWithoutACycle() throws Exception {
        WorkItemId itemA = fixture.workItem("CRW");
        WorkItemId itemB = fixture.workItem("CRW");
        WorkItemId itemC = fixture.workItem("CRW");
        WorkItemId itemD = fixture.workItem("CRW");
        // seed B blocks C and D blocks A; the racers each try to close one half of the
        // diamond D -> A -> B -> C -> D — whichever lands second sees the ring through the
        // probe and must refuse, even though neither racing edge is a direct opposite
        seedEdge(itemB, itemC);
        seedEdge(itemD, itemA);

        Race race =
                race(
                        "diamond",
                        blocks("diamond-1", itemA, itemB),
                        blocks("diamond-2", itemC, itemD));

        assertTrue(
                race.outcomes().containsAll(List.of(Outcome.COMMITTED, Outcome.CYCLE)),
                "exactly one diamond half may commit, got " + race.outcomes());
        assertEquals(3, relationCount());
        // acyclicity via the product probe itself: no stored edge may have its target reach
        // its own source along the blocking closure — in a ring some edge's reverse would
        for (WorkItemId[] edge : storedBlocksEdges()) {
            assertTrue(
                    !graphRepository.blocksReaches(
                            fixture.organizationId(), edge[1], edge[0]),
                    "the surviving edges must stay acyclic: " + edge[0] + " -> " + edge[1]
                            + " closes back");
        }
    }

    @Test
    void splitAncestryRingsCommitExactlyOnce() throws Exception {
        WorkItemId itemA = fixture.workItem("CRW");
        WorkItemId itemB = fixture.workItem("CRW");
        WorkItemId itemC = fixture.workItem("CRW");
        // seed C split-from A; the racers try B split-from C and A split-from B, the two
        // halves of the ancestry ring A <- C <- B <- A
        seedSplit(itemC, itemA);

        Race race =
                race(
                        "split-ring",
                        splitFrom("split-ring-1", itemB, itemC),
                        splitFrom("split-ring-2", itemA, itemB));

        assertTrue(
                race.outcomes().containsAll(List.of(Outcome.COMMITTED, Outcome.CYCLE)),
                "exactly one ancestry half may commit, got " + race.outcomes());
        assertEquals(2, relationCount());
    }

    @Test
    void crossProjectOppositeEdgesRaceWithoutDeadlocking() throws Exception {
        WorkItemId coreItem = fixture.workItem("CRW");
        WorkItemId graphItem = fixture.workItem("WGS");

        // both racers lock the same two projects in the same ascending order, so the pair
        // cannot deadlock; the second racer still loses to the cycle probe
        Race race =
                race(
                        "cross-project",
                        blocks("cross-project-1", coreItem, graphItem),
                        blocks("cross-project-2", graphItem, coreItem));

        assertTrue(
                race.outcomes().containsAll(List.of(Outcome.COMMITTED, Outcome.CYCLE)),
                "exactly one cross-project edge may commit, got " + race.outcomes());
        assertEquals(1, relationCount());
    }

    private enum Outcome {
        COMMITTED,
        CYCLE
    }

    private record Race(List<Outcome> outcomes) {}

    /** A racer body: creates one edge under its own idempotency key once the gate opens. */
    private interface Racer {
        Outcome run() throws Exception;
    }

    private Racer blocks(String key, WorkItemId from, WorkItemId to) {
        return command(key, WorkItemRelationType.BLOCKS, from, to);
    }

    private Racer splitFrom(String key, WorkItemId from, WorkItemId to) {
        return command(key, WorkItemRelationType.SPLIT_FROM, from, to);
    }

    private Racer command(String key, WorkItemRelationType type, WorkItemId from, WorkItemId to) {
        return () -> {
            try {
                service()
                        .create(
                                fixture.context(key),
                                fixture.teamId(),
                                new CreateWorkItemRelationCommand(type, from, to));
                return Outcome.COMMITTED;
            } catch (WorkItemRelationCycleException cycle) {
                return Outcome.CYCLE;
            }
        };
    }

    private Race race(String label, Racer first, Racer second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Outcome> firstRun = executor.submit(() -> runRacer(first, ready, start));
        Future<Outcome> secondRun = executor.submit(() -> runRacer(second, ready, start));
        assertTrue(ready.await(10, TimeUnit.SECONDS), label + " racers never became ready");
        start.countDown();
        // a deadlock surfaces here as a timeout instead of hanging the suite
        Outcome firstOutcome = firstRun.get(30, TimeUnit.SECONDS);
        Outcome secondOutcome = secondRun.get(30, TimeUnit.SECONDS);
        return new Race(List.of(firstOutcome, secondOutcome));
    }

    private Outcome runRacer(Racer racer, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return racer.run();
    }

    /** Direct stored-edge insert for the pre-seeded halves (src blocks dst). */
    private void seedEdge(WorkItemId src, WorkItemId dst) {
        insertStoredEdge(src, dst, "BLOCKS");
    }

    /** Direct stored split insert (src=child spawns from dst=source). */
    private void seedSplit(WorkItemId child, WorkItemId source) {
        insertStoredEdge(child, source, "SPLIT_FROM");
    }

    private void insertStoredEdge(WorkItemId src, WorkItemId dst, String kind) {
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
                fixture.ownerId().value(),
                fixture.ownerId().value(),
                src.value());
    }

    private WorkItemRelationCommandService service() {
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
                TimeProvider.from(
                        Clock.fixed(
                                Instant.parse("2026-10-11T10:00:00.123456789Z"),
                                ZoneOffset.UTC)));
    }

    private int relationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.work_item_relation", Integer.class);
    }

    /** Every stored BLOCKS edge as a {src, dst} pair, for the acyclicity probe. */
    private List<WorkItemId[]> storedBlocksEdges() {
        return jdbcTemplate.query(
                "SELECT src_work_item_id, dst_work_item_id FROM crewscope.work_item_relation"
                        + " WHERE relation_kind = 'BLOCKS'",
                (rs, rowNum) ->
                        new WorkItemId[] {
                            WorkItemId.from(rs.getString(1)), WorkItemId.from(rs.getString(2))
                        });
    }

    private int receiptCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class);
    }

    private Fixture seedFixture() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkProjectId projectAId = WorkProjectId.generate();
        WorkProjectId projectBId = WorkProjectId.generate();
        PrincipalId ownerId = PrincipalId.generate();
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
                ) VALUES (?, ?, 'USER', 'Owner', 'ACTIVE')
                """,
                ownerId.value(),
                organizationId.value());
        UUID ownerMemberRow = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method,
                    joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'IMPORT', ?)
                """,
                ownerMemberRow,
                organizationId.value(),
                teamId.value(),
                ownerId.value(),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC));
        // a custom (non-built-in) team role carrying both work permissions
        UUID teamRoleId =
                insertRole(
                        organizationId,
                        teamId,
                        "RELATION_AUTHOR",
                        "[\"WORK_PARTICIPATE\", \"WORK_CREATE\"]");
        insertGrant(organizationId, teamId, ownerMemberRow, teamRoleId, ownerId);
        // a Team is usable only once initialization closes: owner member + default workspace
        jdbcTemplate.update(
                "UPDATE crewscope.team SET owner_member_id = ?, default_workspace_id = ?"
                        + " WHERE id = ?",
                ownerMemberRow,
                workspaceId.value(),
                teamId.value());
        return new Fixture(organizationId, teamId, workspaceId, projectAId, projectBId, ownerId);
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

    private UUID insertRole(
            OrganizationId organizationId,
            TeamId teamId,
            String roleKey,
            String permissions) {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in, permissions,
                    scope_type, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, FALSE, CAST(? AS JSONB), 'TEAM', 'ACTIVE', ?, ?)
                """,
                roleId,
                organizationId.value(),
                teamId.value(),
                roleKey,
                roleKey + " Role",
                permissions,
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC));
        return roleId;
    }

    private void insertGrant(
            OrganizationId organizationId,
            TeamId teamId,
            UUID memberRow,
            UUID roleId,
            PrincipalId grantedBy) {
        java.time.OffsetDateTime at = OCCURRED_AT.value().atOffset(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id, scope_type,
                    granted_by_principal_id, granted_at, valid_from, status, created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                organizationId.value(),
                teamId.value(),
                memberRow,
                roleId,
                grantedBy.value(),
                at,
                at,
                at,
                at);
    }

    private final class Fixture {
        private final OrganizationId organizationId;
        private final TeamId teamId;
        private final WorkspaceId workspaceId;
        private final WorkProjectId projectAId;
        private final WorkProjectId projectBId;
        private final PrincipalId ownerId;
        private final AtomicInteger itemSequence = new AtomicInteger();
        // racer threads build their own contexts concurrently, so the cache must be concurrent
        private final Map<PrincipalId, Principal> principals =
                new java.util.concurrent.ConcurrentHashMap<>();

        private Fixture(
                OrganizationId organizationId,
                TeamId teamId,
                WorkspaceId workspaceId,
                WorkProjectId projectAId,
                WorkProjectId projectBId,
                PrincipalId ownerId) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.workspaceId = workspaceId;
            this.projectAId = projectAId;
            this.projectBId = projectBId;
            this.ownerId = ownerId;
        }

        private OrganizationId organizationId() {
            return organizationId;
        }

        private TeamId teamId() {
            return teamId;
        }

        private PrincipalId ownerId() {
            return ownerId;
        }

        private TeamCommandContext context(String idempotencyKey) {
            return new TeamCommandContext(
                    new TeamAccessContext(principal(ownerId), false),
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
