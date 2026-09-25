package io.crewscope.infrastructure.persistence.command;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
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
    public void saveResult(CommandResult result) {
        Objects.requireNonNull(result, "result");
        CommandReceipt receipt = result.receipt();
        int inserted = jdbcTemplate.update("""
                INSERT INTO crewscope.command_result (
                    organization_id, idempotency_key, command_id, actor_id, command_type,
                    team_id, project_id, resource_type, resource_id, resource_version, created_at
                )
                SELECT organization_id, idempotency_key, command_id, ?, command_type, ?, ?, ?, ?, ?, ?
                FROM crewscope.command_receipt
                WHERE organization_id = ? AND idempotency_key = ? AND command_id = ?
                  AND command_type = ? AND domain_event_id = ? AND committed_version = ?
                  AND correlation_id = ? AND status = 'COMPLETED'
                """, result.actorId().value(), result.teamId().value(),
                result.projectId().map(WorkProjectId::value).orElse(null),
                result.resourceType().name(), result.resourceId(), result.resourceVersion(),
                result.createdAt().toOffsetDateTime(), result.organizationId().value(),
                result.idempotencyKey().value(), receipt.commandId(), result.commandType(),
                receipt.domainEventId(), receipt.committedVersion(), receipt.correlationId());
        if (inserted != 1) throw new IllegalStateException("Command result needs its exact completed receipt");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommandResult> findResult(OrganizationId organizationId, IdempotencyKey key,
            PrincipalId actorId) {
        return jdbcTemplate.query("""
                SELECT r.*, c.domain_event_id, c.committed_version, c.correlation_id
                FROM crewscope.command_result r
                JOIN crewscope.command_receipt c
                  ON c.organization_id = r.organization_id AND c.idempotency_key = r.idempotency_key
                 AND c.command_id = r.command_id AND c.command_type = r.command_type
                WHERE r.organization_id = ? AND r.idempotency_key = ? AND r.actor_id = ?
                  AND c.status = 'COMPLETED'
                """, (rs, row) -> new CommandResult(
                    new OrganizationId(rs.getObject("organization_id", UUID.class)),
                    new IdempotencyKey(rs.getString("idempotency_key")),
                    new PrincipalId(rs.getObject("actor_id", UUID.class)), rs.getString("command_type"),
                    new TeamId(rs.getObject("team_id", UUID.class)),
                    Optional.ofNullable(rs.getObject("project_id", UUID.class)).map(WorkProjectId::new),
                    CommandResult.ResourceType.valueOf(rs.getString("resource_type")),
                    rs.getObject("resource_id", UUID.class), rs.getLong("resource_version"),
                    new CommandReceipt(rs.getObject("command_id", UUID.class),
                        rs.getObject("domain_event_id", UUID.class), rs.getLong("committed_version"),
                        rs.getObject("correlation_id", UUID.class)),
                    UtcTimestamp.from(rs.getTimestamp("created_at").toInstant())),
                organizationId.value(), key.value(), actorId.value()).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommandReceipt> findCompleted(
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            String commandType,
            io.crewscope.application.command.CommandRequestHash requestHash) {
        ExistingReservation existing = findOptional(
                        Objects.requireNonNull(organizationId, "organizationId"),
                        Objects.requireNonNull(idempotencyKey, "idempotencyKey"))
                .orElse(null);
        if (existing == null) {
            return Optional.empty();
        }
        String requiredType = Objects.requireNonNull(commandType, "commandType");
        String requiredHash = Objects.requireNonNull(requestHash, "requestHash").value();
        if (!existing.commandType().equals(requiredType)
                || !existing.requestHash().equals(requiredHash)) {
            throw new IdempotencyConflictException(
                    idempotencyKey.value(), existing.requestHash(), requiredHash);
        }
        if (existing.receipt() == null) {
            throw new IllegalStateException("A visible command reservation must be completed");
        }
        return Optional.of(existing.receipt());
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
        return findOptional(organizationId, key)
                .orElseThrow(() -> new IllegalStateException(
                        "Conflicting command reservation was not found"));
    }

    private Optional<ExistingReservation> findOptional(
            OrganizationId organizationId, IdempotencyKey key) {
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
        if (rows.size() > 1) {
            throw new IllegalStateException("Command reservation uniqueness was violated");
        }
        return rows.stream().findFirst();
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
