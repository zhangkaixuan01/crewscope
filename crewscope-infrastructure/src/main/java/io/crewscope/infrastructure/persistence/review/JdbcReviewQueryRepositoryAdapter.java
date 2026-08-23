package io.crewscope.infrastructure.persistence.review;

import io.crewscope.application.review.ReviewQueryRepository;
import io.crewscope.application.review.ReviewRequestProjection;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewInvalidationReason;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Rebuildable PostgreSQL Review workbench projection; source facts remain the authority. */
@Repository
public class JdbcReviewQueryRepositoryAdapter implements ReviewQueryRepository {

    private final JdbcTemplate jdbc;

    public JdbcReviewQueryRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ReviewRequestProjection> findByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.review_request_projection
                WHERE organization_id = ? AND review_request_id = ?
                """,
                this::projection,
                organizationId.value(), reviewRequestId.value()));
    }

    @Override
    public List<ReviewRequestProjection> findByExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        return jdbc.query(
                """
                SELECT * FROM crewscope.review_request_projection
                WHERE organization_id = ? AND task_execution_id = ? AND attempt = ?
                ORDER BY request_revision DESC, review_request_id DESC
                """,
                this::projection,
                organizationId.value(), taskExecutionId.value(), attempt);
    }

    @Override
    public List<ReviewRequestProjection> findHistoryByTask(
            OrganizationId organizationId, TaskId taskId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Review history limit must be between 1 and 200");
        }
        return jdbc.query(
                """
                SELECT * FROM crewscope.review_request_projection
                WHERE organization_id = ? AND task_id = ?
                ORDER BY request_revision DESC, review_request_id DESC
                LIMIT ?
                """,
                this::projection,
                organizationId.value(), taskId.value(), limit);
    }

    @Override
    @Transactional
    public void rebuild(OrganizationId organizationId, ReviewRequestId reviewRequestId) {
        int rebuilt = rebuildOne(organizationId, reviewRequestId);
        if (rebuilt != 1) {
            throw new IllegalStateException("ReviewRequest projection source does not exist");
        }
    }

    @Override
    @Transactional
    public int rebuildAll(OrganizationId organizationId) {
        List<ReviewRequestId> ids = jdbc.query(
                """
                SELECT id FROM crewscope.review_request
                WHERE organization_id = ? ORDER BY task_id, revision, id
                """,
                (row, ignored) -> new ReviewRequestId(row.getObject("id", UUID.class)),
                organizationId.value());
        jdbc.update(
                "DELETE FROM crewscope.review_request_projection WHERE organization_id = ?",
                organizationId.value());
        ids.forEach(id -> {
            if (rebuildOne(organizationId, id) != 1) {
                throw new IllegalStateException("Review projection rebuild lost a source row");
            }
        });
        return ids.size();
    }

    private int rebuildOne(OrganizationId organizationId, ReviewRequestId reviewRequestId) {
        return jdbc.update(
                """
                INSERT INTO crewscope.review_request_projection (
                    review_request_id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, request_revision, request_version,
                    request_status, invalidation_reason, context_hash,
                    finding_count, duplicate_observation_count, blocker_count, high_count,
                    latest_decision_id, latest_decision_revision, latest_decision_type,
                    modification_round, projected_at
                )
                SELECT r.id, r.organization_id, r.team_id, r.workspace_id, r.project_id,
                    r.task_id, r.task_execution_id, r.attempt, r.revision, r.version,
                    r.status, r.invalidation_reason, r.context_hash,
                    COALESCE(findings.finding_count, 0),
                    COALESCE(findings.duplicate_observation_count, 0),
                    COALESCE(findings.blocker_count, 0), COALESCE(findings.high_count, 0),
                    decision.id, decision.revision, decision.decision_type,
                    COALESCE(rounds.round_number, 0), CURRENT_TIMESTAMP
                FROM crewscope.review_request r
                LEFT JOIN LATERAL (
                    SELECT COUNT(*)::INTEGER AS finding_count,
                        COALESCE(SUM((f.severity = 'BLOCKER')::INTEGER), 0)::INTEGER AS blocker_count,
                        COALESCE(SUM((f.severity = 'HIGH')::INTEGER), 0)::INTEGER AS high_count,
                        COALESCE((
                            SELECT COUNT(*)::INTEGER
                            FROM crewscope.review_finding_observation observation
                            WHERE observation.review_request_id = r.id
                        ), 0) AS duplicate_observation_count
                    FROM crewscope.review_finding f
                    WHERE f.review_request_id = r.id
                ) findings ON TRUE
                LEFT JOIN LATERAL (
                    SELECT d.id, d.revision, d.decision_type
                    FROM crewscope.review_decision d
                    WHERE d.review_request_id = r.id
                    ORDER BY d.revision DESC, d.id DESC LIMIT 1
                ) decision ON TRUE
                LEFT JOIN LATERAL (
                    SELECT m.round_number
                    FROM crewscope.review_modification_round m
                    WHERE m.source_request_id = r.id
                    ORDER BY m.round_number DESC, m.id DESC LIMIT 1
                ) rounds ON TRUE
                WHERE r.organization_id = ? AND r.id = ?
                ON CONFLICT (review_request_id) DO UPDATE SET
                    organization_id = EXCLUDED.organization_id,
                    team_id = EXCLUDED.team_id,
                    workspace_id = EXCLUDED.workspace_id,
                    project_id = EXCLUDED.project_id,
                    task_id = EXCLUDED.task_id,
                    task_execution_id = EXCLUDED.task_execution_id,
                    attempt = EXCLUDED.attempt,
                    request_revision = EXCLUDED.request_revision,
                    request_version = EXCLUDED.request_version,
                    request_status = EXCLUDED.request_status,
                    invalidation_reason = EXCLUDED.invalidation_reason,
                    context_hash = EXCLUDED.context_hash,
                    finding_count = EXCLUDED.finding_count,
                    duplicate_observation_count = EXCLUDED.duplicate_observation_count,
                    blocker_count = EXCLUDED.blocker_count,
                    high_count = EXCLUDED.high_count,
                    latest_decision_id = EXCLUDED.latest_decision_id,
                    latest_decision_revision = EXCLUDED.latest_decision_revision,
                    latest_decision_type = EXCLUDED.latest_decision_type,
                    modification_round = EXCLUDED.modification_round,
                    projected_at = EXCLUDED.projected_at
                """,
                organizationId.value(), reviewRequestId.value());
    }

    private ReviewRequestProjection projection(ResultSet row, int ignored) throws SQLException {
        UUID decisionId = row.getObject("latest_decision_id", UUID.class);
        Long decisionRevision = row.getObject("latest_decision_revision", Long.class);
        String decisionType = row.getString("latest_decision_type");
        return new ReviewRequestProjection(
                new ReviewRequestId(row.getObject("review_request_id", UUID.class)),
                new WorkItemScope(
                        new OrganizationId(row.getObject("organization_id", UUID.class)),
                        new TeamId(row.getObject("team_id", UUID.class)),
                        new WorkspaceId(row.getObject("workspace_id", UUID.class)),
                        new WorkProjectId(row.getObject("project_id", UUID.class))),
                new TaskId(row.getObject("task_id", UUID.class)),
                new TaskExecutionId(row.getObject("task_execution_id", UUID.class)),
                row.getInt("attempt"),
                row.getLong("request_revision"),
                row.getLong("request_version"),
                ReviewRequestStatus.valueOf(row.getString("request_status")),
                Optional.ofNullable(row.getString("invalidation_reason"))
                        .map(ReviewInvalidationReason::valueOf),
                new TaskFactHash(row.getString("context_hash")),
                row.getInt("finding_count"),
                row.getInt("duplicate_observation_count"),
                row.getInt("blocker_count"),
                row.getInt("high_count"),
                Optional.ofNullable(decisionId).map(ReviewDecisionId::new),
                Optional.ofNullable(decisionRevision),
                Optional.ofNullable(decisionType).map(ReviewDecisionType::valueOf),
                row.getLong("modification_round"),
                UtcTimestamp.from(row.getObject("projected_at", OffsetDateTime.class).toInstant()));
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Review projection query returned multiple rows");
        }
        return values.stream().findFirst();
    }
}
