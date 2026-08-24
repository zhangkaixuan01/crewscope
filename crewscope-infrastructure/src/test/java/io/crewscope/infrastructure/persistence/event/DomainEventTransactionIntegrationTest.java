package io.crewscope.infrastructure.persistence.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.CreateWorkItemCommand;
import io.crewscope.application.workitem.WorkItemApplicationService;
import io.crewscope.application.workitem.WorkItemCommandContext;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.event.WorkItemCreated;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntity;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.ObjectMapper;

/** Proves aggregate, DomainEvent and Outbox atomicity against migrated PostgreSQL. */
@SpringBootTest(
        classes = DomainEventTransactionIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class DomainEventTransactionIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp OCCURRED_AT =
            UtcTimestamp.parse("2026-08-06T14:30:00.123456Z");
    private static final UUID CORRELATION_ID =
            UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d401");
    private static final UUID CAUSATION_ID =
            UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d402");

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private DomainEventStore domainEventStore;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private CommandReceiptStore commandReceiptStore;

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void commitsWorkItemCanonicalDomainEventAndPendingOutboxTogether() {
        Fixture fixture = seedFixture("success");
        WorkItemApplicationService service = service(outboxRepository);

        CommandExecution<WorkItem> execution = service.create(
                fixture.context(Optional.of("create-atomic-1")),
                new CreateWorkItemCommand(
                        fixture.projectId(), "CRW-100", "Atomic transaction baseline"));
        WorkItem created = execution.result().orElseThrow();

        Map<String, Object> event = jdbcTemplate.queryForMap(
                """
                SELECT event_id, event_type, schema_version,
                       organization_id, team_id, workspace_id,
                       subject_type, subject_id, aggregate_version,
                       actor_type, actor_id,
                       correlation_id, causation_id, idempotency_key,
                       occurred_at, payload::TEXT AS payload
                FROM crewscope.domain_event
                """);
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                """
                SELECT domain_event_id, topic, partition_key, delivery_status,
                       retry_count, next_delivery_at, delivered_at, version,
                       created_at, updated_at
                FROM crewscope.outbox_event
                """);

        assertEquals(1, rowCount("work_item"));
        assertEquals(1, rowCount("domain_event"));
        assertEquals(1, rowCount("outbox_event"));
        assertEquals(1, rowCount("command_receipt"));
        assertEquals("WORK_ITEM_CREATED", event.get("event_type"));
        assertEquals("1", event.get("schema_version"));
        assertEquals(fixture.organizationId().value(), event.get("organization_id"));
        assertEquals(fixture.teamId().value(), event.get("team_id"));
        assertEquals(fixture.workspaceId().value(), event.get("workspace_id"));
        assertEquals("WORK_ITEM", event.get("subject_type"));
        assertEquals(created.id().value(), event.get("subject_id"));
        assertEquals(created.version(), ((Number) event.get("aggregate_version")).longValue());
        assertEquals("USER", event.get("actor_type"));
        assertEquals(fixture.actorId().value(), event.get("actor_id"));
        assertEquals(CORRELATION_ID, event.get("correlation_id"));
        assertEquals(CAUSATION_ID, event.get("causation_id"));
        assertEquals("create-atomic-1", event.get("idempotency_key"));
        var payload = objectMapper.readTree((String) event.get("payload"));
        assertEquals(fixture.projectId().toString(), payload.get("projectId").asText());
        assertEquals("CRW-100", payload.get("itemKey").asText());
        assertEquals("Atomic transaction baseline", payload.get("title").asText());
        assertEquals("BACKLOG", payload.get("status").asText());
        // The pre-M1 compatibility service deliberately does not enter the production Owner flow.
        assertTrue(payload.get("initialOwnerAssignmentId").isNull());
        assertTrue(payload.get("initialOwnerPrincipalId").isNull());
        assertEquals(event.get("event_id"), outbox.get("domain_event_id"));
        assertEquals(PendingOutboxEvent.DOMAIN_EVENTS_TOPIC, outbox.get("topic"));
        assertEquals(
                "%s:WORK_ITEM:%s".formatted(fixture.organizationId(), created.id()),
                outbox.get("partition_key"));
        assertEquals("PENDING", outbox.get("delivery_status"));
        assertEquals(0, ((Number) outbox.get("retry_count")).intValue());
        assertEquals(0, ((Number) outbox.get("version")).longValue());
        assertNull(outbox.get("next_delivery_at"));
        assertNull(outbox.get("delivered_at"));
        assertEquals(event.get("occurred_at"), outbox.get("created_at"));
        assertEquals(outbox.get("created_at"), outbox.get("updated_at"));
        Map<String, Object> receipt = jdbcTemplate.queryForMap(
                """
                SELECT command_id, command_type, request_hash, domain_event_id,
                       committed_version, correlation_id, status
                FROM crewscope.command_receipt
                """);
        assertEquals(execution.receipt().commandId(), receipt.get("command_id"));
        assertEquals(execution.receipt().domainEventId(), receipt.get("domain_event_id"));
        assertEquals(
                execution.receipt().committedVersion(),
                ((Number) receipt.get("committed_version")).longValue());
        assertEquals(execution.receipt().correlationId(), receipt.get("correlation_id"));
        assertEquals("COMPLETED", receipt.get("status"));
        assertEquals(
                Optional.of(execution.receipt()),
                commandReceiptStore.findCompleted(
                        fixture.organizationId(),
                        IdempotencyKey.from("create-atomic-1"),
                        (String) receipt.get("command_type"),
                        new CommandRequestHash((String) receipt.get("request_hash"))));
    }

    @Test
    void rollsBackWorkItemAndDomainEventWhenOutboxCreationFails() {
        Fixture fixture = seedFixture("rollback");
        OutboxRepository failingOutbox = ignored -> {
            throw new SimulatedOutboxFailure();
        };

        assertThrows(
                SimulatedOutboxFailure.class,
                () -> service(failingOutbox)
                        .create(
                                fixture.context(Optional.of("create-atomic-rollback")),
                                new CreateWorkItemCommand(
                                        fixture.projectId(), "CRW-101", "Must roll back")));

        assertEquals(0, rowCount("work_item"));
        assertEquals(0, rowCount("domain_event"));
        assertEquals(0, rowCount("outbox_event"));
        assertEquals(0, rowCount("command_receipt"));
    }

    @Test
    void replaysTheSameCommandWithoutDuplicatingAnyCommittedFacts() {
        Fixture fixture = seedFixture("replay");
        WorkItemApplicationService service = service(outboxRepository);
        WorkItemCommandContext context = fixture.context(Optional.of("create-replay-1"));
        CreateWorkItemCommand command = new CreateWorkItemCommand(
                fixture.projectId(), "CRW-103", "Replay transaction baseline");

        CommandExecution<WorkItem> first = service.create(context, command);
        CommandExecution<WorkItem> replay = service.create(context, command);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertTrue(replay.result().isEmpty());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(1, rowCount("work_item"));
        assertEquals(1, rowCount("domain_event"));
        assertEquals(1, rowCount("outbox_event"));
        assertEquals(1, rowCount("command_receipt"));
    }

    @Test
    void rejectsAChangedRequestUsingTheSameIdempotencyKey() {
        Fixture fixture = seedFixture("conflict");
        WorkItemApplicationService service = service(outboxRepository);
        WorkItemCommandContext context = fixture.context(Optional.of("create-conflict-1"));
        service.create(
                context,
                new CreateWorkItemCommand(fixture.projectId(), "CRW-104", "Original"));

        assertThrows(
                IdempotencyConflictException.class,
                () -> service.create(
                        context,
                        new CreateWorkItemCommand(
                                fixture.projectId(), "CRW-104", "Changed")));
        assertEquals(1, rowCount("work_item"));
        assertEquals(1, rowCount("domain_event"));
        assertEquals(1, rowCount("outbox_event"));
        assertEquals(1, rowCount("command_receipt"));
    }

    @Test
    void serializesConcurrentIdenticalCommandsThroughTheReceiptReservation() throws Exception {
        Fixture fixture = seedFixture("concurrent");
        WorkItemApplicationService service = service(outboxRepository);
        WorkItemCommandContext context = fixture.context(Optional.of("create-concurrent-1"));
        CreateWorkItemCommand command = new CreateWorkItemCommand(
                fixture.projectId(), "CRW-105", "Concurrent idempotency baseline");
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CommandExecution<WorkItem>> first = executor.submit(() -> {
                start.await();
                return service.create(context, command);
            });
            Future<CommandExecution<WorkItem>> second = executor.submit(() -> {
                start.await();
                return service.create(context, command);
            });
            start.countDown();

            CommandExecution<WorkItem> firstResult = first.get();
            CommandExecution<WorkItem> secondResult = second.get();
            assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(CommandExecution::replayed)
                    .count());
            assertEquals(firstResult.receipt(), secondResult.receipt());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, rowCount("work_item"));
        assertEquals(1, rowCount("domain_event"));
        assertEquals(1, rowCount("outbox_event"));
        assertEquals(1, rowCount("command_receipt"));
    }

    @Test
    void rejectsEventAndOutboxWritesWithoutAnExistingTransaction() {
        Fixture fixture = seedFixture("mandatory");
        WorkItemId workItemId = WorkItemId.generate();
        DomainEventEnvelope<WorkItemCreated> event = new DomainEventEnvelope<>(
                UUID.randomUUID(),
                EventType.from("WORK_ITEM_CREATED"),
                SchemaVersion.V1,
                fixture.organizationId(),
                Optional.of(fixture.teamId()),
                Optional.of(fixture.workspaceId()),
                AggregateReference.of("WORK_ITEM", workItemId),
                0,
                EventActor.principal(EventActorType.USER, fixture.actorId()),
                CORRELATION_ID,
                Optional.of(CAUSATION_ID),
                Optional.empty(),
                OCCURRED_AT,
                new WorkItemCreated(
                        fixture.projectId().value(),
                        "CRW-102",
                        "Mandatory",
                        WorkItemStatus.BACKLOG,
                        Optional.empty(),
                        Optional.empty()));

        assertThrows(IllegalTransactionStateException.class, () -> domainEventStore.append(event));
        assertThrows(
                IllegalTransactionStateException.class,
                () -> outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event)));
    }

    private WorkItemApplicationService service(OutboxRepository outbox) {
        TimeProvider fixedTime = TimeProvider.from(
                Clock.fixed(Instant.parse("2026-08-06T14:30:00.123456789Z"), ZoneOffset.UTC));
        return new WorkItemApplicationService(
                workItemRepository,
                domainEventStore,
                outbox,
                commandReceiptStore,
                transactionExecutor,
                fixedTime);
    }

    private Fixture seedFixture(String suffix) {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkProjectId projectId = WorkProjectId.generate();
        PrincipalId actorId = PrincipalId.generate();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                "Organization " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                teamId.value(),
                organizationId.value(),
                "Team " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                workspaceId.value(),
                organizationId.value(),
                teamId.value(),
                "Workspace " + suffix);
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
                suffix.substring(0, Math.min(10, suffix.length())).toUpperCase(),
                "Project " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                actorId.value(),
                organizationId.value(),
                "Actor " + suffix);
        return new Fixture(organizationId, teamId, workspaceId, projectId, actorId);
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    private record Fixture(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            PrincipalId actorId) {

        private WorkItemCommandContext context(Optional<String> idempotencyKey) {
            return new WorkItemCommandContext(
                    organizationId,
                    teamId,
                    workspaceId,
                    EventActorType.USER,
                    actorId,
                    CORRELATION_ID,
                    Optional.of(CAUSATION_ID),
                    idempotencyKey);
        }
    }

    private static final class SimulatedOutboxFailure extends RuntimeException {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = WorkItemEntity.class)
    @Import({
        JpaWorkItemRepositoryAdapter.class,
        WorkItemEntityMapper.class,
        JdbcDomainEventStore.class,
        JdbcOutboxRepository.class,
        JdbcCommandReceiptStore.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
