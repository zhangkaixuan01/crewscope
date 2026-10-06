package io.crewscope.infrastructure.persistence.observability;

import io.crewscope.application.observability.TaskQualityStatistics;
import io.crewscope.application.observability.TaskQualityStatisticsRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Direct quality counters over the durable task facts (M10-F03). Execution attempts group
 * by {@code created_at} inside the window; review first-pass groups by the earliest
 * non-invalidated request's {@code created_at}, joined to the maintained projection for
 * the terminal decision and modification rounds. No projection is added — these tables
 * are the facts the contract §quality promises to present as-is.
 */
public class JdbcTaskQualityStatisticsAdapter implements TaskQualityStatisticsRepository {

    private static final String EXECUTION_COUNTS = """
            SELECT COUNT(*) AS total_attempts,
                   COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed_attempts,
                   COUNT(*) FILTER (WHERE status = 'FAILED') AS failed_attempts,
                   COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_attempts
            FROM crewscope.task_execution
            WHERE organization_id = ? AND team_id = ?
              AND created_at >= ? AND created_at < ?""";

    /**
     * One row per task: its earliest non-invalidated review request inside the window
     * (DISTINCT ON keeps the lowest revision). First pass = that request ended APPROVED
     * with no modification rounds behind it.
     */
    private static final String REVIEW_FIRST_PASS = """
            WITH first_requests AS (
                SELECT DISTINCT ON (rr.task_id)
                       rrp.latest_decision_type AS latest_decision_type,
                       rrp.modification_round AS modification_round
                FROM crewscope.review_request rr
                JOIN crewscope.review_request_projection rrp
                  ON rrp.review_request_id = rr.id
                WHERE rr.organization_id = ? AND rr.team_id = ?
                  AND rr.status <> 'INVALIDATED'
                  AND rr.created_at >= ? AND rr.created_at < ?
                ORDER BY rr.task_id, rr.revision
            )
            SELECT COUNT(*) AS entered_review,
                   COUNT(*) FILTER (
                       WHERE latest_decision_type = 'APPROVED' AND modification_round = 0)
                       AS first_pass_approved
            FROM first_requests""";

    private final JdbcTemplate jdbcTemplate;

    public JdbcTaskQualityStatisticsAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public TaskQualityStatistics findForWindow(
            OrganizationId organizationId, TeamId teamId, Instant windowStart, Instant windowEnd) {
        Timestamp start = Timestamp.from(
                Objects.requireNonNull(windowStart, "windowStart"));
        Timestamp end = Timestamp.from(Objects.requireNonNull(windowEnd, "windowEnd"));
        long[] executions = jdbcTemplate.queryForObject(
                EXECUTION_COUNTS,
                (resultSet, ignored) -> new long[] {
                        resultSet.getLong("total_attempts"),
                        resultSet.getLong("completed_attempts"),
                        resultSet.getLong("failed_attempts"),
                        resultSet.getLong("cancelled_attempts")},
                organizationId.value(), teamId.value(), start, end);
        long[] reviews = jdbcTemplate.queryForObject(
                REVIEW_FIRST_PASS,
                (resultSet, ignored) -> new long[] {
                        resultSet.getLong("entered_review"),
                        resultSet.getLong("first_pass_approved")},
                organizationId.value(), teamId.value(), start, end);
        return new TaskQualityStatistics(
                executions[0], executions[1], executions[2], executions[3],
                reviews[0], reviews[1]);
    }
}
