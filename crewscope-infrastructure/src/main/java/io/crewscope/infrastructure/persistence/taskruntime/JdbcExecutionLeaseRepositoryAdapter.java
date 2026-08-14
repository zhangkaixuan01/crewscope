package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.ExecutionLeaseRelease;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionControlRequest;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionTerminal;
import io.crewscope.domain.task.TaskExecutionWaiting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL ownership adapter for Claim, Heartbeat, phase switch, release and expiry scans. */
@Repository
public class JdbcExecutionLeaseRepositoryAdapter implements ExecutionLeaseRepository {

    private static final RowMapper<ExecutionLease> LEASE_MAPPER =
            JdbcExecutionLeaseRepositoryAdapter::mapLease;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExecutionLeaseRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecutionLease acquire(TaskExecution claimedExecution, ExecutionLease lease) {
        TaskExecution execution = Objects.requireNonNull(claimedExecution, "claimedExecution");
        ExecutionLease requiredLease = Objects.requireNonNull(lease, "lease");
        requireLeaseMatchesExecution(execution, requiredLease);
        updateExecution(execution, "READY", Optional.empty());
        MapSqlParameterSource parameters = leaseParameters(requiredLease)
                .addValue("teamId", execution.scope().teamId().value())
                .addValue("workspaceId", execution.scope().workspaceId().value())
                .addValue("projectId", execution.scope().projectId().value())
                .addValue("taskId", execution.taskId().value());
        int inserted = jdbc.update(
                """
                INSERT INTO crewscope.execution_lease (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, runtime_environment,
                    runtime_id, worker_id, claim_token_hash, fencing_token,
                    phase, status, acquired_at, last_heartbeat_at, expires_at,
                    released_at, release_reason, lease_version
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :taskId, :taskExecutionId, :attempt, :environment,
                    :runtimeId, :workerId, :claimTokenHash, :fencingToken,
                    :phase, 'ACTIVE', :acquiredAt, :lastHeartbeatAt, :expiresAt,
                    NULL, NULL, :leaseVersion
                )
                """,
                parameters);
        if (inserted != 1) {
            throw new IllegalStateException("ExecutionLease insert did not affect exactly one row");
        }
        return requiredLease;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecutionLease renew(ExecutionLease lease) {
        ExecutionLease required = Objects.requireNonNull(lease, "lease");
        long expected = expectedVersion(required);
        int affected = jdbc.update(
                """
                UPDATE crewscope.execution_lease
                SET last_heartbeat_at = :lastHeartbeatAt,
                    expires_at = :expiresAt,
                    lease_version = :leaseVersion
                WHERE id = :id
                  AND organization_id = :organizationId
                  AND runtime_environment = :environment
                  AND task_execution_id = :taskExecutionId
                  AND attempt = :attempt
                  AND runtime_id = :runtimeId
                  AND worker_id = :workerId
                  AND claim_token_hash = :claimTokenHash
                  AND fencing_token = :fencingToken
                  AND phase = :phase
                  AND status = 'ACTIVE'
                  AND lease_version = :expectedVersion
                  AND expires_at > :lastHeartbeatAt
                """,
                leaseParameters(required).addValue("expectedVersion", expected));
        requireLeaseMutation(required, expected, affected);
        return required;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecutionLease switchPhase(
            TaskExecution runningExecution, ExecutionLease runLease) {
        TaskExecution execution = Objects.requireNonNull(runningExecution, "runningExecution");
        ExecutionLease lease = Objects.requireNonNull(runLease, "runLease");
        requireLeaseMatchesExecution(execution, lease);
        updateExecution(execution, null, Optional.of(lease));
        long expectedLeaseVersion = expectedVersion(lease);
        int affected = jdbc.update(
                """
                UPDATE crewscope.execution_lease
                SET phase = 'RUN',
                    last_heartbeat_at = :lastHeartbeatAt,
                    expires_at = :expiresAt,
                    lease_version = :leaseVersion
                WHERE id = :id
                  AND organization_id = :organizationId
                  AND task_execution_id = :taskExecutionId
                  AND attempt = :attempt
                  AND runtime_environment = :environment
                  AND runtime_id = :runtimeId
                  AND worker_id = :workerId
                  AND claim_token_hash = :claimTokenHash
                  AND fencing_token = :fencingToken
                  AND phase = 'PREPARE'
                  AND status = 'ACTIVE'
                  AND lease_version = :expectedVersion
                  AND expires_at > :lastHeartbeatAt
                """,
                leaseParameters(lease).addValue("expectedVersion", expectedLeaseVersion));
        requireLeaseMutation(lease, expectedLeaseVersion, affected);
        return lease;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecutionLease release(TaskExecution execution, ExecutionLease releasedLease) {
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        ExecutionLease lease = Objects.requireNonNull(releasedLease, "releasedLease");
        requireLeaseMatchesExecution(requiredExecution, lease);
        updateExecution(requiredExecution, null, Optional.of(lease));
        ExecutionLeaseRelease release = lease.release().orElseThrow(() ->
                new DomainValidationException("executionLease.release", "must be terminal"));
        long expectedLeaseVersion = expectedVersion(lease);
        int affected = jdbc.update(
                """
                UPDATE crewscope.execution_lease
                SET status = :leaseStatus,
                    released_at = :releasedAt,
                    release_reason = :releaseReason,
                    lease_version = :leaseVersion
                WHERE id = :id
                  AND organization_id = :organizationId
                  AND task_execution_id = :taskExecutionId
                  AND attempt = :attempt
                  AND runtime_environment = :environment
                  AND runtime_id = :runtimeId
                  AND worker_id = :workerId
                  AND claim_token_hash = :claimTokenHash
                  AND fencing_token = :fencingToken
                  AND status = 'ACTIVE'
                  AND lease_version = :expectedVersion
                  AND (:releaseReason = 'EXPIRED' OR expires_at > :releasedAt)
                """,
                    leaseParameters(lease)
                        .addValue("expectedVersion", expectedLeaseVersion)
                        .addValue("leaseStatus", "RELEASED")
                        .addValue("releasedAt", release.releasedAt().toOffsetDateTime())
                        .addValue("releaseReason", release.reason().name()));
        requireLeaseMutation(lease, expectedLeaseVersion, affected);
        return lease;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionLease> findById(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionLeaseId leaseId) {
        return jdbc.query(
                        """
                        SELECT * FROM crewscope.execution_lease
                        WHERE organization_id = :organizationId
                          AND runtime_environment = :environment
                          AND id = :id
                        """,
                        Map.of(
                                "organizationId", Objects.requireNonNull(
                                        organizationId, "organizationId").value(),
                                "environment", Objects.requireNonNull(
                                        environment, "environment").value(),
                                "id", Objects.requireNonNull(leaseId, "leaseId").value()),
                        LEASE_MAPPER)
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionLease> findActiveByTaskExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId) {
        return jdbc.query(
                        """
                        SELECT * FROM crewscope.execution_lease
                        WHERE organization_id = :organizationId
                          AND task_execution_id = :taskExecutionId
                          AND status = 'ACTIVE'
                        """,
                        Map.of(
                                "organizationId", Objects.requireNonNull(
                                        organizationId, "organizationId").value(),
                                "taskExecutionId", Objects.requireNonNull(
                                        taskExecutionId, "taskExecutionId").value()),
                        LEASE_MAPPER)
                .stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExecutionLease> findExpired(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            UtcTimestamp authoritativeNow,
            int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return jdbc.query(
                """
                SELECT * FROM crewscope.execution_lease
                WHERE organization_id = :organizationId
                  AND runtime_environment = :environment
                  AND status = 'ACTIVE'
                  AND expires_at <= :authoritativeNow
                ORDER BY expires_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
                """,
                Map.of(
                        "organizationId", Objects.requireNonNull(
                                organizationId, "organizationId").value(),
                        "environment", Objects.requireNonNull(
                                environment, "environment").value(),
                        "authoritativeNow", Objects.requireNonNull(
                                authoritativeNow, "authoritativeNow").toOffsetDateTime(),
                        "limit", limit),
                LEASE_MAPPER);
    }

    private void updateExecution(
            TaskExecution execution,
            String requiredSourceStatus,
            Optional<ExecutionLease> ownership) {
        long expected = execution.version() - 1;
        if (expected < 0) {
            throw new DomainValidationException(
                    "taskExecution.version", "must contain one uncommitted mutation");
        }
        Map<String, Object> values = executionParameters(execution);
        values.put("expectedVersion", expected);
        String sourceStatusSql = "";
        if (requiredSourceStatus != null) {
            values.put("sourceStatus", requiredSourceStatus);
            sourceStatusSql = " AND status = :sourceStatus ";
        }
        StringBuilder ownershipSql = new StringBuilder();
        ownership.ifPresent(lease -> {
            values.putAll(leaseCoordinateMap(lease));
            ownershipSql.append(
                    """
                    AND EXISTS (
                        SELECT 1 FROM crewscope.execution_lease lease
                        WHERE lease.id = :leaseId
                          AND lease.organization_id = execution.organization_id
                          AND lease.task_execution_id = execution.id
                          AND lease.attempt = execution.attempt
                          AND lease.runtime_environment = :environment
                          AND lease.runtime_id = :runtimeId
                          AND lease.worker_id = :workerId
                          AND lease.claim_token_hash = :claimTokenHash
                          AND lease.fencing_token = :fencingToken
                          AND lease.status = 'ACTIVE'
                    )
                    """);
        });
        int affected = jdbc.update(
                """
                UPDATE crewscope.task_execution execution
                SET status = :status,
                    waiting_reason = :waitingReason,
                    waiting_since = :waitingSince,
                    control_request_type = :controlRequestType,
                    control_requested_by_principal_id = :controlRequestedBy,
                    control_requested_at = :controlRequestedAt,
                    control_request_reason = :controlRequestReason,
                    terminal_decided_by_principal_id = :terminalDecidedBy,
                    terminal_decided_at = :terminalDecidedAt,
                    terminal_failure_class = :terminalFailureClass,
                    terminal_failure_code = :terminalFailureCode,
                    last_fencing_token = :lastFencingToken,
                    updated_at = :updatedAt,
                    updated_by_principal_id = :updatedBy,
                    version = :version
                WHERE organization_id = :organizationId
                  AND team_id = :teamId
                  AND workspace_id = :workspaceId
                  AND project_id = :projectId
                  AND task_id = :taskId
                  AND id = :taskExecutionId
                  AND attempt = :attempt
                  AND version = :expectedVersion
                """ + sourceStatusSql + ownershipSql,
                values);
        if (affected == 1) {
            return;
        }
        Optional<Long> actual = jdbc.query(
                        """
                        SELECT version FROM crewscope.task_execution
                        WHERE organization_id = :organizationId AND id = :taskExecutionId
                        """,
                        values,
                        (result, row) -> result.getLong(1))
                .stream().findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("TaskExecution", execution.id());
        }
        if (actual.orElseThrow() != expected) {
            throw new OptimisticLockConflictException(
                    "TaskExecution", execution.id(), expected, actual.orElseThrow());
        }
        throw new DomainValidationException(
                "executionLease.ownership", "must match the current TaskExecution and active Lease");
    }

    private static Map<String, Object> executionParameters(TaskExecution execution) {
        Map<String, Object> values = new HashMap<>();
        values.put("organizationId", execution.scope().organizationId().value());
        values.put("teamId", execution.scope().teamId().value());
        values.put("workspaceId", execution.scope().workspaceId().value());
        values.put("projectId", execution.scope().projectId().value());
        values.put("taskId", execution.taskId().value());
        values.put("taskExecutionId", execution.id().value());
        values.put("attempt", execution.attempt());
        values.put("status", execution.status().name());
        TaskExecutionWaiting waiting = execution.waiting().orElse(null);
        values.put("waitingReason", waiting == null ? null : waiting.reason().name());
        values.put("waitingSince", waiting == null ? null : waiting.waitingSince().toOffsetDateTime());
        TaskExecutionControlRequest request = execution.controlRequest().orElse(null);
        values.put("controlRequestType", request == null ? null : request.type().name());
        values.put("controlRequestedBy", request == null
                ? null : request.requestedByPrincipalId().value());
        values.put("controlRequestedAt", request == null ? null : request.requestedAt().toOffsetDateTime());
        values.put("controlRequestReason", request == null ? null : request.reason());
        TaskExecutionTerminal terminal = execution.terminal().orElse(null);
        values.put("terminalDecidedBy", terminal == null
                ? null : terminal.decidedByPrincipalId().value());
        values.put("terminalDecidedAt", terminal == null ? null : terminal.decidedAt().toOffsetDateTime());
        TaskExecutionFailure failure = terminal == null ? null : terminal.failure().orElse(null);
        values.put("terminalFailureClass", failure == null ? null : failure.failureClass().name());
        values.put("terminalFailureCode", failure == null ? null : failure.code());
        values.put("lastFencingToken", execution.lastFencingToken()
                .map(FencingToken::value).orElse(null));
        values.put("updatedAt", execution.audit().updatedAt().toOffsetDateTime());
        values.put("updatedBy", execution.audit().updatedBy().orElseThrow().value());
        values.put("version", execution.version());
        return values;
    }

    private static MapSqlParameterSource leaseParameters(ExecutionLease lease) {
        ExecutionLeaseRelease release = lease.release().orElse(null);
        return new MapSqlParameterSource(leaseCoordinateMap(lease))
                .addValue("phase", lease.phase().name())
                .addValue("acquiredAt", lease.acquiredAt().toOffsetDateTime())
                .addValue("lastHeartbeatAt", lease.lastHeartbeatAt().toOffsetDateTime())
                .addValue("expiresAt", lease.expiresAt().toOffsetDateTime())
                .addValue("releasedAt", release == null ? null : release.releasedAt().toOffsetDateTime())
                .addValue("releaseReason", release == null ? null : release.reason().name())
                .addValue("leaseVersion", lease.version());
    }

    private static Map<String, Object> leaseCoordinateMap(ExecutionLease lease) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", lease.id().value());
        values.put("leaseId", lease.id().value());
        values.put("organizationId", lease.organizationId().value());
        values.put("environment", lease.environment().value());
        values.put("taskExecutionId", lease.taskExecutionId().value());
        values.put("attempt", lease.attempt());
        values.put("runtimeId", lease.runtimeId().value());
        values.put("workerId", lease.workerId().value());
        values.put("claimTokenHash", lease.claimTokenHash().value());
        values.put("fencingToken", lease.fencingToken().value());
        return values;
    }

    private void requireLeaseMutation(ExecutionLease lease, long expected, int affected) {
        if (affected == 1) {
            return;
        }
        Optional<Long> actual = jdbc.query(
                        """
                        SELECT lease_version FROM crewscope.execution_lease
                        WHERE organization_id = :organizationId
                          AND runtime_environment = :environment
                          AND id = :id
                        """,
                        leaseCoordinateMap(lease),
                        (result, row) -> result.getLong(1))
                .stream().findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("ExecutionLease", lease.id());
        }
        if (actual.orElseThrow() != expected) {
            throw new OptimisticLockConflictException(
                    "ExecutionLease", lease.id(), expected, actual.orElseThrow());
        }
        throw new DomainValidationException(
                "executionLease.ownership", "must match every active Lease coordinate");
    }

    private static long expectedVersion(ExecutionLease lease) {
        if (lease.version() < 1) {
            throw new DomainValidationException(
                    "executionLease.version", "must contain one uncommitted mutation");
        }
        return lease.version() - 1;
    }

    private static void requireLeaseMatchesExecution(
            TaskExecution execution, ExecutionLease lease) {
        boolean matches = execution.scope().organizationId().equals(lease.organizationId())
                && execution.id().equals(lease.taskExecutionId())
                && execution.attempt() == lease.attempt()
                && execution.lastFencingToken().filter(lease.fencingToken()::equals).isPresent();
        if (!matches) {
            throw new DomainValidationException(
                    "executionLease.taskExecutionId",
                    "must match the TaskExecution scope, attempt and fencing token");
        }
    }

    private static ExecutionLease mapLease(ResultSet result, int rowNumber) throws SQLException {
        String releaseReason = result.getString("release_reason");
        return ExecutionLease.reconstitute(
                new ExecutionLeaseId(result.getObject("id", UUID.class)),
                new OrganizationId(result.getObject("organization_id", UUID.class)),
                new RuntimeEnvironment(result.getString("runtime_environment")),
                new TaskExecutionId(result.getObject("task_execution_id", UUID.class)),
                result.getInt("attempt"),
                new ExecutionRuntimeId(result.getObject("runtime_id", UUID.class)),
                new RuntimeWorkerId(result.getObject("worker_id", UUID.class)),
                new ClaimTokenHash(result.getString("claim_token_hash")),
                new FencingToken(result.getLong("fencing_token")),
                ExecutionLeasePhase.valueOf(result.getString("phase")),
                new UtcTimestamp(result.getTimestamp("acquired_at").toInstant()),
                new UtcTimestamp(result.getTimestamp("last_heartbeat_at").toInstant()),
                new UtcTimestamp(result.getTimestamp("expires_at").toInstant()),
                releaseReason == null
                        ? Optional.empty()
                        : Optional.of(new ExecutionLeaseRelease(
                                ExecutionLeaseReleaseReason.valueOf(releaseReason),
                                new UtcTimestamp(result.getTimestamp("released_at").toInstant()))),
                result.getLong("lease_version"));
    }
}
