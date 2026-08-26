package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable claim, startup recovery, cursor and retention store for Projection Supervisor. */
@Repository
public class JdbcProjectionSupervisorStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;

    public JdbcProjectionSupervisorStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setName("crewscope-projection-supervisor-store");
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Seeds distinct ONLINE/SHADOW claim coordinates and atomically takes bounded shadow work. */
    public List<ProjectionSupervisorClaim> claim(
            String ownerId, UtcTimestamp now, Duration leaseDuration, int limit) {
        String owner = requireOwner(ownerId);
        UtcTimestamp current = Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        List<ProjectionSupervisorClaim> result = transaction.execute(status -> {
            seedClaims(current);
            return jdbcTemplate.query(
                    """
                    WITH candidates AS (
                        SELECT claim.organization_id, claim.projection_name,
                               claim.generation, claim.worker_role
                        FROM crewscope.projection_worker_claim claim
                        JOIN crewscope.projection_generation generation
                          ON generation.organization_id = claim.organization_id
                         AND generation.projection_name = claim.projection_name
                         AND generation.generation = claim.generation
                        WHERE claim.worker_role = 'SHADOW'
                          AND generation.status IN ('BUILDING', 'VALIDATING')
                          AND (
                              claim.status IN ('IDLE', 'INTERRUPTED')
                              OR (claim.status = 'RUNNING' AND claim.lease_expires_at <= ?)
                          )
                        ORDER BY claim.updated_at, claim.organization_id,
                                 claim.projection_name, claim.generation
                        FOR UPDATE OF claim SKIP LOCKED
                        LIMIT ?
                    ), claimed AS (
                        UPDATE crewscope.projection_worker_claim claim
                        SET owner_id = ?, status = 'RUNNING',
                            fencing_token = claim.fencing_token + 1,
                            lease_expires_at = ?, heartbeat_at = ?,
                            version = claim.version + 1, updated_at = ?
                        FROM candidates
                        WHERE claim.organization_id = candidates.organization_id
                          AND claim.projection_name = candidates.projection_name
                          AND claim.generation = candidates.generation
                          AND claim.worker_role = candidates.worker_role
                        RETURNING claim.*
                    )
                    SELECT claimed.*, generation.fencing_token AS generation_fencing_token
                    FROM claimed
                    JOIN crewscope.projection_generation generation
                      ON generation.organization_id = claimed.organization_id
                     AND generation.projection_name = claimed.projection_name
                     AND generation.generation = claimed.generation
                    ORDER BY claimed.organization_id, claimed.projection_name, claimed.generation
                    """,
                    (rs, row) -> new ProjectionSupervisorClaim(
                            new ProjectionGenerationKey(
                                    new OrganizationId(rs.getObject("organization_id", UUID.class)),
                                    new ProjectionName(rs.getString("projection_name")),
                                    new ProjectionGeneration(rs.getLong("generation"))),
                            new ProjectionFencingToken(rs.getLong("generation_fencing_token")),
                            rs.getString("owner_id"),
                            rs.getLong("fencing_token"),
                            cursor(rs.getString("cursor_aggregate_type"),
                                    rs.getObject("cursor_aggregate_id", UUID.class),
                                    (Long) rs.getObject("cursor_aggregate_version"),
                                    rs.getObject("cursor_occurred_at", OffsetDateTime.class),
                                    rs.getObject("cursor_event_id", UUID.class))),
                    current.toOffsetDateTime(), limit, owner,
                    current.value().plus(lease).atOffset(java.time.ZoneOffset.UTC),
                    current.toOffsetDateTime(), current.toOffsetDateTime());
        });
        return result == null ? List.of() : List.copyOf(result);
    }

    /** Saves one completed page only while both Worker and Generation fences remain current. */
    public boolean saveProgress(
            ProjectionSupervisorClaim claim,
            Optional<ProjectionHistoryCursor> cursor,
            boolean caughtUp,
            UtcTimestamp now,
            Duration leaseDuration) {
        ProjectionSupervisorClaim owned = Objects.requireNonNull(claim, "claim");
        Optional<ProjectionHistoryCursor> next = Objects.requireNonNull(cursor, "cursor");
        UtcTimestamp current = Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        ProjectionHistoryCursor value = next.orElse(null);
        Integer updated = transaction.execute(status -> jdbcTemplate.update(
                """
                UPDATE crewscope.projection_worker_claim claim
                SET owner_id = ?, status = ?, lease_expires_at = ?, heartbeat_at = ?,
                    cursor_aggregate_type = ?, cursor_aggregate_id = ?,
                    cursor_aggregate_version = ?, cursor_occurred_at = ?, cursor_event_id = ?,
                    version = claim.version + 1, updated_at = ?
                WHERE claim.organization_id = ? AND claim.projection_name = ?
                  AND claim.generation = ? AND claim.worker_role = 'SHADOW'
                  AND claim.owner_id = ? AND claim.fencing_token = ?
                  AND claim.status = 'RUNNING' AND claim.lease_expires_at > ?
                  AND EXISTS (
                      SELECT 1 FROM crewscope.projection_generation generation
                      WHERE generation.organization_id = claim.organization_id
                        AND generation.projection_name = claim.projection_name
                        AND generation.generation = claim.generation
                        AND generation.status IN ('BUILDING', 'VALIDATING')
                        AND generation.fencing_token = ?
                  )
                """,
                caughtUp ? null : owned.ownerId(),
                caughtUp ? "CAUGHT_UP" : "RUNNING",
                caughtUp ? null
                        : current.value().plus(lease).atOffset(java.time.ZoneOffset.UTC),
                caughtUp ? null : current.toOffsetDateTime(),
                value == null ? null : value.aggregateType(),
                value == null ? null : value.aggregateId(),
                value == null ? null : value.aggregateVersion(),
                value == null ? null : value.occurredAt().toOffsetDateTime(),
                value == null ? null : value.eventId(),
                current.toOffsetDateTime(),
                owned.generationKey().organizationId().value(),
                owned.generationKey().projectionName().value(),
                owned.generationKey().generation().value(),
                owned.ownerId(), owned.workerFencingToken(), current.toOffsetDateTime(),
                owned.generationFencingToken().value()));
        return updated != null && updated == 1;
    }

    /** Marks this instance's live work interruptible; another instance receives a higher fence. */
    public int interruptOwned(String ownerId, UtcTimestamp now) {
        String owner = requireOwner(ownerId);
        UtcTimestamp current = Objects.requireNonNull(now, "now");
        Integer updated = transaction.execute(status -> jdbcTemplate.update(
                """
                UPDATE crewscope.projection_worker_claim
                SET owner_id = NULL, status = 'INTERRUPTED',
                    lease_expires_at = NULL, heartbeat_at = NULL,
                    version = version + 1, updated_at = ?
                WHERE worker_role = 'SHADOW' AND owner_id = ? AND status = 'RUNNING'
                """,
                current.toOffsetDateTime(), owner));
        return updated == null ? 0 : updated;
    }

    public boolean interrupt(ProjectionSupervisorClaim claim, UtcTimestamp now) {
        ProjectionSupervisorClaim owned = Objects.requireNonNull(claim, "claim");
        UtcTimestamp current = Objects.requireNonNull(now, "now");
        Integer updated = transaction.execute(status -> jdbcTemplate.update(
                """
                UPDATE crewscope.projection_worker_claim
                SET owner_id = NULL, status = 'INTERRUPTED',
                    lease_expires_at = NULL, heartbeat_at = NULL,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                  AND worker_role = 'SHADOW' AND owner_id = ?
                  AND fencing_token = ? AND status = 'RUNNING'
                """,
                current.toOffsetDateTime(), owned.generationKey().organizationId().value(),
                owned.generationKey().projectionName().value(),
                owned.generationKey().generation().value(), owned.ownerId(),
                owned.workerFencingToken()));
        return updated != null && updated == 1;
    }

    /** Turns orphaned RUNNING rows into explicit startup-recovery work without taking ownership. */
    public int recoverExpired(UtcTimestamp now) {
        UtcTimestamp current = Objects.requireNonNull(now, "now");
        Integer updated = transaction.execute(status -> jdbcTemplate.update(
                """
                UPDATE crewscope.projection_worker_claim
                SET owner_id = NULL, status = 'INTERRUPTED',
                    lease_expires_at = NULL, heartbeat_at = NULL,
                    version = version + 1, updated_at = ?
                WHERE status = 'RUNNING' AND lease_expires_at <= ?
                """,
                current.toOffsetDateTime(), current.toOffsetDateTime()));
        return updated == null ? 0 : updated;
    }

    public ProjectionSupervisorSummary summary(UtcTimestamp now, Duration retention) {
        UtcTimestamp current = Objects.requireNonNull(now, "now");
        Duration window = requirePositive(retention, "retention");
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) FILTER (WHERE claim.status = 'RUNNING') AS running,
                    COUNT(*) FILTER (WHERE claim.status = 'CAUGHT_UP') AS caught_up,
                    COUNT(*) FILTER (WHERE claim.status = 'INTERRUPTED') AS interrupted,
                    COUNT(*) FILTER (WHERE claim.status = 'RUNNING'
                        AND claim.lease_expires_at <= ?) AS expired,
                    (SELECT COUNT(*) FROM crewscope.operations_recovery_schedule
                        WHERE status IN ('PENDING', 'CLAIMED')) AS pending_recovery,
                    (SELECT COUNT(*) FROM crewscope.projection_generation generation
                        WHERE generation.status IN ('RETIRED', 'FAILED', 'CANCELLED')
                          AND generation.updated_at < ?
                          AND NOT EXISTS (
                              SELECT 1 FROM crewscope.projection_pointer pointer
                              WHERE pointer.organization_id = generation.organization_id
                                AND pointer.projection_name = generation.projection_name
                                AND pointer.active_generation = generation.generation
                          )) AS cleanup_eligible
                FROM crewscope.projection_worker_claim claim
                """,
                (rs, row) -> new ProjectionSupervisorSummary(
                        rs.getLong("running"), rs.getLong("caught_up"),
                        rs.getLong("interrupted"), rs.getLong("expired"),
                        rs.getLong("pending_recovery"), rs.getLong("cleanup_eligible")),
                current.toOffsetDateTime(),
                current.value().minus(window).atOffset(java.time.ZoneOffset.UTC));
    }

    /**
     * Cleans only generation-owned replay/runtime rows and replaceable Activity/Inbox rows. Audit,
     * DomainEvent, Inbox Disposition and immutable Notification history are deliberately excluded.
     */
    public int cleanupDue(UtcTimestamp now, Duration retention, int limit) {
        UtcTimestamp current = Objects.requireNonNull(now, "now");
        Duration window = requirePositive(retention, "retention");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Integer cleaned = transaction.execute(status -> {
            List<TerminalGeneration> candidates = jdbcTemplate.query(
                    """
                    SELECT generation.organization_id, generation.projection_name,
                           generation.generation, generation.status
                    FROM crewscope.projection_generation generation
                    WHERE generation.status IN ('RETIRED', 'FAILED', 'CANCELLED')
                      AND generation.updated_at < ?
                      AND NOT EXISTS (
                          SELECT 1 FROM crewscope.projection_pointer pointer
                          WHERE pointer.organization_id = generation.organization_id
                            AND pointer.projection_name = generation.projection_name
                            AND pointer.active_generation = generation.generation
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM crewscope.projection_worker_claim claim
                          WHERE claim.organization_id = generation.organization_id
                            AND claim.projection_name = generation.projection_name
                            AND claim.generation = generation.generation
                            AND claim.status = 'RUNNING' AND claim.lease_expires_at > ?
                      )
                    ORDER BY generation.updated_at
                    FOR UPDATE SKIP LOCKED LIMIT ?
                    """,
                    (rs, row) -> new TerminalGeneration(
                            rs.getObject("organization_id", UUID.class),
                            rs.getString("projection_name"), rs.getLong("generation"),
                            rs.getString("status")),
                    current.value().minus(window).atOffset(java.time.ZoneOffset.UTC),
                    current.toOffsetDateTime(), limit);
            for (TerminalGeneration candidate : candidates) {
                long rows = cleanupProjectionRows(candidate);
                rows += deleteRuntimeRows(candidate);
                jdbcTemplate.update(
                        """
                        INSERT INTO crewscope.projection_cleanup_receipt (
                            organization_id, cleanup_id, projection_name, generation,
                            terminal_status, deleted_row_count, cleaned_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        candidate.organizationId(), UUID.randomUUID(), candidate.projectionName(),
                        candidate.generation(), candidate.status(), rows,
                        current.toOffsetDateTime());
            }
            return candidates.size();
        });
        return cleaned == null ? 0 : cleaned;
    }

    private void seedClaims(UtcTimestamp now) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_worker_claim (
                    organization_id, projection_name, generation, worker_role,
                    fencing_token, status, version, created_at, updated_at
                )
                SELECT organization_id, projection_name, generation,
                       CASE status WHEN 'ACTIVE' THEN 'ONLINE' ELSE 'SHADOW' END,
                       0, 'IDLE', 0, ?, ?
                FROM crewscope.projection_generation
                WHERE status IN ('ACTIVE', 'BUILDING', 'VALIDATING')
                ON CONFLICT (organization_id, projection_name, generation, worker_role) DO NOTHING
                """,
                now.toOffsetDateTime(), now.toOffsetDateTime());
    }

    private long cleanupProjectionRows(TerminalGeneration value) {
        long rows = 0;
        if ("team-activity".equals(value.projectionName())) {
            rows += jdbcTemplate.update(
                    """
                    DELETE FROM crewscope.activity_reference
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                    """,
                    value.organizationId(), value.projectionName(), value.generation());
            rows += jdbcTemplate.update(
                    """
                    DELETE FROM crewscope.activity_event
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                    """,
                    value.organizationId(), value.projectionName(), value.generation());
        } else if ("member-inbox".equals(value.projectionName())) {
            // Intents/actions/deliveries are immutable external-action evidence and protect their
            // source Inbox rows from retention deletion.
            rows += jdbcTemplate.update(
                    """
                    DELETE FROM crewscope.inbox_item item
                    WHERE item.organization_id = ? AND item.projection_name = ?
                      AND item.generation = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM crewscope.notification_intent intent
                          WHERE intent.organization_id = item.organization_id
                            AND intent.projection_name = item.projection_name
                            AND intent.generation = item.generation
                            AND intent.inbox_item_id = item.inbox_item_id
                      )
                    """,
                    value.organizationId(), value.projectionName(), value.generation());
        }
        return rows;
    }

    private long deleteRuntimeRows(TerminalGeneration value) {
        long rows = 0;
        rows += delete("projection_dead_letter", value);
        rows += delete("projection_generation_checkpoint", value);
        rows += delete("projection_consumer_receipt", value);
        rows += delete("projection_worker_claim", value);
        return rows;
    }

    private int delete(String table, TerminalGeneration value) {
        // Table names are a closed code-owned set; all scope values remain bound parameters.
        return jdbcTemplate.update(
                "DELETE FROM crewscope." + table
                        + " WHERE organization_id = ? AND projection_name = ? AND generation = ?",
                value.organizationId(), value.projectionName(), value.generation());
    }

    private static Optional<ProjectionHistoryCursor> cursor(
            String aggregateType,
            UUID aggregateId,
            Long aggregateVersion,
            OffsetDateTime occurredAt,
            UUID eventId) {
        if (aggregateType == null) {
            return Optional.empty();
        }
        return Optional.of(new ProjectionHistoryCursor(
                aggregateType, aggregateId, aggregateVersion,
                UtcTimestamp.from(occurredAt), eventId));
    }

    private static String requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.strip().length() > 160) {
            throw new IllegalArgumentException("ownerId must contain between 1 and 160 characters");
        }
        return ownerId.strip();
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name);
        if (required.isNegative() || required.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return required;
    }

    private record TerminalGeneration(
            UUID organizationId, String projectionName, long generation, String status) {}
}
