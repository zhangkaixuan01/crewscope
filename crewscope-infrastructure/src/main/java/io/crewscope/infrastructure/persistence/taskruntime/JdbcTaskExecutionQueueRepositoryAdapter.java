package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.TaskExecutionQueueRepository;
import io.crewscope.domain.task.TaskExecution;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation of the READY queue ordering, keyset and lock protocol. */
@Repository
public class JdbcTaskExecutionQueueRepositoryAdapter implements TaskExecutionQueueRepository {

    private static final String ORDER_BY =
            " ORDER BY priority DESC, not_before ASC, created_at ASC, id ASC ";

    private final NamedParameterJdbcTemplate jdbc;
    private final TaskRuntimePersistenceMapper mapper;

    public JdbcTaskExecutionQueueRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, TaskRuntimePersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ReadyPage findReadyPage(ReadyQuery query) {
        ReadyQuery required = Objects.requireNonNull(query, "query");
        List<QueueRow> rows = select(required, false, required.limit() + 1);
        boolean hasNext = rows.size() > required.limit();
        List<QueueRow> pageRows = hasNext ? rows.subList(0, required.limit()) : rows;
        List<TaskExecution> executions = pageRows.stream().map(QueueRow::execution).toList();
        Optional<ReadyCursor> next = hasNext
                ? Optional.of(pageRows.get(pageRows.size() - 1).cursor())
                : Optional.empty();
        return new ReadyPage(executions, next);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TaskExecution> lockReadyBatch(ReadyQuery query) {
        ReadyQuery required = Objects.requireNonNull(query, "query");
        return select(required, true, required.limit()).stream().map(QueueRow::execution).toList();
    }

    private List<QueueRow> select(ReadyQuery query, boolean lock, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("organizationId", query.organizationId().value());
        parameters.put("authoritativeNow", query.authoritativeNow().toOffsetDateTime());
        parameters.put("limit", limit);
        StringBuilder where = new StringBuilder(
                " organization_id = :organizationId AND status = 'READY' AND not_before <= :authoritativeNow ");
        query.teamId().ifPresent(teamId -> {
            parameters.put("teamId", teamId.value());
            where.append(" AND team_id = :teamId ");
        });
        query.after().ifPresent(cursor -> {
            parameters.put("cursorPriority", cursor.priority());
            parameters.put("cursorNotBefore", cursor.notBefore().toOffsetDateTime());
            parameters.put("cursorCreatedAt", cursor.createdAt().toOffsetDateTime());
            parameters.put("cursorId", cursor.executionId().value());
            // The disjunction mirrors the mixed DESC/ASC index ordering exactly.
            where.append(
                    """
                    AND (
                        priority < :cursorPriority
                        OR (priority = :cursorPriority AND not_before > :cursorNotBefore)
                        OR (priority = :cursorPriority AND not_before = :cursorNotBefore
                            AND created_at > :cursorCreatedAt)
                        OR (priority = :cursorPriority AND not_before = :cursorNotBefore
                            AND created_at = :cursorCreatedAt AND id > :cursorId)
                    )
                    """);
        });
        String sql = "SELECT * FROM crewscope.task_execution WHERE " + where + ORDER_BY
                + (lock ? " FOR UPDATE SKIP LOCKED " : "") + " LIMIT :limit";
        return jdbc.query(sql, parameters, this::mapRow);
    }

    private QueueRow mapRow(ResultSet result, int rowNumber) throws SQLException {
        TaskExecutionEntity entity = new TaskExecutionEntity();
        entity.id = result.getObject("id", UUID.class);
        entity.organizationId = result.getObject("organization_id", UUID.class);
        entity.teamId = result.getObject("team_id", UUID.class);
        entity.workspaceId = result.getObject("workspace_id", UUID.class);
        entity.projectId = result.getObject("project_id", UUID.class);
        entity.taskId = result.getObject("task_id", UUID.class);
        entity.attempt = result.getInt("attempt");
        entity.maxAttempts = result.getInt("max_attempts");
        entity.parentExecutionId = result.getObject("parent_execution_id", UUID.class);
        entity.priority = result.getInt("priority");
        entity.notBefore = instant(result, "not_before");
        entity.status = result.getString("status");
        entity.waitingReason = result.getString("waiting_reason");
        entity.waitingSince = instant(result, "waiting_since");
        entity.controlRequestType = result.getString("control_request_type");
        entity.controlRequestedByPrincipalId = result.getObject("control_requested_by_principal_id", UUID.class);
        entity.controlRequestedAt = instant(result, "control_requested_at");
        entity.controlRequestReason = result.getString("control_request_reason");
        entity.terminalDecidedByPrincipalId = result.getObject("terminal_decided_by_principal_id", UUID.class);
        entity.terminalDecidedAt = instant(result, "terminal_decided_at");
        entity.terminalFailureClass = result.getString("terminal_failure_class");
        entity.terminalFailureCode = result.getString("terminal_failure_code");
        entity.executionPrincipalId = result.getObject("execution_principal_id", UUID.class);
        entity.executionAssignmentId = result.getObject("execution_assignment_id", UUID.class);
        entity.executionAssignmentVersion = nullableLong(result, "execution_assignment_version");
        entity.responsibilitySnapshotHash = trimmed(result, "responsibility_snapshot_hash");
        entity.currentPolicySnapshotId = result.getObject("current_policy_snapshot_id", UUID.class);
        entity.currentPolicySnapshotHash = trimmed(result, "current_policy_snapshot_hash");
        entity.currentSafetyOverlayId = result.getObject("current_safety_overlay_id", UUID.class);
        entity.currentSafetyOverlayVersion = nullableLong(result, "current_safety_overlay_version");
        entity.currentSafetyOverlayHash = trimmed(result, "current_safety_overlay_hash");
        entity.currentPlanVersionId = result.getObject("current_plan_version_id", UUID.class);
        entity.currentPlanVersionHash = trimmed(result, "current_plan_version_hash");
        entity.lastFencingToken = nullableLong(result, "last_fencing_token");
        entity.version = result.getLong("version");
        entity.createdAt = instant(result, "created_at");
        entity.createdByPrincipalId = result.getObject("created_by_principal_id", UUID.class);
        entity.updatedAt = instant(result, "updated_at");
        entity.updatedByPrincipalId = result.getObject("updated_by_principal_id", UUID.class);
        return new QueueRow(mapper.toExecutionDomain(entity), new ReadyCursor(
                entity.priority,
                new io.crewscope.domain.shared.time.UtcTimestamp(entity.notBefore),
                new io.crewscope.domain.shared.time.UtcTimestamp(entity.createdAt),
                new io.crewscope.domain.task.TaskExecutionId(entity.id)));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String trimmed(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        return value == null ? null : value.trim();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record QueueRow(TaskExecution execution, ReadyCursor cursor) {}
}
