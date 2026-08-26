package io.crewscope.infrastructure.persistence.operations;

import io.crewscope.application.operations.NotificationDeliveryRecoveryTarget;
import io.crewscope.application.operations.OperationsComponentObservation;
import io.crewscope.application.operations.OperationsHealthComponent;
import io.crewscope.application.operations.OperationsHealthQueryPort;
import io.crewscope.application.operations.OperationsHealthSnapshot;
import io.crewscope.application.operations.OperationsRecoveryTarget;
import io.crewscope.application.operations.OutboxDeadLetterRecoveryTarget;
import io.crewscope.application.operations.ProjectionDeadLetterRecoveryTarget;
import io.crewscope.application.operations.ProjectionHealthDiagnostic;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.projection.ProjectionDeadLetterId;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationStatus;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Fixed-query PostgreSQL health snapshot with bounded, payload-free recovery coordinates. */
@Repository
public class JdbcOperationsHealthQueryAdapter implements OperationsHealthQueryPort {

    public static final int MAX_RECOVERY_CANDIDATES_PER_TYPE = 200;

    private final JdbcTemplate jdbc;

    public JdbcOperationsHealthQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationsHealthSnapshot observe(OrganizationId organizationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        OffsetDateTime observedAt = Objects.requireNonNull(
                jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class),
                "observedAt");
        List<ProjectionHealthDiagnostic> diagnostics = projectionDiagnostics(organization);
        List<OperationsComponentObservation> components = List.of(
                projectionComponent(organization),
                outboxComponent(organization),
                deadLetterComponent(organization),
                cursorComponent(organization),
                notificationComponent(organization));
        return new OperationsHealthSnapshot(
                organization,
                UtcTimestamp.from(observedAt),
                components,
                diagnostics,
                recoveryCandidates(organization));
    }

    private List<ProjectionHealthDiagnostic> projectionDiagnostics(OrganizationId organizationId) {
        return jdbc.query(
                """
                SELECT pointer.projection_name, active.definition_version,
                       pointer.active_generation, pointer.version AS pointer_version,
                       active.version AS active_version,
                       shadow.generation AS shadow_generation,
                       shadow.status AS shadow_status,
                       shadow.version AS shadow_version,
                       shadow.rebuild_job_id,
                       job.version AS job_version,
                       CASE WHEN checkpoint.last_event_occurred_at IS NULL THEN 0
                            ELSE GREATEST(0, EXTRACT(EPOCH FROM
                                 (CURRENT_TIMESTAMP - checkpoint.last_event_occurred_at)))::BIGINT
                       END AS lag_seconds,
                       COALESCE(validation.actual_gap_count, 0) AS gap_count,
                       COALESCE(dead_letter.dead_letter_count, 0) AS dead_letter_count,
                       dead_letter.latest_failure_code
                FROM crewscope.projection_pointer pointer
                JOIN crewscope.projection_generation active
                  ON active.organization_id = pointer.organization_id
                 AND active.projection_name = pointer.projection_name
                 AND active.generation = pointer.active_generation
                 AND active.status = 'ACTIVE'
                LEFT JOIN LATERAL (
                    SELECT generation, status, version, rebuild_job_id, current_validation_id
                    FROM crewscope.projection_generation candidate
                    WHERE candidate.organization_id = pointer.organization_id
                      AND candidate.projection_name = pointer.projection_name
                      AND candidate.status IN ('BUILDING', 'VALIDATING')
                    ORDER BY candidate.generation DESC
                    LIMIT 1
                ) shadow ON TRUE
                LEFT JOIN crewscope.projection_rebuild_job job
                  ON job.organization_id = pointer.organization_id
                 AND job.id = shadow.rebuild_job_id
                LEFT JOIN crewscope.projection_validation_result validation
                  ON validation.id = shadow.current_validation_id
                LEFT JOIN LATERAL (
                    SELECT MAX(last_event_occurred_at) AS last_event_occurred_at
                    FROM crewscope.projection_generation_checkpoint checkpoint_row
                    WHERE checkpoint_row.organization_id = pointer.organization_id
                      AND checkpoint_row.projection_name = pointer.projection_name
                      AND checkpoint_row.generation = pointer.active_generation
                ) checkpoint ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS dead_letter_count,
                           (ARRAY_AGG(failure_code ORDER BY created_at DESC, id DESC))[1]
                               AS latest_failure_code
                    FROM crewscope.projection_dead_letter dead
                    WHERE dead.organization_id = pointer.organization_id
                      AND dead.projection_name = pointer.projection_name
                      AND dead.generation = pointer.active_generation
                ) dead_letter ON TRUE
                WHERE pointer.organization_id = ?
                ORDER BY pointer.projection_name
                """,
                this::projectionDiagnostic,
                organizationId.value());
    }

    private ProjectionHealthDiagnostic projectionDiagnostic(ResultSet row, int ignored)
            throws SQLException {
        Long shadowGeneration = nullableLong(row, "shadow_generation");
        Long shadowVersion = nullableLong(row, "shadow_version");
        UUID rebuildJobId = row.getObject("rebuild_job_id", UUID.class);
        Long jobVersion = nullableLong(row, "job_version");
        return new ProjectionHealthDiagnostic(
                new ProjectionName(row.getString("projection_name")),
                new ProjectionDefinitionVersion(row.getLong("definition_version")),
                new ProjectionGeneration(row.getLong("active_generation")),
                row.getLong("pointer_version"),
                row.getLong("active_version"),
                Optional.ofNullable(shadowGeneration).map(ProjectionGeneration::new),
                Optional.ofNullable(row.getString("shadow_status"))
                        .map(ProjectionGenerationStatus::valueOf),
                optionalLong(shadowVersion),
                Optional.ofNullable(rebuildJobId).map(ProjectionRebuildJobId::new),
                optionalLong(jobVersion),
                row.getLong("lag_seconds"),
                row.getLong("gap_count"),
                row.getLong("dead_letter_count"),
                Optional.ofNullable(row.getString("latest_failure_code"))
                        .map(ProjectionFailureCode::new));
    }

    private OperationsComponentObservation projectionComponent(OrganizationId organizationId) {
        return component(
                OperationsHealthComponent.PROJECTION,
                aggregate(
                        """
                        SELECT COUNT(*) FILTER (WHERE status IN ('BUILDING', 'VALIDATING')) AS backlog,
                               COUNT(*) FILTER (WHERE status IN ('BUILDING', 'VALIDATING')) AS in_flight,
                               COUNT(*) FILTER (WHERE status = 'FAILED') AS failures,
                               COUNT(DISTINCT projection_name) FILTER (
                                   WHERE status IN ('BUILDING', 'VALIDATING', 'FAILED')) AS affected,
                               MIN(created_at) FILTER (
                                   WHERE status IN ('BUILDING', 'VALIDATING')) AS oldest
                        FROM crewscope.projection_generation
                        WHERE organization_id = ?
                        """,
                        organizationId.value()));
    }

    private OperationsComponentObservation outboxComponent(OrganizationId organizationId) {
        return component(
                OperationsHealthComponent.OUTBOX,
                aggregate(
                        """
                        SELECT COUNT(*) FILTER (
                                   WHERE outbox.delivery_status IN ('PENDING', 'CLAIMED')) AS backlog,
                               COUNT(*) FILTER (WHERE outbox.delivery_status = 'CLAIMED') AS in_flight,
                               COUNT(*) FILTER (WHERE outbox.delivery_status = 'DEAD_LETTER') AS failures,
                               COUNT(*) FILTER (WHERE outbox.delivery_status = 'DEAD_LETTER') AS affected,
                               MIN(outbox.created_at) FILTER (
                                   WHERE outbox.delivery_status IN ('PENDING', 'CLAIMED')) AS oldest
                        FROM crewscope.outbox_event outbox
                        JOIN crewscope.domain_event event ON event.event_id = outbox.domain_event_id
                        WHERE event.organization_id = ?
                        """,
                        organizationId.value()));
    }

    private OperationsComponentObservation deadLetterComponent(OrganizationId organizationId) {
        return component(
                OperationsHealthComponent.DEAD_LETTER,
                aggregate(
                        """
                        WITH failures AS (
                            SELECT outbox.id, outbox.created_at
                            FROM crewscope.outbox_event outbox
                            JOIN crewscope.domain_event event
                              ON event.event_id = outbox.domain_event_id
                            WHERE event.organization_id = ?
                              AND outbox.delivery_status = 'DEAD_LETTER'
                            UNION ALL
                            SELECT dead.id, dead.created_at
                            FROM crewscope.projection_dead_letter dead
                            WHERE dead.organization_id = ?
                        )
                        SELECT COUNT(*) AS backlog, 0::BIGINT AS in_flight,
                               COUNT(*) AS failures, COUNT(*) AS affected,
                               MIN(created_at) AS oldest
                        FROM failures
                        """,
                        organizationId.value(),
                        organizationId.value()));
    }

    private OperationsComponentObservation cursorComponent(OrganizationId organizationId) {
        return component(
                OperationsHealthComponent.CURSOR,
                aggregate(
                        """
                        WITH latest_event AS (
                            SELECT MAX(occurred_at) AS occurred_at
                            FROM crewscope.domain_event WHERE organization_id = ?
                        ), positions AS (
                            SELECT pointer.projection_name,
                                   MAX(checkpoint.last_event_occurred_at) AS last_event_at,
                                   active.created_at
                            FROM crewscope.projection_pointer pointer
                            JOIN crewscope.projection_generation active
                              ON active.organization_id = pointer.organization_id
                             AND active.projection_name = pointer.projection_name
                             AND active.generation = pointer.active_generation
                            LEFT JOIN crewscope.projection_generation_checkpoint checkpoint
                              ON checkpoint.organization_id = pointer.organization_id
                             AND checkpoint.projection_name = pointer.projection_name
                             AND checkpoint.generation = pointer.active_generation
                            WHERE pointer.organization_id = ?
                            GROUP BY pointer.projection_name, active.created_at
                        )
                        SELECT COUNT(*) FILTER (
                                   WHERE latest_event.occurred_at IS NOT NULL
                                     AND (positions.last_event_at IS NULL
                                       OR positions.last_event_at < latest_event.occurred_at)) AS backlog,
                               0::BIGINT AS in_flight, 0::BIGINT AS failures,
                               COUNT(*) FILTER (
                                   WHERE latest_event.occurred_at IS NOT NULL
                                     AND (positions.last_event_at IS NULL
                                       OR positions.last_event_at < latest_event.occurred_at)) AS affected,
                               MIN(COALESCE(positions.last_event_at, positions.created_at)) FILTER (
                                   WHERE latest_event.occurred_at IS NOT NULL
                                     AND (positions.last_event_at IS NULL
                                       OR positions.last_event_at < latest_event.occurred_at)) AS oldest
                        FROM positions CROSS JOIN latest_event
                        """,
                        organizationId.value(),
                        organizationId.value()));
    }

    private OperationsComponentObservation notificationComponent(OrganizationId organizationId) {
        return component(
                OperationsHealthComponent.NOTIFICATION,
                aggregate(
                        """
                        SELECT COUNT(*) FILTER (WHERE delivery.status IN (
                                   'READY', 'RUNNING', 'RETRY_WAIT', 'UNKNOWN', 'RECONCILING'))
                                   AS backlog,
                               COUNT(*) FILTER (WHERE delivery.status IN ('RUNNING', 'RECONCILING'))
                                   AS in_flight,
                               COUNT(*) FILTER (WHERE delivery.status = 'FAILED_FINAL') AS failures,
                               COUNT(*) FILTER (WHERE delivery.status = 'FAILED_FINAL') AS affected,
                               MIN(delivery.created_at) FILTER (WHERE delivery.status IN (
                                   'READY', 'RUNNING', 'RETRY_WAIT', 'UNKNOWN', 'RECONCILING'))
                                   AS oldest
                        FROM crewscope.notification_delivery delivery
                        JOIN crewscope.notification_planned_action action
                          ON action.organization_id = delivery.organization_id
                         AND action.action_id = delivery.action_id
                        WHERE action.organization_id = ?
                        """,
                        organizationId.value()));
    }

    private Aggregate aggregate(String sql, Object... parameters) {
        return jdbc.query(sql, result -> {
            if (!result.next()) {
                throw new IllegalStateException("Operations aggregate query returned no row");
            }
            return new Aggregate(
                    result.getLong("backlog"),
                    result.getLong("in_flight"),
                    result.getLong("failures"),
                    result.getLong("affected"),
                    Optional.ofNullable(result.getObject("oldest", OffsetDateTime.class))
                            .map(UtcTimestamp::from));
        }, parameters);
    }

    private static OperationsComponentObservation component(
            OperationsHealthComponent component, Aggregate aggregate) {
        return new OperationsComponentObservation(
                component,
                aggregate.backlog(),
                aggregate.inFlight(),
                aggregate.failures(),
                aggregate.affected(),
                aggregate.oldest(),
                false);
    }

    private List<OperationsRecoveryTarget> recoveryCandidates(OrganizationId organizationId) {
        List<OperationsRecoveryTarget> targets = new ArrayList<>();
        targets.addAll(jdbc.query(
                """
                SELECT outbox.id, outbox.domain_event_id, outbox.version
                FROM crewscope.outbox_event outbox
                JOIN crewscope.domain_event event ON event.event_id = outbox.domain_event_id
                WHERE event.organization_id = ? AND outbox.delivery_status = 'DEAD_LETTER'
                ORDER BY outbox.created_at, outbox.id
                LIMIT ?
                """,
                (row, ignored) -> new OutboxDeadLetterRecoveryTarget(
                        row.getObject("id", UUID.class),
                        row.getObject("domain_event_id", UUID.class),
                        row.getLong("version")),
                organizationId.value(),
                MAX_RECOVERY_CANDIDATES_PER_TYPE));
        targets.addAll(jdbc.query(
                """
                SELECT dead.projection_name, dead.generation, dead.id,
                       dead.domain_event_id, generation.version
                FROM crewscope.projection_dead_letter dead
                JOIN crewscope.projection_generation generation
                  ON generation.organization_id = dead.organization_id
                 AND generation.projection_name = dead.projection_name
                 AND generation.generation = dead.generation
                WHERE dead.organization_id = ?
                ORDER BY dead.created_at, dead.id
                LIMIT ?
                """,
                (row, ignored) -> new ProjectionDeadLetterRecoveryTarget(
                        new ProjectionName(row.getString("projection_name")),
                        new ProjectionGeneration(row.getLong("generation")),
                        new ProjectionDeadLetterId(row.getObject("id", UUID.class)),
                        row.getObject("domain_event_id", UUID.class),
                        row.getLong("version")),
                organizationId.value(),
                MAX_RECOVERY_CANDIDATES_PER_TYPE));
        targets.addAll(jdbc.query(
                """
                SELECT delivery.delivery_id, delivery.version
                FROM crewscope.notification_delivery delivery
                JOIN crewscope.notification_planned_action action
                  ON action.organization_id = delivery.organization_id
                 AND action.action_id = delivery.action_id
                WHERE action.organization_id = ? AND delivery.status = 'FAILED_FINAL'
                ORDER BY delivery.updated_at, delivery.delivery_id
                LIMIT ?
                """,
                (row, ignored) -> new NotificationDeliveryRecoveryTarget(
                        new NotificationDeliveryId(row.getObject("delivery_id", UUID.class)),
                        row.getLong("version")),
                organizationId.value(),
                MAX_RECOVERY_CANDIDATES_PER_TYPE));
        return List.copyOf(targets);
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private record Aggregate(
            long backlog,
            long inFlight,
            long failures,
            long affected,
            Optional<UtcTimestamp> oldest) {}
}
