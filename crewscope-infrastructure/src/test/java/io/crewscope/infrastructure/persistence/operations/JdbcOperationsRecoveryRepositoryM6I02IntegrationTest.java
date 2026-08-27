package io.crewscope.infrastructure.persistence.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.audit.CrewScopeAuditEventTypes;
import io.crewscope.application.operations.OperationsRecoveryCommandId;
import io.crewscope.application.operations.OperationsRecoveryFingerprint;
import io.crewscope.application.operations.OperationsRecoveryRequest;
import io.crewscope.application.operations.OperationsRecoveryTarget;
import io.crewscope.application.operations.NotificationDeliveryRecoveryTarget;
import io.crewscope.application.operations.OutboxDeadLetterRecoveryTarget;
import io.crewscope.application.operations.ProjectionDeadLetterRecoveryTarget;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.projection.ProjectionDeadLetterId;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.event.projection.AuditEventProjector;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Atomic Audit/Outbox, exact-version and idempotency tests for M6-I02 recovery. */
@SpringBootTest(
        classes = JdbcOperationsRecoveryRepositoryM6I02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class JdbcOperationsRecoveryRepositoryM6I02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.from(
            Instant.parse("2026-08-26T08:00:00Z"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private PrincipalId principalId;
    private UUID sourceEventId;
    private UUID outboxId;
    private JdbcOperationsRecoveryRepositoryAdapter repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        principalId = PrincipalId.generate();
        sourceEventId = UUID.randomUUID();
        outboxId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Admin', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id,
                    subject_type, subject_id, aggregate_version, actor_type, actor_id,
                    correlation_id, occurred_at, payload
                ) VALUES (?, 'SOURCE_EVENT', '1', ?, 'WORK_ITEM', ?, 0, 'USER', ?, ?, ?, '{}'::JSONB)
                """,
                sourceEventId, organizationId.value(), UUID.randomUUID(), principalId.value(),
                UUID.randomUUID(), NOW.toOffsetDateTime());
        jdbc.update(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key, delivery_status,
                    retry_count, created_at, version, updated_at, last_error_code
                ) VALUES (?, ?, 'crewscope.domain-events.v1', 'partition',
                          'DEAD_LETTER', 5, ?, 3, ?, 'DELIVERY_FAILED')
                """,
                outboxId, sourceEventId, NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
        AuditEventProjector audit = new AuditEventProjector(
                jdbc, objectMapper, CrewScopeAuditEventTypes.reviewedRegistry());
        AtomicOperationsEventWriter writer = new AtomicOperationsEventWriter(
                jdbc,
                new JdbcDomainEventStore(jdbc, objectMapper),
                new JdbcOutboxRepository(jdbc),
                audit,
                objectMapper);
        repository = new JdbcOperationsRecoveryRepositoryAdapter(jdbc, writer);
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void schedulesOnceAndCommitsReceiptDomainEventOutboxAndAuditAtomically() {
        OperationsRecoveryCommandId commandId = OperationsRecoveryCommandId.generate();
        OperationsRecoveryRequest request = request(commandId, fingerprint('a'), 3);

        var first = transaction.execute(status -> repository.recover(request));
        var replay = transaction.execute(status -> repository.recover(request));

        assertEquals(first, replay);
        assertEquals(1, count("operations_recovery_schedule"));
        assertEquals(1, count("command_receipt"));
        assertEquals(2, count("domain_event"));
        assertEquals(1, count("audit_event"));
        assertEquals(2, count("outbox_event"));
        assertEquals("DEAD_LETTER", jdbc.queryForObject(
                "SELECT delivery_status FROM crewscope.outbox_event WHERE id = ?",
                String.class, outboxId));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT version FROM crewscope.outbox_event WHERE id = ?",
                Long.class, outboxId));
    }

    @Test
    void rejectsDifferentFingerprintAndStaleTargetWithoutAdditionalFacts() {
        OperationsRecoveryCommandId commandId = OperationsRecoveryCommandId.generate();
        transaction.execute(status -> repository.recover(request(commandId, fingerprint('a'), 3)));

        assertThrows(IdempotencyConflictException.class, () -> transaction.execute(
                status -> repository.recover(request(commandId, fingerprint('b'), 3))));
        assertThrows(IllegalStateException.class, () -> transaction.execute(
                status -> repository.recover(request(
                        OperationsRecoveryCommandId.generate(), fingerprint('c'), 2))));
        assertEquals(1, count("operations_recovery_schedule"));
        assertEquals(1, count("audit_event"));
    }

    @Test
    void schedulesProjectionAndNotificationRecoveryWithoutChangingFailureHistory() {
        ProjectionDeadLetterRecoveryTarget projection = seedProjectionDeadLetter();
        NotificationDeliveryRecoveryTarget notification = seedFailedNotificationDelivery();

        transaction.execute(status -> repository.recover(request(
                OperationsRecoveryCommandId.generate(), fingerprint('d'), projection)));
        transaction.execute(status -> repository.recover(request(
                OperationsRecoveryCommandId.generate(), fingerprint('e'), notification)));

        assertEquals(2, count("operations_recovery_schedule"));
        assertEquals(1, count("projection_dead_letter"));
        assertEquals("FAILED_FINAL", jdbc.queryForObject(
                "SELECT status FROM crewscope.notification_delivery WHERE delivery_id = ?",
                String.class, notification.deliveryId().value()));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM crewscope.notification_delivery WHERE delivery_id = ?",
                Long.class, notification.deliveryId().value()));
        List<String> actions = jdbc.queryForList(
                "SELECT recovery_action FROM crewscope.operations_recovery_schedule "
                        + "ORDER BY recovery_action",
                String.class);
        assertEquals(
                List.of("REPLAY_PROJECTION_DEAD_LETTER", "RETRY_NOTIFICATION_DELIVERY"),
                actions);
        assertThrows(DataAccessException.class, () -> jdbc.update(
                """
                UPDATE crewscope.operations_recovery_schedule
                SET projection_name = 'tampered', version = version + 1
                WHERE recovery_action = 'REPLAY_PROJECTION_DEAD_LETTER'
                """));
    }

    @Test
    void concurrentSameCommandAndFingerprintConvergesToOneSchedule() throws Exception {
        OperationsRecoveryCommandId commandId = OperationsRecoveryCommandId.generate();
        OperationsRecoveryRequest request = request(commandId, fingerprint('f'), 3);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return transaction.execute(status -> repository.recover(request));
            });
            var second = pool.submit(() -> {
                start.await();
                return transaction.execute(status -> repository.recover(request));
            });
            start.countDown();

            assertEquals(first.get(), second.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("operations_recovery_schedule"));
        assertEquals(1, count("command_receipt"));
        assertEquals(1, count("audit_event"));
    }

    private OperationsRecoveryRequest request(
            OperationsRecoveryCommandId commandId,
            OperationsRecoveryFingerprint fingerprint,
            long expectedVersion) {
        return request(
                commandId,
                fingerprint,
                new OutboxDeadLetterRecoveryTarget(outboxId, sourceEventId, expectedVersion));
    }

    private OperationsRecoveryRequest request(
            OperationsRecoveryCommandId commandId,
            OperationsRecoveryFingerprint fingerprint,
            OperationsRecoveryTarget target) {
        return new OperationsRecoveryRequest(
                commandId,
                organizationId,
                target,
                principalId,
                fingerprint,
                NOW);
    }

    private ProjectionDeadLetterRecoveryTarget seedProjectionDeadLetter() {
        // Keep the public Audit fixture deterministic and free from random digit runs that can
        // intentionally be classified as phone-like PII by the fail-closed summary boundary.
        ProjectionName name = new ProjectionName("operations-test-recovery");
        ProjectionDeadLetterId deadLetterId = ProjectionDeadLetterId.generate();
        transaction.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_definition (
                        projection_name, definition_version, projection_schema_version,
                        canonical_encoder, validator
                    ) VALUES (?, 1, 1, 'test.canonical-v1', 'test.validator-v1')
                    """,
                    name.value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 1, 1, 'ACTIVE', 1, 4, ?, ?)
                    """,
                    organizationId.value(), name.value(),
                    NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    """,
                    organizationId.value(), name.value(), NOW.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_dead_letter (
                        id, organization_id, projection_name, generation, domain_event_id,
                        partition_hash, failure_code, fencing_token, created_at
                    ) VALUES (?, ?, ?, 1, ?, ?, 'PROJECTION_FAILED', 1, ?)
                    """,
                    deadLetterId.value(), organizationId.value(), name.value(), sourceEventId,
                    "a".repeat(64), NOW.toOffsetDateTime());
        });
        return new ProjectionDeadLetterRecoveryTarget(
                name, ProjectionGeneration.FIRST, deadLetterId, sourceEventId, 4);
    }

    private NotificationDeliveryRecoveryTarget seedFailedNotificationDelivery() {
        UUID actionId = UUID.randomUUID();
        NotificationDeliveryId deliveryId = new NotificationDeliveryId(UUID.randomUUID());
        UUID receiptId = UUID.randomUUID();
        String actionDigest = "b".repeat(64);
        String deduplicationKey = "c".repeat(64);
        transaction.executeWithoutResult(status -> {
            // The recovery adapter only needs the immutable Delivery boundary. Provider graph
            // construction is covered by M6-E04/I01, so this fixture disables FK triggers locally.
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_planned_action (
                        organization_id, team_id, recipient_member_id,
                        projection_name, generation, action_id, intent_id,
                        source_identity_hash, template_id, template_version, variable_hash,
                        recipient_mapping_id, recipient_mapping_version,
                        provider_binding_id, provider_binding_version,
                        connection_id, connection_version,
                        connection_grant_id, connection_grant_version,
                        team_policy_id, team_policy_version, preference_version,
                        deduplication_key, authorization_digest, not_before, valid_until,
                        status, action_digest, version, created_at, updated_at
                    ) VALUES (
                        ?, ?, ?, 'member-inbox', 1, ?, ?, ?, ?, 1, ?, ?, 0, ?, 0,
                        ?, 0, ?, 0, ?, 0, 0, ?, ?, ?, ?, 'PLANNED', ?, 0, ?, ?
                    )
                    """,
                    organizationId.value(), UUID.randomUUID(), UUID.randomUUID(),
                    actionId, UUID.randomUUID(), "d".repeat(64), UUID.randomUUID(),
                    "e".repeat(64), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), deduplicationKey, "f".repeat(64),
                    NOW.toOffsetDateTime(),
                    NOW.value().plusSeconds(3_600).atOffset(ZoneOffset.UTC),
                    actionDigest, NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_delivery (
                        organization_id, delivery_id, action_id, action_digest,
                        deduplication_key, status, attempt_count, receipt_id,
                        version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'FAILED_FINAL', 3, ?, 2, ?, ?)
                    """,
                    organizationId.value(), deliveryId.value(), actionId, actionDigest,
                    deduplicationKey, receiptId, NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
            jdbc.execute("SET LOCAL session_replication_role = origin");
        });
        return new NotificationDeliveryRecoveryTarget(deliveryId, 2);
    }

    private static OperationsRecoveryFingerprint fingerprint(char value) {
        return new OperationsRecoveryFingerprint(String.valueOf(value).repeat(64));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
