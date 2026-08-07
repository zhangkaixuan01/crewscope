package io.crewscope.infrastructure.persistence.command;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter using one unique reservation row as the concurrent command gate. */
@Repository
public class JdbcCommandReceiptStore implements CommandReceiptStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommandReceiptStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CommandReservation reserve(CommandReservationRequest request) {
        CommandReservationRequest reservation = Objects.requireNonNull(request, "request");
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO crewscope.command_receipt (
                    organization_id, idempotency_key, command_type, request_hash,
                    command_id, correlation_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT (organization_id, idempotency_key) DO NOTHING
                """,
                reservation.organizationId().value(),
                reservation.idempotencyKey().value(),
                reservation.commandType(),
                reservation.requestHash().value(),
                reservation.commandId(),
                reservation.correlationId(),
                reservation.requestedAt().toOffsetDateTime(),
                reservation.requestedAt().toOffsetDateTime());
        if (inserted == 1) {
            return CommandReservation.newlyAcquired();
        }

        ExistingReservation existing = find(reservation.organizationId(), reservation.idempotencyKey());
        if (!existing.commandType().equals(reservation.commandType())
                || !existing.requestHash().equals(reservation.requestHash().value())) {
            throw new IdempotencyConflictException(
                    reservation.idempotencyKey().value(),
                    existing.requestHash(),
                    reservation.requestHash().value());
        }
        if (existing.receipt() == null) {
            throw new IllegalStateException("A visible command reservation must be completed");
        }
        return CommandReservation.replay(existing.receipt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            CommandReceipt receipt,
            UtcTimestamp completedAt) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        IdempotencyKey key = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        CommandReceipt completion = Objects.requireNonNull(receipt, "receipt");
        UtcTimestamp time = Objects.requireNonNull(completedAt, "completedAt");
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.command_receipt
                SET domain_event_id = ?, committed_version = ?, status = 'COMPLETED', updated_at = ?
                WHERE organization_id = ? AND idempotency_key = ?
                  AND command_id = ? AND correlation_id = ? AND status = 'PENDING'
                """,
                completion.domainEventId(),
                completion.committedVersion(),
                time.toOffsetDateTime(),
                organization.value(),
                key.value(),
                completion.commandId(),
                completion.correlationId());
        if (updated != 1) {
            throw new IllegalStateException("Command reservation could not be completed");
        }
    }

    private ExistingReservation find(OrganizationId organizationId, IdempotencyKey key) {
        List<ExistingReservation> rows = jdbcTemplate.query(
                """
                SELECT command_type, request_hash, command_id, domain_event_id,
                       committed_version, correlation_id, status
                FROM crewscope.command_receipt
                WHERE organization_id = ? AND idempotency_key = ?
                """,
                this::mapRow,
                organizationId.value(),
                key.value());
        if (rows.size() != 1) {
            throw new IllegalStateException("Conflicting command reservation was not found");
        }
        return rows.get(0);
    }

    private ExistingReservation mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String status = resultSet.getString("status");
        CommandReceipt receipt = null;
        if ("COMPLETED".equals(status)) {
            receipt = new CommandReceipt(
                    resultSet.getObject("command_id", UUID.class),
                    resultSet.getObject("domain_event_id", UUID.class),
                    resultSet.getLong("committed_version"),
                    resultSet.getObject("correlation_id", UUID.class));
        } else if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Command reservation status is invalid");
        }
        return new ExistingReservation(
                resultSet.getString("command_type"),
                resultSet.getString("request_hash"),
                receipt);
    }

    private record ExistingReservation(
            String commandType, String requestHash, CommandReceipt receipt) {}
}
