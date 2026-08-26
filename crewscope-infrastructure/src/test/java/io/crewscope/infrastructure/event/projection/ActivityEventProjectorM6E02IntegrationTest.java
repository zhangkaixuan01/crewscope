package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.activity.CrewScopeActivityEventTypes;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.event.IdempotentEventDispatcher;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL contract for M6-E02's safe generation-aware Team Activity Projector. */
@SpringBootTest(
        classes = ActivityEventProjectorM6E02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class ActivityEventProjectorM6E02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-26T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private UUID teamId;
    private UUID actorId;
    private ActivityEventProjector projector;
    private GenerationAwareProjectionRunner runner;
    private ProjectionHistoryReplayer replayer;
    private ProjectionGenerationLease activeLease;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbcTemplate.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                ActivityEventProjector.PROJECTION_NAME.value());
        organizationId = OrganizationId.generate();
        teamId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Activity Org', 'ACTIVE')",
                organizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Activity Actor', 'ORGANIZATION', 'ACTIVE')
                """,
                actorId,
                organizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Activity Team', 'ACTIVE')
                """,
                teamId,
                organizationId.value());
        projector = new ActivityEventProjector(
                jdbcTemplate, objectMapper, CrewScopeActivityEventTypes.reviewedRegistry());
        JdbcProjectionGenerationRegistry generationRegistry =
                new JdbcProjectionGenerationRegistry(jdbcTemplate, transactionManager);
        activeLease = lease(ProjectionGeneration.FIRST);
        JdbcGenerationProjectionStore store = new JdbcGenerationProjectionStore(jdbcTemplate);
        ProjectionEventJsonMapper eventMapper = new ProjectionEventJsonMapper(objectMapper);
        Clock clock = Clock.fixed(BASE_TIME.plusSeconds(60), ZoneOffset.UTC);
        runner = new GenerationAwareProjectionRunner(
                projector,
                generationRegistry,
                store,
                eventMapper,
                transactionManager,
                clock);
        replayer = new ProjectionHistoryReplayer(
                new JdbcProjectionEventHistoryStore(jdbcTemplate, objectMapper), runner);
    }

    @Test
    void publicPayloadUsesReviewedSnapshotAndDropsRawOrSensitiveFields() {
        UUID workItemId = UUID.randomUUID();
        UUID eventId = seedEvent(
                "WORK_ITEM_CREATED",
                "1",
                workItemId,
                0,
                BASE_TIME,
                """
                {
                  "projectId":"%s",
                  "itemKey":"OPS-1",
                  "title":"Repair activity stream",
                  "status":"OPEN",
                  "credentialToken":"must-never-project",
                  "rawPayload":"must-never-project"
                }
                """.formatted(UUID.randomUUID()));

        runner.consume(publication(eventId));

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload::TEXT FROM crewscope.activity_event WHERE domain_event_id = ?",
                String.class,
                eventId);
        assertEquals(
                objectMapper.readTree(
                        """
                        {"itemKey":"OPS-1","status":"OPEN","title":"Repair activity stream"}
                        """),
                objectMapper.readTree(payload));
        assertFalse(payload.contains("credential"));
        assertFalse(payload.contains("rawPayload"));
        assertEquals("WORK_ITEM_PARTICIPANTS", scalar(eventId, "visibility"));
        assertEquals(workItemId, uuid(eventId, "subject_id"));
    }

    @Test
    void currentTaskSchemaV2ProjectsThroughItsExactReviewedCoordinate() {
        UUID taskId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID eventId = seedEvent(
                "TASK_DELEGATED_TO_AGENT",
                "2",
                taskId,
                0,
                BASE_TIME,
                """
                {
                  "taskId":"%s",
                  "workItemId":"%s",
                  "taskStatus":"READY",
                  "executionStatus":"PENDING"
                }
                """.formatted(taskId, workItemId));

        runner.consume(publication(eventId));

        assertEquals("TASK_DELEGATED_TO_AGENT", scalar(eventId, "event_type"));
        assertEquals(taskId, uuid(eventId, "subject_id"));
        assertEquals(1, jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM crewscope.activity_reference reference
                JOIN crewscope.activity_event activity
                  ON activity.organization_id = reference.organization_id
                 AND activity.projection_name = reference.projection_name
                 AND activity.generation = reference.generation
                 AND activity.activity_event_id = reference.activity_event_id
                WHERE activity.domain_event_id = ?
                  AND reference.reference_type = 'WORK_ITEM'
                  AND reference.reference_id = ?
                """,
                Integer.class,
                eventId,
                workItemId));
    }

    @Test
    void firstEventAtomicallyBootstrapsTheActivityDefinitionGenerationAndPointer() {
        OrganizationId freshOrganizationId = OrganizationId.generate();
        UUID freshTeamId = UUID.randomUUID();
        UUID freshActorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) "
                        + "VALUES (?, 'Fresh Activity Org', 'ACTIVE')",
                freshOrganizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Fresh Actor', 'ORGANIZATION', 'ACTIVE')
                """,
                freshActorId,
                freshOrganizationId.value());
        jdbcTemplate.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'Fresh Team', 'ACTIVE')",
                freshTeamId,
                freshOrganizationId.value());
        UUID eventId = seedEvent(
                freshOrganizationId, freshTeamId, freshActorId,
                "WORK_ITEM_CREATED", "1", UUID.randomUUID(), 0, BASE_TIME,
                workItemCreatedPayload("OPS-BOOT", "Bootstrap", "OPEN"));

        IdempotentEventDispatcher dispatcher = new IdempotentEventDispatcher(
                jdbcTemplate,
                transactionManager,
                Clock.fixed(BASE_TIME.plusSeconds(60), ZoneOffset.UTC));

        assertTrue(dispatcher.dispatch(
                runner, publication(freshOrganizationId, eventId)));

        assertEquals(1, jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.activity_event
                WHERE organization_id = ? AND projection_name = ? AND generation = 1
                """,
                Integer.class,
                freshOrganizationId.value(),
                ActivityEventProjector.PROJECTION_NAME.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_definition
                WHERE projection_name = ? AND definition_version = 1
                  AND projection_schema_version = 1
                  AND canonical_encoder = 'activity.canonical-v1'
                  AND validator = 'activity.expected-v1'
                """,
                Integer.class,
                ActivityEventProjector.PROJECTION_NAME.value()));
        assertEquals(1L, jdbcTemplate.queryForObject(
                """
                SELECT active_generation FROM crewscope.projection_pointer
                WHERE organization_id = ? AND projection_name = ?
                """,
                Long.class,
                freshOrganizationId.value(),
                ActivityEventProjector.PROJECTION_NAME.value()));
    }

    @Test
    void duplicateAndSameAggregateEventsProduceOneRowPerSourceInStrictSequence() {
        UUID workItemId = UUID.randomUUID();
        UUID created = seedEvent(
                "WORK_ITEM_CREATED", "1", workItemId, 0, BASE_TIME,
                workItemCreatedPayload("OPS-2", "First", "OPEN"));
        EventPublication createdPublication = publication(created);
        runner.consume(createdPublication);
        runner.consume(createdPublication);
        UUID changed = seedEvent(
                "WORK_ITEM_STATUS_CHANGED", "1", workItemId, 1,
                BASE_TIME.plusSeconds(1),
                """
                {"projectId":"%s","itemKey":"OPS-2","previousStatus":"OPEN","status":"IN_PROGRESS"}
                """.formatted(UUID.randomUUID()));
        runner.consume(publication(changed));

        assertEquals(2, activityCount(ProjectionGeneration.FIRST));
        assertEquals(List.of(1L, 2L), sequences(ProjectionGeneration.FIRST));
        assertEquals(2, receiptCount(ProjectionGeneration.FIRST));
    }

    @Test
    void concurrentCrossAggregateEventsReceiveContiguousTeamSequence() throws Exception {
        List<UUID> eventIds = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            eventIds.add(seedEvent(
                    "WORK_ITEM_CREATED",
                    "1",
                    UUID.randomUUID(),
                    0,
                    BASE_TIME.plusSeconds(index),
                    workItemCreatedPayload("OPS-" + (index + 10), "Item " + index, "OPEN")));
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (UUID eventId : eventIds) {
                futures.add(executor.submit(() -> runner.consume(publication(eventId))));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L),
                sequences(ProjectionGeneration.FIRST));
    }

    @Test
    void unknownEventIsIgnoredButAdvancesAggregateOrderForFollowingKnownEvent() {
        UUID workItemId = UUID.randomUUID();
        UUID unknown = seedEvent(
                "PRIVATE_PROVIDER_PAYLOAD_CAPTURED", "1", workItemId, 0, BASE_TIME,
                "{\"rawPayload\":\"private\"}");
        runner.consume(publication(unknown));
        UUID known = seedEvent(
                "WORK_ITEM_STATUS_CHANGED", "1", workItemId, 1,
                BASE_TIME.plusSeconds(1),
                """
                {"projectId":"%s","itemKey":"OPS-20","previousStatus":"OPEN","status":"DONE"}
                """.formatted(UUID.randomUUID()));
        runner.consume(publication(known));

        assertEquals(1, activityCount(ProjectionGeneration.FIRST));
        assertEquals(2, receiptCount(ProjectionGeneration.FIRST));
        assertEquals(1L, sequences(ProjectionGeneration.FIRST).get(0));
    }

    @Test
    void validUserOwnedProviderEventIsIgnoredOutsideTeamActivityScope() {
        UUID eventId = seedEvent(
                "GITHUB_CONNECTION_CREATED", "1", UUID.randomUUID(), 0, BASE_TIME,
                """
                {"connectorKey":"github","ownerType":"USER","status":"ACTIVE"}
                """);
        jdbcTemplate.update(
                "UPDATE crewscope.domain_event SET team_id = NULL WHERE event_id = ?",
                eventId);

        runner.consume(publication(eventId));

        assertEquals(0, activityCount(ProjectionGeneration.FIRST));
        assertEquals(1, receiptCount(ProjectionGeneration.FIRST));
        assertEquals(
                projector.expectedSnapshot(organizationId),
                projector.actualSnapshot(activeLease.key()));
    }

    @Test
    void malformedRegisteredPayloadRollsBackReceiptCheckpointAndActivity() {
        UUID eventId = seedEvent(
                "WORK_ITEM_CREATED", "1", UUID.randomUUID(), 0, BASE_TIME,
                "{\"title\":\"Missing reviewed fields\",\"status\":\"OPEN\"}");

        assertThrows(
                InvalidProjectionEventException.class,
                () -> runner.consume(publication(eventId)));

        assertEquals(0, activityCount(ProjectionGeneration.FIRST));
        assertEquals(0, receiptCount(ProjectionGeneration.FIRST));
        assertEquals(0, checkpointCount(ProjectionGeneration.FIRST));
    }

    @Test
    void historyReplayBuildsAnEquivalentShadowCanonicalHash() {
        UUID first = seedEvent(
                "TEAM_MEMBER_JOINED", "1", UUID.randomUUID(), 0, BASE_TIME,
                "{\"userPrincipalId\":\"%s\",\"joinMethod\":\"INVITED\"}"
                        .formatted(UUID.randomUUID()));
        UUID second = seedEvent(
                "WORK_ITEM_CREATED", "1", UUID.randomUUID(), 0,
                BASE_TIME.plusSeconds(1), workItemCreatedPayload("OPS-30", "Hash", "OPEN"));
        runner.consume(publication(first));
        runner.consume(publication(second));
        ProjectionGenerationLease shadow = startShadow();

        Optional<ProjectionHistoryCursor> cursor = Optional.empty();
        while (true) {
            ProjectionReplayBatchResult batch = replayer.replayPage(shadow, cursor, 1);
            if (batch.caughtUp()) {
                break;
            }
            cursor = batch.nextCursor();
        }

        ProjectionSnapshot expected = projector.expectedSnapshot(organizationId);
        assertEquals(expected, projector.actualSnapshot(activeLease.key()));
        assertEquals(expected, projector.actualSnapshot(shadow.key()));
        assertEquals(2, activityCount(shadow.key().generation()));
    }

    private ProjectionGenerationLease startShadow() {
        ProjectionRebuildJobId jobId = ProjectionRebuildJobId.generate();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        rebuild_job_id, status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 2, 1, ?, 'BUILDING', 1, 0, ?, ?)
                    """,
                    organizationId.value(),
                    ActivityEventProjector.PROJECTION_NAME.value(),
                    jobId.value(),
                    BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC),
                    BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC));
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.projection_rebuild_job (
                        id, organization_id, projection_name, definition_version,
                        generation, requested_by_principal_id, status, version,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, 1, 2, ?, 'BUILDING', 0, ?, ?)
                    """,
                    jobId.value(),
                    organizationId.value(),
                    ActivityEventProjector.PROJECTION_NAME.value(),
                    actorId,
                    BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC),
                    BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC));
        });
        return lease(new ProjectionGeneration(2));
    }

    private ProjectionGenerationLease lease(ProjectionGeneration generation) {
        return new ProjectionGenerationLease(
                new ProjectionGenerationKey(
                        organizationId, ActivityEventProjector.PROJECTION_NAME, generation),
                ProjectionFencingToken.INITIAL);
    }

    private UUID seedEvent(
            String eventType,
            String schemaVersion,
            UUID aggregateId,
            long aggregateVersion,
            Instant occurredAt,
            String payload) {
        return seedEvent(
                organizationId, teamId, actorId, eventType, schemaVersion,
                aggregateId, aggregateVersion, occurredAt, payload);
    }

    private UUID seedEvent(
            OrganizationId targetOrganizationId,
            UUID targetTeamId,
            UUID targetActorId,
            String eventType,
            String schemaVersion,
            UUID aggregateId,
            long aggregateVersion,
            Instant occurredAt,
            String payload) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    subject_type, subject_id, aggregate_version, actor_type, actor_id,
                    correlation_id, occurred_at, payload
                ) VALUES (?, ?, ?, ?, ?, 'WORK_ITEM', ?, ?, 'USER', ?, ?, ?, CAST(? AS JSONB))
                """,
                eventId,
                eventType,
                schemaVersion,
                targetOrganizationId.value(),
                targetTeamId,
                aggregateId,
                aggregateVersion,
                targetActorId,
                UUID.randomUUID(),
                occurredAt.atOffset(ZoneOffset.UTC),
                payload);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key, delivery_status,
                    retry_count, created_at, version, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)
                """,
                UUID.randomUUID(),
                eventId,
                PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                targetOrganizationId + ":WORK_ITEM:" + aggregateId,
                occurredAt.atOffset(ZoneOffset.UTC),
                occurredAt.atOffset(ZoneOffset.UTC));
        return eventId;
    }

    private EventPublication publication(UUID eventId) {
        return publication(organizationId, eventId);
    }

    private EventPublication publication(OrganizationId targetOrganizationId, UUID eventId) {
        return new JdbcProjectionEventHistoryStore(jdbcTemplate, objectMapper)
                .read(targetOrganizationId, Optional.empty(), 100)
                .events().stream()
                .map(ProjectionHistoryEvent::publication)
                .filter(event -> event.eventId().equals(eventId))
                .findFirst()
                .orElseThrow();
    }

    private String workItemCreatedPayload(String itemKey, String title, String status) {
        return """
                {"projectId":"%s","itemKey":"%s","title":"%s","status":"%s"}
                """.formatted(UUID.randomUUID(), itemKey, title, status);
    }

    private int activityCount(ProjectionGeneration generation) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.activity_event
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                Integer.class,
                organizationId.value(),
                ActivityEventProjector.PROJECTION_NAME.value(),
                generation.value());
    }

    private int receiptCount(ProjectionGeneration generation) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_consumer_receipt
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                Integer.class,
                organizationId.value(),
                ActivityEventProjector.PROJECTION_NAME.value(),
                generation.value());
    }

    private int checkpointCount(ProjectionGeneration generation) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_generation_checkpoint
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                Integer.class,
                organizationId.value(),
                ActivityEventProjector.PROJECTION_NAME.value(),
                generation.value());
    }

    private List<Long> sequences(ProjectionGeneration generation) {
        return jdbcTemplate.queryForList(
                """
                SELECT team_sequence FROM crewscope.activity_event
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                ORDER BY team_sequence
                """,
                Long.class,
                organizationId.value(),
                ActivityEventProjector.PROJECTION_NAME.value(),
                generation.value());
    }

    private String scalar(UUID eventId, String column) {
        assertTrue(List.of("event_type", "visibility").contains(column));
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM crewscope.activity_event WHERE domain_event_id = ?",
                String.class,
                eventId);
    }

    private UUID uuid(UUID eventId, String column) {
        assertTrue(List.of("subject_id").contains(column));
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM crewscope.activity_event WHERE domain_event_id = ?",
                UUID.class,
                eventId);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
