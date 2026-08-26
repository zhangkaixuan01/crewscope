package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.projection.ProjectionCanonicalHash;
import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Production V27/V28 integration contract for M6-E01's Generation-aware projection runtime. */
@SpringBootTest(
        classes = GenerationAwareProjectionRuntimeM6E01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class GenerationAwareProjectionRuntimeM6E01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final ProjectionName PROJECTION_NAME = new ProjectionName("m6-e01-probe");
    private static final ProjectionDefinition DEFINITION = new ProjectionDefinition(
            PROJECTION_NAME,
            ProjectionDefinitionVersion.V1,
            SchemaVersion.V1,
            "m6-e01.probe-v1",
            "m6-e01.probe-v1");
    private static final Instant BASE_TIME = Instant.parse("2026-08-25T14:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private PrincipalId actorId;
    private ProbeProjectionHandler handler;
    private GenerationAwareProjectionRunner runner;
    private ProjectionHistoryReplayer replayer;
    private JdbcProjectionGenerationLifecycle lifecycle;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS crewscope.m6_e01_projection_row");
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbcTemplate.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                PROJECTION_NAME.value());
        jdbcTemplate.execute(
                """
                CREATE TABLE crewscope.m6_e01_projection_row (
                    organization_id UUID NOT NULL,
                    projection_name VARCHAR(180) NOT NULL,
                    generation BIGINT NOT NULL,
                    domain_event_id UUID NOT NULL,
                    partition_key VARCHAR(240) NOT NULL,
                    canonical_value VARCHAR(1000) NOT NULL,
                    fencing_token BIGINT NOT NULL,
                    PRIMARY KEY (
                        organization_id, projection_name, generation, domain_event_id
                    ),
                    FOREIGN KEY (organization_id, projection_name, generation)
                        REFERENCES crewscope.projection_generation (
                            organization_id, projection_name, generation
                        ) ON DELETE RESTRICT,
                    FOREIGN KEY (organization_id, domain_event_id)
                        REFERENCES crewscope.domain_event (organization_id, event_id)
                        ON DELETE RESTRICT
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TRIGGER trg_m6_e01_projection_row_lease
                BEFORE INSERT OR UPDATE ON crewscope.m6_e01_projection_row
                FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_write_lease_v27()
                """);

        organizationId = OrganizationId.generate();
        actorId = PrincipalId.generate();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                "M6 E01 Organization");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Projection Administrator', 'ORGANIZATION', 'ACTIVE')
                """,
                actorId.value(),
                organizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 1, 1, ?, ?)
                """,
                PROJECTION_NAME.value(),
                DEFINITION.canonicalEncoder(),
                DEFINITION.validator());

        Clock clock = Clock.fixed(BASE_TIME.plusSeconds(30), ZoneOffset.UTC);
        JdbcProjectionGenerationRegistry registry = new JdbcProjectionGenerationRegistry(
                jdbcTemplate, transactionManager);
        JdbcGenerationProjectionStore store = new JdbcGenerationProjectionStore(jdbcTemplate);
        ProjectionEventJsonMapper mapper = new ProjectionEventJsonMapper(objectMapper);
        handler = new ProbeProjectionHandler();
        runner = new GenerationAwareProjectionRunner(
                handler, registry, store, mapper, transactionManager, clock);
        JdbcProjectionEventHistoryStore historyStore = new JdbcProjectionEventHistoryStore(
                jdbcTemplate, objectMapper);
        replayer = new ProjectionHistoryReplayer(historyStore, runner);
        lifecycle = new JdbcProjectionGenerationLifecycle(
                jdbcTemplate, transactionManager, clock);
    }

    @Test
    void activeCommitsBeforeShadowGapAndRestartedHistoryReplayConverges() {
        bootstrapActive();
        UUID aggregateId = UUID.randomUUID();
        UUID versionZero = seedEvent(aggregateId, 0, BASE_TIME, "v0");
        runner.consume(publication(versionZero));
        Shadow shadow = startShadow();
        UUID versionOne = seedEvent(aggregateId, 1, BASE_TIME.plusSeconds(1), "v1");

        assertThrows(ProjectionGapException.class, () -> runner.consume(publication(versionOne)));
        assertEquals(2, rowCount(ProjectionGeneration.FIRST));
        assertEquals(0, rowCount(shadow.generation()));
        assertEquals(0, receiptCount(shadow.generation()));
        assertEquals(0, checkpointCount(shadow.generation()));

        ProjectionReplayBatchResult first = replayer.replayPage(
                shadow.lease(), Optional.empty(), 1);
        ProjectionReplayBatchResult second = replayer.replayPage(
                shadow.lease(), first.nextCursor(), 1);
        ProjectionReplayBatchResult caughtUp = replayer.replayPage(
                shadow.lease(), second.nextCursor(), 1);
        assertEquals(1, first.applied());
        assertEquals(1, second.applied());
        assertTrue(caughtUp.caughtUp());

        // A new runner/replayer instance proves that all recovery state is database-resident.
        GenerationAwareProjectionRunner restartedRunner = new GenerationAwareProjectionRunner(
                handler,
                new JdbcProjectionGenerationRegistry(jdbcTemplate, transactionManager),
                new JdbcGenerationProjectionStore(jdbcTemplate),
                new ProjectionEventJsonMapper(objectMapper),
                transactionManager,
                Clock.fixed(BASE_TIME.plusSeconds(40), ZoneOffset.UTC));
        ProjectionHistoryReplayer restarted = new ProjectionHistoryReplayer(
                new JdbcProjectionEventHistoryStore(jdbcTemplate, objectMapper), restartedRunner);
        ProjectionReplayBatchResult duplicate = restarted.replayPage(
                shadow.lease(), Optional.empty(), 100);

        assertEquals(2, duplicate.duplicateOrStale());
        assertEquals(2, rowCount(shadow.generation()));
        assertEquals(2, receiptCount(shadow.generation()));
        assertEquals(1, checkpointCount(shadow.generation()));
        assertEquals(
                handler.expectedSnapshot(organizationId),
                handler.actualSnapshot(key(shadow.generation())));
    }

    @Test
    void projectorFailureRollsBackAndValidationFencesTheOldLease() {
        bootstrapActive();
        Shadow shadow = startShadow();
        UUID aggregateId = UUID.randomUUID();
        UUID versionZero = seedEvent(aggregateId, 0, BASE_TIME, "rollback");
        handler.failOn(versionZero);

        assertThrows(
                SimulatedProjectionFailure.class,
                () -> runner.consume(shadow.lease(), publication(versionZero)));
        assertEquals(0, rowCount(shadow.generation()));
        assertEquals(0, receiptCount(shadow.generation()));
        assertEquals(0, checkpointCount(shadow.generation()));

        handler.clearFailure(versionZero);
        assertEquals(
                ProjectionConsumptionResult.APPLIED,
                runner.consume(shadow.lease(), publication(versionZero)));
        ProjectionValidationOutcome validation = lifecycle.validate(
                handler,
                validationRequest(shadow, 0, 0));
        assertTrue(validation.passed());
        assertEquals(2, validation.lease().fencingToken().value());

        UUID versionOne = seedEvent(aggregateId, 1, BASE_TIME.plusSeconds(1), "late");
        assertEquals(
                ProjectionConsumptionResult.LEASE_REJECTED,
                runner.consume(shadow.lease(), publication(versionOne)));
        assertEquals(1, rowCount(shadow.generation()));
        assertEquals(0, receiptForEvent(shadow.generation(), versionOne));
    }

    @Test
    void handlerDefinitionVersionMismatchRejectsBeforeAnyProjectionStateIsWritten() {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 2, 1, 'm6-e01.probe-v2', 'm6-e01.probe-v2')
                """,
                PROJECTION_NAME.value());
        ProjectionGenerationLease active = bootstrapActive(new ProjectionDefinitionVersion(2));
        UUID eventId = seedEvent(UUID.randomUUID(), 0, BASE_TIME, "wrong-definition");

        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> runner.consume(active, publication(eventId)));

        assertTrue(mismatch.getMessage().contains("does not match generation version"));
        assertEquals(0, rowCount(ProjectionGeneration.FIRST));
        assertEquals(0, receiptCount(ProjectionGeneration.FIRST));
        assertEquals(0, checkpointCount(ProjectionGeneration.FIRST));
    }

    @Test
    void staleValidationCannotSwitchAndRevalidationSwitchesAtomically() {
        ProjectionGenerationLease active = bootstrapActive();
        UUID aggregateId = UUID.randomUUID();
        UUID versionZero = seedEvent(aggregateId, 0, BASE_TIME, "v0");
        runner.consume(publication(versionZero));
        Shadow shadow = startShadow();
        replayAll(shadow.lease());

        ProjectionValidationOutcome firstValidation = lifecycle.validate(
                handler, validationRequest(shadow, 0, 0));
        assertTrue(firstValidation.passed());
        UUID versionOne = seedEvent(aggregateId, 1, BASE_TIME.plusSeconds(1), "v1");
        runner.consume(publication(versionOne));

        ProjectionSwitchRequest staleSwitch = new ProjectionSwitchRequest(
                key(shadow.generation()),
                ProjectionGeneration.FIRST,
                shadow.jobId(),
                ProjectionDefinitionVersion.V1,
                0,
                0,
                1,
                1);
        assertThrows(
                IllegalStateException.class,
                () -> lifecycle.switchGeneration(handler, staleSwitch));
        assertEquals(1L, activeGeneration());
        assertEquals("ACTIVE", generationStatus(ProjectionGeneration.FIRST));
        assertEquals("VALIDATING", generationStatus(shadow.generation()));

        ProjectionValidationOutcome secondValidation = lifecycle.validate(
                handler, validationRequest(shadow, 1, 1));
        ProjectionSwitchOutcome switched = lifecycle.switchGeneration(
                handler,
                new ProjectionSwitchRequest(
                        key(shadow.generation()),
                        ProjectionGeneration.FIRST,
                        shadow.jobId(),
                        ProjectionDefinitionVersion.V1,
                        0,
                        0,
                        secondValidation.generationVersion(),
                        secondValidation.jobVersion()));

        assertEquals(shadow.generation().value(), activeGeneration());
        assertEquals("RETIRED", generationStatus(ProjectionGeneration.FIRST));
        assertEquals("ACTIVE", generationStatus(shadow.generation()));
        assertEquals(1, switched.pointerVersion());
        assertEquals(4, switched.activeLease().fencingToken().value());
        assertThrows(
                IllegalStateException.class,
                () -> lifecycle.switchGeneration(handler, staleSwitch));

        UUID versionTwo = seedEvent(aggregateId, 2, BASE_TIME.plusSeconds(2), "v2");
        assertEquals(
                ProjectionConsumptionResult.LEASE_REJECTED,
                runner.consume(active, publication(versionTwo)));
        runner.consume(publication(versionTwo));
        assertEquals(2, rowCount(ProjectionGeneration.FIRST));
        assertEquals(3, rowCount(shadow.generation()));
    }

    @Test
    void historyReaderUsesStableBoundedKeysetAcrossAggregates() {
        bootstrapActive();
        UUID firstAggregate = UUID.randomUUID();
        UUID secondAggregate = UUID.randomUUID();
        seedEvent(firstAggregate, 0, BASE_TIME, "a0");
        seedEvent(secondAggregate, 0, BASE_TIME.plusMillis(1), "b0");
        seedEvent(firstAggregate, 1, BASE_TIME.plusMillis(2), "a1");
        Shadow shadow = startShadow();

        ProjectionReplayBatchResult first = replayer.replayPage(
                shadow.lease(), Optional.empty(), 2);
        ProjectionReplayBatchResult second = replayer.replayPage(
                shadow.lease(), first.nextCursor(), 2);
        ProjectionReplayBatchResult end = replayer.replayPage(
                shadow.lease(), second.nextCursor(), 2);

        assertEquals(2, first.scanned());
        assertEquals(1, second.scanned());
        assertTrue(end.caughtUp());
        assertEquals(3, rowCount(shadow.generation()));
        assertEquals(2, checkpointCount(shadow.generation()));
    }

    @Test
    void concurrentSwitchAttemptsCommitExactlyOnePointerChange() throws Exception {
        bootstrapActive();
        UUID eventId = seedEvent(UUID.randomUUID(), 0, BASE_TIME, "switch-race");
        runner.consume(publication(eventId));
        Shadow shadow = startShadow();
        replayAll(shadow.lease());
        ProjectionValidationOutcome validation = lifecycle.validate(
                handler, validationRequest(shadow, 0, 0));
        ProjectionSwitchRequest request = new ProjectionSwitchRequest(
                key(shadow.generation()),
                ProjectionGeneration.FIRST,
                shadow.jobId(),
                ProjectionDefinitionVersion.V1,
                0,
                0,
                validation.generationVersion(),
                validation.jobVersion());

        ExecutorService competitors = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = competitors.submit(() -> switchAfter(start, request));
            Future<Boolean> second = competitors.submit(() -> switchAfter(start, request));
            start.countDown();

            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertEquals(2L, activeGeneration());
            assertEquals(1L, jdbcTemplate.queryForObject(
                    """
                    SELECT version FROM crewscope.projection_pointer
                    WHERE organization_id = ? AND projection_name = ?
                    """,
                    Long.class,
                    organizationId.value(),
                    PROJECTION_NAME.value()));
        } finally {
            competitors.shutdownNow();
        }
    }

    private boolean switchAfter(CountDownLatch start, ProjectionSwitchRequest request) {
        try {
            start.await();
            lifecycle.switchGeneration(handler, request);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent switch was interrupted", exception);
        } catch (RuntimeException expectedConflict) {
            return false;
        }
    }

    private ProjectionGenerationLease bootstrapActive() {
        return bootstrapActive(ProjectionDefinitionVersion.V1);
    }

    private ProjectionGenerationLease bootstrapActive(
            ProjectionDefinitionVersion definitionVersion) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                        INSERT INTO crewscope.projection_generation (
                            organization_id, projection_name, generation, definition_version,
                            status, fencing_token, version, created_at, updated_at
                        ) VALUES (?, ?, 1, ?, 'ACTIVE', 1, 0, ?, ?)
                        """,
                    organizationId.value(),
                    PROJECTION_NAME.value(),
                    definitionVersion.value(),
                    BASE_TIME.atOffset(ZoneOffset.UTC),
                    BASE_TIME.atOffset(ZoneOffset.UTC));
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    """,
                    organizationId.value(),
                    PROJECTION_NAME.value(),
                    BASE_TIME.atOffset(ZoneOffset.UTC));
        });
        return new ProjectionGenerationLease(
                key(ProjectionGeneration.FIRST), ProjectionFencingToken.INITIAL);
    }

    private Shadow startShadow() {
        ProjectionGeneration generation = new ProjectionGeneration(2);
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
                    PROJECTION_NAME.value(),
                    jobId.value(),
                    BASE_TIME.plusSeconds(10).atOffset(ZoneOffset.UTC),
                    BASE_TIME.plusSeconds(10).atOffset(ZoneOffset.UTC));
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
                    PROJECTION_NAME.value(),
                    actorId.value(),
                    BASE_TIME.plusSeconds(10).atOffset(ZoneOffset.UTC),
                    BASE_TIME.plusSeconds(10).atOffset(ZoneOffset.UTC));
        });
        return new Shadow(
                generation,
                jobId,
                new ProjectionGenerationLease(key(generation), ProjectionFencingToken.INITIAL));
    }

    private ProjectionValidationRequest validationRequest(
            Shadow shadow, long generationVersion, long jobVersion) {
        return new ProjectionValidationRequest(
                key(shadow.generation()),
                shadow.jobId(),
                ProjectionDefinitionVersion.V1,
                generationVersion,
                jobVersion,
                actorId);
    }

    private void replayAll(ProjectionGenerationLease lease) {
        Optional<ProjectionHistoryCursor> cursor = Optional.empty();
        while (true) {
            ProjectionReplayBatchResult batch = replayer.replayPage(lease, cursor, 2);
            if (batch.caughtUp()) {
                return;
            }
            cursor = batch.nextCursor();
        }
    }

    private UUID seedEvent(
            UUID aggregateId, long aggregateVersion, Instant occurredAt, String marker) {
        UUID eventId = UUID.randomUUID();
        String partitionKey = organizationId + ":WORK_ITEM:" + aggregateId;
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id,
                    subject_type, subject_id, aggregate_version, actor_type,
                    correlation_id, occurred_at, payload
                ) VALUES (?, 'WORK_ITEM_CHANGED', '1', ?, 'WORK_ITEM', ?, ?,
                    'SERVICE', ?, ?, CAST(? AS JSONB))
                """,
                eventId,
                organizationId.value(),
                aggregateId,
                aggregateVersion,
                UUID.randomUUID(),
                occurredAt.atOffset(ZoneOffset.UTC),
                "{\"marker\":\"" + marker + "\"}");
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
                partitionKey,
                occurredAt.atOffset(ZoneOffset.UTC),
                occurredAt.atOffset(ZoneOffset.UTC));
        return eventId;
    }

    private EventPublication publication(UUID eventId) {
        return new JdbcProjectionEventHistoryStore(jdbcTemplate, objectMapper)
                .read(organizationId, Optional.empty(), 100)
                .events().stream()
                .map(ProjectionHistoryEvent::publication)
                .filter(event -> event.eventId().equals(eventId))
                .findFirst()
                .orElseThrow();
    }

    private ProjectionGenerationKey key(ProjectionGeneration generation) {
        return new ProjectionGenerationKey(organizationId, PROJECTION_NAME, generation);
    }

    private int rowCount(ProjectionGeneration generation) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.m6_e01_projection_row
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                Integer.class,
                organizationId.value(),
                PROJECTION_NAME.value(),
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
                PROJECTION_NAME.value(),
                generation.value());
    }

    private int receiptForEvent(ProjectionGeneration generation, UUID eventId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_consumer_receipt
                WHERE organization_id = ? AND projection_name = ?
                  AND generation = ? AND domain_event_id = ?
                """,
                Integer.class,
                organizationId.value(),
                PROJECTION_NAME.value(),
                generation.value(),
                eventId);
    }

    private int checkpointCount(ProjectionGeneration generation) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_generation_checkpoint
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                Integer.class,
                organizationId.value(),
                PROJECTION_NAME.value(),
                generation.value());
    }

    private long activeGeneration() {
        return jdbcTemplate.queryForObject(
                """
                SELECT active_generation FROM crewscope.projection_pointer
                WHERE organization_id = ? AND projection_name = ?
                """,
                Long.class,
                organizationId.value(),
                PROJECTION_NAME.value());
    }

    private String generationStatus(ProjectionGeneration generation) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status FROM crewscope.projection_generation
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                String.class,
                organizationId.value(),
                PROJECTION_NAME.value(),
                generation.value());
    }

    private final class ProbeProjectionHandler implements GenerationAwareProjectionHandler {

        private final Set<UUID> failures = new HashSet<>();

        @Override
        public ProjectionDefinition definition() {
            return DEFINITION;
        }

        @Override
        public void project(ProjectionGenerationLease lease, ProjectionEvent event) {
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.m6_e01_projection_row (
                        organization_id, projection_name, generation, domain_event_id,
                        partition_key, canonical_value, fencing_token
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    lease.key().organizationId().value(),
                    lease.key().projectionName().value(),
                    lease.key().generation().value(),
                    event.eventId(),
                    event.organizationId() + ":" + event.aggregateType() + ":" + event.aggregateId(),
                    canonical(event.eventId(), event.aggregateType(), event.aggregateId(),
                            event.aggregateVersion(), event.occurredAt()),
                    lease.fencingToken().value());
            if (failures.contains(event.eventId())) {
                throw new SimulatedProjectionFailure();
            }
        }

        @Override
        public ProjectionSnapshot expectedSnapshot(OrganizationId organizationId) {
            List<String> values = jdbcTemplate.query(
                    """
                    SELECT event_id, subject_type, subject_id, aggregate_version, occurred_at
                    FROM crewscope.domain_event
                    WHERE organization_id = ?
                    ORDER BY subject_type, subject_id, aggregate_version, occurred_at, event_id
                    """,
                    (resultSet, rowNumber) -> canonical(
                            resultSet.getObject("event_id", UUID.class),
                            resultSet.getString("subject_type"),
                            resultSet.getObject("subject_id", UUID.class),
                            resultSet.getLong("aggregate_version"),
                            UtcTimestamp.from(resultSet.getObject(
                                    "occurred_at", java.time.OffsetDateTime.class))),
                    organizationId.value());
            return snapshot(values);
        }

        @Override
        public ProjectionSnapshot actualSnapshot(ProjectionGenerationKey generationKey) {
            List<String> values = jdbcTemplate.queryForList(
                    """
                    SELECT canonical_value
                    FROM crewscope.m6_e01_projection_row
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                    ORDER BY canonical_value
                    """,
                    String.class,
                    generationKey.organizationId().value(),
                    generationKey.projectionName().value(),
                    generationKey.generation().value());
            return snapshot(values);
        }

        private void failOn(UUID eventId) {
            failures.add(eventId);
        }

        private void clearFailure(UUID eventId) {
            failures.remove(eventId);
        }
    }

    private static String canonical(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            UtcTimestamp occurredAt) {
        return eventId + "|" + aggregateType + "|" + aggregateId + "|"
                + aggregateVersion + "|" + occurredAt;
    }

    private static ProjectionSnapshot snapshot(List<String> source) {
        try {
            List<String> values = new ArrayList<>(source);
            values.sort(String::compareTo);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return new ProjectionSnapshot(
                    values.size(),
                    new ProjectionCanonicalHash(HexFormat.of().formatHex(digest.digest())),
                    0,
                    List.of());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Shadow(
            ProjectionGeneration generation,
            ProjectionRebuildJobId jobId,
            ProjectionGenerationLease lease) {}

    private static final class SimulatedProjectionFailure extends RuntimeException {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
