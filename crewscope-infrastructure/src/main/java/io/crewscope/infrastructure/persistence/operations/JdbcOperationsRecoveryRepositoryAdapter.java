package io.crewscope.infrastructure.persistence.operations;

import io.crewscope.application.operations.*;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for closed-set, strongly versioned and append-preserving recovery requests. */
@Repository
public class JdbcOperationsRecoveryRepositoryAdapter implements OperationsRecoveryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicOperationsEventWriter eventWriter;

    public JdbcOperationsRecoveryRepositoryAdapter(
            JdbcTemplate jdbcTemplate, AtomicOperationsEventWriter eventWriter) {
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.eventWriter = java.util.Objects.requireNonNull(eventWriter, "eventWriter");
    }

    @Override
    public Optional<OperationsRecoveryReceipt> findReceipt(
            OrganizationId organizationId, OperationsRecoveryCommandId commandId) {
        List<OperationsRecoveryReceipt> rows = jdbcTemplate.query(
                """
                SELECT request_fingerprint, recovery_action, target_reference_hash,
                       status, accepted_at
                FROM crewscope.operations_recovery_schedule
                WHERE organization_id = ? AND command_id = ?
                """,
                (rs, row) -> mapReceipt(organizationId, commandId, rs),
                organizationId.value(), commandId.value());
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OperationsRecoveryReceipt recover(OperationsRecoveryRequest request) {
        OperationsRecoveryRequest required = java.util.Objects.requireNonNull(request, "request");
        // The transaction-scoped lock serializes the read-after-miss race without persisting a
        // PENDING receipt that could survive independently of the recovery fact.
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class,
                required.organizationId().value() + ":" + required.commandId().value());
        Optional<OperationsRecoveryReceipt> raced = findReceipt(
                required.organizationId(), required.commandId());
        if (raced.isPresent()) {
            OperationsRecoveryReceipt existing = raced.orElseThrow();
            existing.replay(required.fingerprint());
            return existing;
        }

        LockedTarget target = lockTarget(required.organizationId(), required.target());
        UUID scheduleId = UUID.randomUUID();
        RecoveryEvent recoveryEvent = recoveryEvent(required, scheduleId);
        UUID auditEventId = eventWriter.append(
                recoveryEvent.eventType(),
                "OPERATIONS_RECOVERY_COMMAND",
                required.commandId().value(),
                required.organizationId(),
                required.actorId(),
                required.occurredAt(),
                recoveryEvent.payload());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.operations_recovery_schedule (
                    organization_id, schedule_id, command_id, request_fingerprint,
                    recovery_action, target_reference_hash, projection_name, generation,
                    target_id, domain_event_id, expected_version, status,
                    audit_domain_event_id, accepted_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0)
                """,
                required.organizationId().value(), scheduleId, required.commandId().value(),
                required.fingerprint().value(), required.target().action().name(),
                required.target().referenceHash(), target.projectionName(), target.generation(),
                target.targetId(), target.domainEventId(), target.expectedVersion(),
                auditEventId, required.occurredAt().toOffsetDateTime());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.command_receipt (
                    organization_id, idempotency_key, command_type, request_hash,
                    command_id, domain_event_id, committed_version, correlation_id,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, 'COMPLETED', ?, ?)
                """,
                required.organizationId().value(),
                "operations-recovery:" + required.commandId().value(),
                required.target().action().name(), required.fingerprint().value(),
                required.commandId().value(), auditEventId, required.commandId().value(),
                required.occurredAt().toOffsetDateTime(),
                required.occurredAt().toOffsetDateTime());
        return new OperationsRecoveryReceipt(
                required.commandId(), required.organizationId(), required.fingerprint(),
                new OperationsRecoveryResult(
                        required.target().action(), required.target().referenceHash(),
                        OperationsRecoveryStatus.SCHEDULED, required.occurredAt()));
    }

    private LockedTarget lockTarget(
            OrganizationId organizationId, OperationsRecoveryTarget target) {
        if (target instanceof OutboxDeadLetterRecoveryTarget outbox) {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT id, domain_event_id, version, delivery_status
                    FROM crewscope.outbox_event
                    WHERE id = ? AND domain_event_id = ? FOR UPDATE
                    """,
                    (rs, row) -> {
                        requireStatus("Outbox", "DEAD_LETTER", rs.getString("delivery_status"));
                        requireVersion("Outbox", outbox.expectedVersion(), rs.getLong("version"));
                        UUID eventId = rs.getObject("domain_event_id", UUID.class);
                        Integer scoped = jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM crewscope.domain_event
                                WHERE organization_id = ? AND event_id = ?
                                """,
                                Integer.class, organizationId.value(), eventId);
                        if (scoped == null || scoped != 1) {
                            throw new IllegalArgumentException("Outbox target belongs to another Organization");
                        }
                        return new LockedTarget(null, null, outbox.outboxEventId(), eventId,
                                outbox.expectedVersion());
                    },
                    outbox.outboxEventId(), outbox.domainEventId());
        }
        if (target instanceof ProjectionDeadLetterRecoveryTarget projection) {
            Long generationVersion = jdbcTemplate.queryForObject(
                    """
                    SELECT version FROM crewscope.projection_generation
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                      AND status IN ('ACTIVE', 'BUILDING', 'VALIDATING')
                    FOR UPDATE
                    """,
                    Long.class, organizationId.value(), projection.projectionName().value(),
                    projection.generation().value());
            if (generationVersion == null) {
                throw new IllegalArgumentException("Writable Projection Generation was not found");
            }
            requireVersion("Projection Generation", projection.expectedGenerationVersion(),
                    generationVersion);
            UUID deadLetter = jdbcTemplate.queryForObject(
                    """
                    SELECT id FROM crewscope.projection_dead_letter
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                      AND id = ? AND domain_event_id = ? FOR UPDATE
                    """,
                    UUID.class, organizationId.value(), projection.projectionName().value(),
                    projection.generation().value(), projection.deadLetterId().value(),
                    projection.domainEventId());
            if (deadLetter == null) {
                throw new IllegalArgumentException("Projection Dead Letter was not found");
            }
            return new LockedTarget(
                    projection.projectionName().value(), projection.generation().value(),
                    deadLetter, projection.domainEventId(), projection.expectedGenerationVersion());
        }
        NotificationDeliveryRecoveryTarget notification =
                (NotificationDeliveryRecoveryTarget) target;
        return jdbcTemplate.queryForObject(
                """
                SELECT delivery_id, version, status
                FROM crewscope.notification_delivery
                WHERE organization_id = ? AND delivery_id = ? FOR UPDATE
                """,
                (rs, row) -> {
                    requireStatus("Notification Delivery", "FAILED_FINAL", rs.getString("status"));
                    requireVersion("Notification Delivery", notification.expectedVersion(),
                            rs.getLong("version"));
                    return new LockedTarget(null, null,
                            rs.getObject("delivery_id", UUID.class), null,
                            notification.expectedVersion());
                },
                organizationId.value(), notification.deliveryId().value());
    }

    private static RecoveryEvent recoveryEvent(
            OperationsRecoveryRequest request, UUID scheduleId) {
        OperationsRecoveryTarget target = request.target();
        if (target instanceof OutboxDeadLetterRecoveryTarget outbox) {
            return new RecoveryEvent(
                    "OUTBOX_DEAD_LETTER_REPLAY_REQUESTED",
                    new OutboxReplayRequested(
                            request.commandId().value(), outbox.outboxEventId(),
                            outbox.domainEventId(), outbox.expectedVersion(),
                            target.action().name(), OperationsRecoveryStatus.SCHEDULED.name()));
        }
        if (target instanceof ProjectionDeadLetterRecoveryTarget projection) {
            return new RecoveryEvent(
                    "PROJECTION_DEAD_LETTER_REPLAY_REQUESTED",
                    new ProjectionReplayRequested(
                            request.commandId().value(), projection.projectionName().value(),
                            projection.generation().value(), projection.deadLetterId().value(),
                            projection.domainEventId(), projection.expectedGenerationVersion(),
                            target.action().name(), OperationsRecoveryStatus.SCHEDULED.name()));
        }
        NotificationDeliveryRecoveryTarget notification =
                (NotificationDeliveryRecoveryTarget) target;
        return new RecoveryEvent(
                "NOTIFICATION_REDELIVERY_REQUESTED",
                new NotificationRedeliveryRequested(
                        notification.deliveryId().value(), scheduleId, "ADMIN_REDELIVERY"));
    }

    private static OperationsRecoveryReceipt mapReceipt(
            OrganizationId organizationId,
            OperationsRecoveryCommandId commandId,
            ResultSet rs) throws SQLException {
        return new OperationsRecoveryReceipt(
                commandId,
                organizationId,
                new OperationsRecoveryFingerprint(rs.getString("request_fingerprint")),
                new OperationsRecoveryResult(
                        OperationsRecoveryAction.valueOf(rs.getString("recovery_action")),
                        rs.getString("target_reference_hash"),
                        OperationsRecoveryStatus.SCHEDULED,
                        UtcTimestamp.from(rs.getObject("accepted_at", OffsetDateTime.class))));
    }

    private static void requireVersion(String type, long expected, long actual) {
        if (expected != actual) {
            throw new IllegalStateException(
                    type + " version conflict: expected " + expected + ", actual " + actual);
        }
    }

    private static void requireStatus(String type, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(type + " is not recoverable from status " + actual);
        }
    }

    private record LockedTarget(
            String projectionName,
            Long generation,
            UUID targetId,
            UUID domainEventId,
            long expectedVersion) {}

    private record RecoveryEvent(String eventType, DomainEvent payload) {}

    private record OutboxReplayRequested(
            UUID commandId,
            UUID outboxEventId,
            UUID domainEventId,
            long expectedVersion,
            String recoveryAction,
            String status) implements DomainEvent {}

    private record ProjectionReplayRequested(
            UUID commandId,
            String projectionName,
            long generation,
            UUID deadLetterId,
            UUID domainEventId,
            long expectedGenerationVersion,
            String recoveryAction,
            String status) implements DomainEvent {}

    private record NotificationRedeliveryRequested(
            UUID originalDeliveryId,
            UUID replacementDeliveryId,
            String reasonCode) implements DomainEvent {}
}
