package io.crewscope.infrastructure.persistence.notification;

import io.crewscope.application.notification.NotificationRecoveryClaim;
import io.crewscope.application.notification.NotificationRecoveryScheduleRepository;
import io.crewscope.application.notification.NotificationWorkerId;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL queue adapter for audited manual notification redelivery schedules. */
@Repository
public class JdbcNotificationRecoveryScheduleRepository
        implements NotificationRecoveryScheduleRepository {

    private final JdbcTemplate jdbc;

    public JdbcNotificationRecoveryScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationId> findOrganizations(UtcTimestamp now, int limit) {
        return jdbc.query(
                """
                SELECT DISTINCT organization_id
                FROM crewscope.operations_recovery_schedule
                WHERE recovery_action = 'RETRY_NOTIFICATION_DELIVERY'
                  AND (status = 'PENDING'
                       OR (status = 'CLAIMED' AND lease_expires_at <= ?))
                ORDER BY organization_id
                LIMIT ?
                """,
                (row, ignored) -> new OrganizationId(
                        row.getObject("organization_id", UUID.class)),
                now.toOffsetDateTime(), limit);
    }

    @Override
    @Transactional
    public Optional<NotificationRecoveryClaim> claim(
            OrganizationId organizationId,
            NotificationWorkerId workerId,
            UtcTimestamp now,
            Duration leaseDuration) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        List<Row> rows = jdbc.query(
                """
                SELECT schedule_id, command_id, target_id, expected_version,
                       claim_token, version
                FROM crewscope.operations_recovery_schedule
                WHERE organization_id = ?
                  AND recovery_action = 'RETRY_NOTIFICATION_DELIVERY'
                  AND (status = 'PENDING'
                       OR (status = 'CLAIMED' AND lease_expires_at <= ?))
                ORDER BY accepted_at, schedule_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """,
                (row, ignored) -> new Row(
                        row.getObject("schedule_id", UUID.class),
                        row.getObject("command_id", UUID.class),
                        row.getObject("target_id", UUID.class),
                        row.getLong("expected_version"), row.getLong("claim_token"),
                        row.getLong("version")),
                organization.value(), now.toOffsetDateTime());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row row = rows.get(0);
        long token = row.claimToken() + 1;
        UtcTimestamp leaseUntil = UtcTimestamp.from(now.value().plus(leaseDuration));
        int updated = jdbc.update(
                """
                UPDATE crewscope.operations_recovery_schedule
                SET status = 'CLAIMED', claimed_by = ?, claim_token = ?,
                    lease_expires_at = ?, version = version + 1
                WHERE organization_id = ? AND schedule_id = ? AND version = ?
                  AND claim_token = ?
                """,
                workerId.value(), token, leaseUntil.toOffsetDateTime(), organization.value(),
                row.scheduleId(), row.version(), row.claimToken());
        if (updated != 1) {
            throw conflict(organization, row.scheduleId(), row.version());
        }
        return Optional.of(new NotificationRecoveryClaim(
                organization, row.scheduleId(), new NotificationRedeliveryCommandId(row.commandId()),
                new NotificationDeliveryId(row.targetId()), row.expectedVersion(), workerId,
                token, leaseUntil));
    }

    @Override
    @Transactional
    public void complete(
            NotificationRecoveryClaim claim,
            NotificationDeliveryId replacementDeliveryId,
            UtcTimestamp now) {
        NotificationRecoveryClaim value = Objects.requireNonNull(claim, "claim");
        int updated = jdbc.update(
                """
                UPDATE crewscope.operations_recovery_schedule
                SET status = 'COMPLETED', replacement_delivery_id = ?, completed_at = ?,
                    claimed_by = NULL, lease_expires_at = NULL, version = version + 1
                WHERE organization_id = ? AND schedule_id = ? AND status = 'CLAIMED'
                  AND claimed_by = ? AND claim_token = ? AND lease_expires_at > ?
                """,
                Objects.requireNonNull(replacementDeliveryId, "replacementDeliveryId").value(),
                now.toOffsetDateTime(), value.organizationId().value(), value.scheduleId(),
                value.workerId().value(), value.fencingToken(), now.toOffsetDateTime());
        if (updated != 1) {
            throw conflict(value.organizationId(), value.scheduleId(), 0);
        }
    }

    private OptimisticLockConflictException conflict(
            OrganizationId organization, UUID scheduleId, long expectedVersion) {
        Long actual = jdbc.query(
                """
                SELECT version FROM crewscope.operations_recovery_schedule
                WHERE organization_id = ? AND schedule_id = ?
                """,
                result -> result.next() ? result.getLong("version") : null,
                organization.value(), scheduleId);
        return new OptimisticLockConflictException(
                "NotificationRecoverySchedule",
                new NotificationDeliveryId(scheduleId), expectedVersion,
                actual == null ? 0 : actual);
    }

    private record Row(
            UUID scheduleId,
            UUID commandId,
            UUID targetId,
            long expectedVersion,
            long claimToken,
            long version) {}
}
