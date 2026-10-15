package io.crewscope.infrastructure.persistence.operations;

import io.crewscope.application.observability.sli.SliExecutionStatistics;
import io.crewscope.application.observability.sli.SliNotificationStatistics;
import io.crewscope.application.observability.sli.SliQueueingSnapshot;
import io.crewscope.application.observability.sli.SliStatisticsRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-query PostgreSQL facts for the M12-I03 SLI surface. Table-backed rates count
 * rows by {@code created_at} inside the window (zero-migration: organization_id +
 * created_at scans, the cost is documented in the capacity report); the queueing
 * snapshot reuses the operations-health shapes — outbox joins domain_event for the
 * organization filter, projection lag rides the active generation's checkpoint.
 */
@Repository
public class JdbcSliStatisticsAdapter implements SliStatisticsRepository {

    private final JdbcTemplate jdbc;

    public JdbcSliStatisticsAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public SliExecutionStatistics executionStatistics(
            OrganizationId organizationId, Instant windowStart, Instant windowEnd) {
        SliStatisticsRepository.requireWindow(windowStart, windowEnd);
        UUID organization = organizationId.value();
        TaskExecutionCounts executions = jdbc.query(
                """
                SELECT
                    COUNT(*) FILTER (WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED'))
                        AS terminal,
                    COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                    COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                    COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                    COUNT(*) FILTER (WHERE status = 'FAILED'
                        AND terminal_failure_class IN ('POLICY_VIOLATION', 'AUTHORIZATION'))
                        AS policy_denied
                FROM crewscope.task_execution
                WHERE organization_id = ? AND created_at >= ? AND created_at < ?
                """,
                this::taskExecutionRow,
                organization, atUtc(windowStart), atUtc(windowEnd));
        AgentRunCounts runs = jdbc.query(
                """
                SELECT
                    COUNT(*) FILTER (WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED'))
                        AS terminal,
                    COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                    COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                    COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                    COUNT(*) FILTER (WHERE status = 'INTERRUPTED') AS interrupted
                FROM crewscope.agent_run
                WHERE organization_id = ? AND created_at >= ? AND created_at < ?
                """,
                this::agentRunRow,
                organization, atUtc(windowStart), atUtc(windowEnd));
        return new SliExecutionStatistics(
                executions.terminal(), executions.completed(), executions.failed(),
                executions.cancelled(), executions.policyDenied(),
                runs.terminal(), runs.completed(), runs.failed(),
                runs.cancelled(), runs.interrupted());
    }

    @Override
    @Transactional(readOnly = true)
    public SliNotificationStatistics notificationStatistics(
            OrganizationId organizationId, Instant windowStart, Instant windowEnd) {
        SliStatisticsRepository.requireWindow(windowStart, windowEnd);
        return jdbc.query(
                """
                SELECT
                    COUNT(*) FILTER (WHERE delivery.status = 'SUCCEEDED') AS delivered,
                    COUNT(*) FILTER (WHERE delivery.status = 'FAILED_FINAL'
                        AND COALESCE(receipt.failure_code, '')
                            NOT IN ('RECIPIENT_UNAVAILABLE', 'AUTHORIZATION_REVOKED'))
                        AS failed_final,
                    COUNT(*) FILTER (WHERE delivery.status = 'FAILED_FINAL'
                        AND receipt.failure_code
                            IN ('RECIPIENT_UNAVAILABLE', 'AUTHORIZATION_REVOKED'))
                        AS recipient_side_excluded,
                    COUNT(*) FILTER (WHERE delivery.status IN ('INVALIDATED', 'CANCELLED'))
                        AS invalidated_or_cancelled
                FROM crewscope.notification_delivery delivery
                LEFT JOIN crewscope.notification_receipt receipt
                  ON receipt.organization_id = delivery.organization_id
                 AND receipt.delivery_id = delivery.delivery_id
                WHERE delivery.organization_id = ?
                  AND delivery.created_at >= ? AND delivery.created_at < ?
                """,
                this::notificationRow,
                organizationId.value(), atUtc(windowStart), atUtc(windowEnd));
    }

