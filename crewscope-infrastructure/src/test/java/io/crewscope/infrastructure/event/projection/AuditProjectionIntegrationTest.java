package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.infrastructure.event.IdempotentEventDispatcher;
import io.crewscope.infrastructure.event.JdbcOutboxClaimStore;
import io.crewscope.infrastructure.event.OutboxPublisherConfiguration;
import io.crewscope.infrastructure.event.PollingOutboxPublisher;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Proves ordered, atomic and restart-safe AuditEvent projection against PostgreSQL. */
@SpringBootTest(
        classes = AuditProjectionIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.outbox.enabled=false",
            "crewscope.outbox.parallelism=2"
        })
class AuditProjectionIntegrationTest extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-07T02:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcProjectionCheckpointStore checkpointStore;

    @Autowired
    private AuditEventProjector auditProjector;

    @Autowired
    private PollingOutboxPublisher publisher;

    @Autowired
    @Qualifier("auditEventProjectionRunner")
    private DomainEventConsumer productionRunner;

    private UUID organizationId;
    private UUID teamId;
    private UUID workspaceId;
    private UUID actorId;
    private UUID aggregateId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        aggregateId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Projection Org', 'ACTIVE')",
                organizationId);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Projection Team', 'ACTIVE')
                """,
                teamId,
                organizationId);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Projection Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Projection Actor', 'ACTIVE')
                """,
                actorId,
                organizationId);
    }

    @Test
    void publishesOutboxIntoAuditAndCheckpointEndToEnd() {
        EventPublication versionZero = seedEvent(0, BASE_TIME, true);
        EventPublication versionOne = seedEvent(1, BASE_TIME.plusSeconds(1), true);

        assertEquals(1, publisher.publishAvailable().delivered());
        assertEquals(1, publisher.publishAvailable().delivered());

        assertEquals(2, count("audit_event"));
        assertEquals(2, countByStatus("DELIVERED"));
        assertEquals(2, receiptCount());
        Map<String, Object> checkpoint = checkpoint();
        assertEquals(versionOne.eventId(), checkpoint.get("last_event_id"));
        assertEquals("aggregate-version:1", checkpoint.get("last_event_cursor"));
        assertEquals(2L, ((Number) checkpoint.get("version")).longValue());

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                """
                SELECT principal_id, initiator_id, actor_type, actor_id,
                       agent_principal_id, event_type, subject_type, subject_id,
                       event_category, outcome, retention_level, domain_event_id,
                       correlation_id, schema_version,
                       authorization_context::TEXT AS authorization_context,
                       payload::TEXT AS payload
                FROM crewscope.audit_event
                WHERE domain_event_id = ?
                """,
                versionZero.eventId());
        assertEquals(actorId, audit.get("principal_id"));
        assertEquals(actorId, audit.get("initiator_id"));
        assertEquals("USER", audit.get("actor_type"));
        assertEquals(actorId, audit.get("actor_id"));
        assertEquals(null, audit.get("agent_principal_id"));
        assertEquals("WORK_ITEM_CREATED", audit.get("event_type"));
        assertEquals("WORK_ITEM", audit.get("subject_type"));
        assertEquals(aggregateId, audit.get("subject_id"));
        assertEquals("WORK", audit.get("event_category"));
        assertEquals("SUCCEEDED", audit.get("outcome"));
        assertEquals("STANDARD", audit.get("retention_level"));
        assertEquals(versionZero.eventId(), audit.get("domain_event_id"));
        assertEquals("1", audit.get("schema_version"));
        assertEquals(
                "AUD-1",
                objectMapper.readTree((String) audit.get("payload"))
                        .get("itemKey")
                        .stringValue());
        assertEquals(
                "REVIEWED",
                objectMapper.readTree((String) audit.get("authorization_context"))
                        .get("classification")
                        .stringValue());
    }

    @Test
    void duplicateDeliveryAndRunnerRestartDoNotDuplicateAudit() {
        EventPublication event = seedEvent(0, BASE_TIME, false);
        IdempotentEventDispatcher firstDispatcher = dispatcher();

        assertTrue(firstDispatcher.dispatch(productionRunner, event));
        assertEquals(false, firstDispatcher.dispatch(productionRunner, event));
        assertEquals(1, count("audit_event"));

        jdbcTemplate.update(
                "DELETE FROM crewscope.event_consumer_receipt WHERE domain_event_id = ?",
                event.eventId());
        DomainEventConsumer restartedRunner = runner(auditProjector);
        assertTrue(dispatcher().dispatch(restartedRunner, event));

        assertEquals(1, count("audit_event"));
        assertEquals("aggregate-version:0", checkpoint().get("last_event_cursor"));
        assertEquals(1L, ((Number) checkpoint().get("version")).longValue());
    }

    @Test
    void mapsAgentActorWithoutInventingAHumanInitiator() {
        EventPublication event = seedEvent(
                0, BASE_TIME, false, "SPECIALIST_AGENT", actorId);

        assertTrue(dispatcher().dispatch(productionRunner, event));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                """
                SELECT principal_id, initiator_id, actor_type, actor_id, agent_principal_id
                FROM crewscope.audit_event
                WHERE domain_event_id = ?
                """,
                event.eventId());
        assertEquals(actorId, audit.get("principal_id"));
        assertEquals(null, audit.get("initiator_id"));
        assertEquals("SPECIALIST_AGENT", audit.get("actor_type"));
        assertEquals(actorId, audit.get("actor_id"));
        assertEquals(actorId, audit.get("agent_principal_id"));
    }

    @Test
    void gapRollsBackReceiptCheckpointAndAuditThenSucceedsInOrder() {
        EventPublication versionOne = seedEvent(1, BASE_TIME.plusSeconds(1), false);
        EventPublication versionZero = seedEvent(0, BASE_TIME, false);
        IdempotentEventDispatcher dispatcher = dispatcher();

        assertThrows(
                ProjectionGapException.class,
                () -> dispatcher.dispatch(productionRunner, versionOne));
        assertEquals(0, count("audit_event"));
        assertEquals(0, count("event_consumer_receipt"));
        assertEquals(0, count("event_projection_checkpoint"));

        assertTrue(dispatcher.dispatch(productionRunner, versionZero));
        assertTrue(dispatcher.dispatch(productionRunner, versionOne));
        assertEquals(2, count("audit_event"));
        assertEquals("aggregate-version:1", checkpoint().get("last_event_cursor"));
    }

    @Test
    void sameAggregateVersionCanContainMultipleOrderedDomainEvents() {
        EventPublication first = seedEvent(0, BASE_TIME, false);
        EventPublication second = seedEvent(0, BASE_TIME.plusSeconds(1), false);
        IdempotentEventDispatcher dispatcher = dispatcher();

        assertTrue(dispatcher.dispatch(productionRunner, first));
        assertTrue(dispatcher.dispatch(productionRunner, second));

        assertEquals(2, count("audit_event"));
        assertEquals(2, count("event_consumer_receipt"));
        assertEquals(second.eventId(), checkpoint().get("last_event_id"));
        assertEquals("aggregate-version:0", checkpoint().get("last_event_cursor"));
        assertEquals(2L, ((Number) checkpoint().get("version")).longValue());
    }

    @Test
    void staleReplayBelowCheckpointIsIgnoredWithoutMovingPositionBackward() {
        EventPublication versionZero = seedEvent(0, BASE_TIME, false);
        EventPublication versionOne = seedEvent(1, BASE_TIME.plusSeconds(1), false);
        IdempotentEventDispatcher dispatcher = dispatcher();
        assertTrue(dispatcher.dispatch(productionRunner, versionZero));
        assertTrue(dispatcher.dispatch(productionRunner, versionOne));
        jdbcTemplate.update(
                "DELETE FROM crewscope.event_consumer_receipt WHERE domain_event_id = ?",
                versionZero.eventId());

        assertTrue(dispatcher.dispatch(runner(auditProjector), versionZero));

        assertEquals(2, count("audit_event"));
        assertEquals(versionOne.eventId(), checkpoint().get("last_event_id"));
        assertEquals("aggregate-version:1", checkpoint().get("last_event_cursor"));
        assertEquals(2L, ((Number) checkpoint().get("version")).longValue());
    }

    @Test
    void projectorFailureRollsBackBusinessSideEffectReceiptAndCheckpoint() {
        EventPublication event = seedEvent(0, BASE_TIME, false);
        ProjectionHandler failingHandler = new ProjectionHandler() {
            @Override
            public String projectionName() {
                return "failing-projection-v1";
            }

            @Override
            public void project(ProjectionEvent ignored) {
                jdbcTemplate.update(
                        "UPDATE crewscope.organization SET name = 'Must Roll Back' WHERE id = ?",
                        organizationId);
                throw new SimulatedProjectionFailure();
            }
        };

        assertThrows(
                SimulatedProjectionFailure.class,
                () -> dispatcher().dispatch(runner(failingHandler), event));

        assertEquals("Projection Org", jdbcTemplate.queryForObject(
                "SELECT name FROM crewscope.organization WHERE id = ?",
                String.class,
                organizationId));
        assertEquals(0, count("event_consumer_receipt"));
        assertEquals(0, count("event_projection_checkpoint"));
    }

    private DomainEventConsumer runner(ProjectionHandler handler) {
        return new CheckpointedProjectionRunner(
                handler,
                checkpointStore,
                new ProjectionEventJsonMapper(objectMapper),
                Clock.fixed(BASE_TIME.plusSeconds(10), ZoneOffset.UTC));
    }

    private IdempotentEventDispatcher dispatcher() {
        return new IdempotentEventDispatcher(
                jdbcTemplate,
                transactionManager,
                Clock.fixed(BASE_TIME.plusSeconds(10), ZoneOffset.UTC));
    }

    private EventPublication seedEvent(long aggregateVersion, Instant occurredAt, boolean outbox) {
        return seedEvent(aggregateVersion, occurredAt, outbox, "USER", actorId);
    }

    private EventPublication seedEvent(
            long aggregateVersion,
            Instant occurredAt,
            boolean outbox,
            String actorType,
            UUID eventActorId) {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, team_id, workspace_id,
                    subject_type, subject_id, aggregate_version,
                    actor_type, actor_id, correlation_id, occurred_at, payload
                ) VALUES (
                    ?, 'WORK_ITEM_CREATED', '1',
                    ?, ?, ?,
                    'WORK_ITEM', ?, ?,
                    ?, ?, ?, ?, CAST(? AS JSONB)
                )
                """,
                eventId,
                organizationId,
                teamId,
                workspaceId,
                aggregateId,
                aggregateVersion,
                actorType,
                eventActorId,
                correlationId,
                occurredAt.atOffset(ZoneOffset.UTC),
                """
                {
                  "projectId":"%s",
                  "itemKey":"AUD-1",
                  "title":"Projection work",
                  "status":"OPEN",
                  "initialOwnerAssignmentId":null,
                  "initialOwnerPrincipalId":null
                }
                """.formatted(UUID.randomUUID()));
        UUID outboxId = UUID.randomUUID();
        String partitionKey = "%s:WORK_ITEM:%s".formatted(organizationId, aggregateId);
        if (outbox) {
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.outbox_event (
                        id, domain_event_id, topic, partition_key,
                        delivery_status, retry_count, created_at, version, updated_at
                    ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)
                    """,
                    outboxId,
                    eventId,
                    PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                    partitionKey,
                    occurredAt.atOffset(ZoneOffset.UTC),
                    occurredAt.atOffset(ZoneOffset.UTC));
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", "WORK_ITEM_CREATED");
        root.put("schemaVersion", "1");
        root.put("organizationId", organizationId.toString());
        root.put("teamId", teamId.toString());
        root.put("workspaceId", workspaceId.toString());
        root.put("aggregateType", "WORK_ITEM");
        root.put("aggregateId", aggregateId.toString());
        root.put("aggregateVersion", aggregateVersion);
        root.put("actorType", actorType);
        root.put("actorId", eventActorId.toString());
        root.put("correlationId", correlationId.toString());
        root.set("causationId", objectMapper.nullNode());
        root.set("idempotencyKey", objectMapper.nullNode());
        root.put("occurredAt", occurredAt.toString());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("projectId", UUID.randomUUID().toString());
        payload.put("itemKey", "AUD-1");
        payload.put("title", "Projection work");
        payload.put("status", "OPEN");
        payload.set("initialOwnerAssignmentId", objectMapper.nullNode());
        payload.set("initialOwnerPrincipalId", objectMapper.nullNode());
        root.set("payload", payload);
        return new EventPublication(
                outboxId,
                eventId,
                PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                partitionKey,
                1,
                io.crewscope.domain.shared.time.UtcTimestamp.from(occurredAt),
                objectMapper.writeValueAsString(root));
    }

    private Map<String, Object> checkpoint() {
        return jdbcTemplate.queryForMap(
                """
                SELECT last_event_id, last_event_cursor, version
                FROM crewscope.event_projection_checkpoint
                WHERE projection_name = ?
                """,
                AuditEventProjector.PROJECTION_NAME);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    private int receiptCount() {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.event_consumer_receipt
                WHERE consumer_name = ?
                """,
                Integer.class,
                "projection:" + AuditEventProjector.PROJECTION_NAME);
    }

    private int countByStatus(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.outbox_event WHERE delivery_status = ?",
                Integer.class,
                status);
    }

    private static final class SimulatedProjectionFailure extends RuntimeException {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        JdbcOutboxClaimStore.class,
        JdbcProjectionCheckpointStore.class,
        AuditEventProjector.class,
        ProjectionConfiguration.class,
        OutboxPublisherConfiguration.class
    })
    static class TestApplication {}
}