    @Override
    @Transactional(readOnly = true)
    public SliQueueingSnapshot queueingSnapshot(OrganizationId organizationId) {
        UUID organization = organizationId.value();
        return jdbc.query(
                """
                SELECT
                    (SELECT COUNT(*)
                       FROM crewscope.outbox_event outbox
                       JOIN crewscope.domain_event event
                         ON event.event_id = outbox.domain_event_id
                      WHERE event.organization_id = ?
                        AND outbox.delivery_status IN ('PENDING', 'CLAIMED'))
                        AS outbox_backlog,
                    (SELECT COALESCE(EXTRACT(EPOCH FROM
                            (CURRENT_TIMESTAMP - MIN(outbox.created_at)))::BIGINT, 0)
                       FROM crewscope.outbox_event outbox
                       JOIN crewscope.domain_event event
                         ON event.event_id = outbox.domain_event_id
                      WHERE event.organization_id = ?
                        AND outbox.delivery_status IN ('PENDING', 'CLAIMED'))
                        AS outbox_oldest_age_seconds,
                    (SELECT COALESCE(MAX(GREATEST(0, EXTRACT(EPOCH FROM
                            (CURRENT_TIMESTAMP - checkpoint.last_event_occurred_at)))::BIGINT), 0)
                       FROM crewscope.projection_pointer pointer
                       JOIN crewscope.projection_generation active
                         ON active.organization_id = pointer.organization_id
                        AND active.projection_name = pointer.projection_name
                        AND active.generation = pointer.active_generation
                        AND active.status = 'ACTIVE'
                       LEFT JOIN LATERAL (
                           SELECT MAX(checkpoint_row.last_event_occurred_at)
                               AS last_event_occurred_at
                             FROM crewscope.projection_generation_checkpoint checkpoint_row
                            WHERE checkpoint_row.organization_id = pointer.organization_id
                              AND checkpoint_row.projection_name = pointer.projection_name
                              AND checkpoint_row.generation = pointer.active_generation
                       ) checkpoint ON TRUE
                      WHERE pointer.organization_id = ?)
                        AS projection_max_lag_seconds
                """,
                this::queueingRow,
                organization, organization, organization);
    }

    private TaskExecutionCounts taskExecutionRow(ResultSet row) throws SQLException {
        if (!row.next()) {
            throw new IllegalStateException("SLI execution aggregate query returned no row");
        }
        return new TaskExecutionCounts(
                row.getLong("terminal"),
                row.getLong("completed"),
                row.getLong("failed"),
                row.getLong("cancelled"),
                row.getLong("policy_denied"));
    }

    private AgentRunCounts agentRunRow(ResultSet row) throws SQLException {
        if (!row.next()) {
            throw new IllegalStateException("SLI agent-run aggregate query returned no row");
        }
        return new AgentRunCounts(
                row.getLong("terminal"),
                row.getLong("completed"),
                row.getLong("failed"),
                row.getLong("cancelled"),
                row.getLong("interrupted"));
    }

    private SliNotificationStatistics notificationRow(ResultSet row) throws SQLException {
        if (!row.next()) {
            throw new IllegalStateException("SLI notification aggregate query returned no row");
        }
        return new SliNotificationStatistics(
                row.getLong("delivered"),
                row.getLong("failed_final"),
                row.getLong("recipient_side_excluded"),
                row.getLong("invalidated_or_cancelled"));
    }

    private SliQueueingSnapshot queueingRow(ResultSet row) throws SQLException {
        if (!row.next()) {
            throw new IllegalStateException("SLI queueing snapshot query returned no row");
        }
        return new SliQueueingSnapshot(
                row.getLong("outbox_backlog"),
                row.getLong("outbox_oldest_age_seconds"),
                row.getLong("projection_max_lag_seconds"));
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record TaskExecutionCounts(
            long terminal, long completed, long failed, long cancelled, long policyDenied) {}

    private record AgentRunCounts(
            long terminal, long completed, long failed, long cancelled, long interrupted) {}
}
