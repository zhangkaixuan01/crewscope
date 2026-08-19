package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFailure;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC adapter for versioned Workspaces and PostgreSQL recovery/retention row claims. */
@Repository
public class JdbcExecutionWorkspaceRepositoryAdapter implements ExecutionWorkspaceRepository {

    private static final String SELECT = "SELECT * FROM crewscope.execution_workspace";

    private final NamedParameterJdbcTemplate jdbc;
    private final CodingPersistenceMapper mapper;

    public JdbcExecutionWorkspaceRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, CodingPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public ExecutionWorkspace create(ExecutionWorkspace workspace) {
        ExecutionWorkspace required = Objects.requireNonNull(workspace, "workspace");
        if (required.version() != 0) {
            throw new DomainValidationException(
                    "executionWorkspace.version", "must be zero when created");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.execution_workspace (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt,
                        coding_target_snapshot_id, coding_target_revision, coding_target_hash,
                        repository_binding_id, repository_binding_version, repository_key,
                        baseline_commit, workspace_key, managed_branch, archive_reference,
                        runtime_environment, runtime_id, worker_id, execution_lease_id, fencing_token,
                        status, recovery_target_status, recovery_generation,
                        completion_reason, failure_code, retain_until, workspace_fingerprint,
                        version, created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :teamId, :workspaceId, :projectId,
                        :taskId, :taskExecutionId, :attempt,
                        :codingTargetId, :codingTargetRevision, :codingTargetHash,
                        :repositoryBindingId, :repositoryBindingVersion, :repositoryKey,
                        :baselineCommit, :workspaceKey, :managedBranch, :archiveReference,
                        :runtimeEnvironment, :runtimeId, :workerId, :leaseId, :fencingToken,
                        :status, :recoveryTargetStatus, :recoveryGeneration,
                        :completionReason, :failureCode, :retainUntil, :fingerprint,
                        :version, :createdAt, :createdBy, :updatedAt, :updatedBy
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw CodingPersistenceConflictMapper.executionWorkspace(failure, required);
        }
        return findById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().projectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("ExecutionWorkspace", required.id()));
    }

    @Override
    @Transactional
    public ExecutionWorkspace update(ExecutionWorkspace workspace) {
        ExecutionWorkspace required = Objects.requireNonNull(workspace, "workspace");
        long expectedVersion = required.version() - 1;
        if (expectedVersion < 0) {
            throw new DomainValidationException(
                    "executionWorkspace.version", "must contain one uncommitted mutation");
        }
        int affected = jdbc.update(
                """
                UPDATE crewscope.execution_workspace
                   SET runtime_environment = :runtimeEnvironment,
                       runtime_id = :runtimeId,
                       worker_id = :workerId,
                       execution_lease_id = :leaseId,
                       fencing_token = :fencingToken,
                       status = :status,
                       recovery_target_status = :recoveryTargetStatus,
                       recovery_generation = :recoveryGeneration,
                       completion_reason = :completionReason,
                       failure_code = :failureCode,
                       retain_until = :retainUntil,
                       workspace_fingerprint = :fingerprint,
                       version = :version,
                       updated_at = :updatedAt,
                       updated_by_principal_id = :updatedBy
                 WHERE organization_id = :organizationId
                   AND team_id = :teamId
                   AND workspace_id = :workspaceId
                   AND project_id = :projectId
                   AND id = :id
                   AND version = :expectedVersion
                """,
                parameters(required).addValue("expectedVersion", expectedVersion));
        if (affected == 0) {
            throwVersionConflict(required, expectedVersion);
        }
        return findById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().projectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("ExecutionWorkspace", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionWorkspace> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId) {
        return query(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND id = :id
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("id", Objects.requireNonNull(workspaceId).value()))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionWorkspace> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return query(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND task_execution_id = :taskExecutionId
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("taskExecutionId", Objects.requireNonNull(taskExecutionId).value()))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ExecutionWorkspace> findByTaskExecutionForUpdate(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return query(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND task_execution_id = :taskExecutionId
                        FOR UPDATE
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("taskExecutionId", Objects.requireNonNull(taskExecutionId).value()))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionWorkspace> findByWorkspaceKey(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionWorkspaceKey workspaceKey) {
        return query(
                        """
                        WHERE organization_id = :organizationId
                          AND runtime_environment = :runtimeEnvironment
                          AND workspace_key = :workspaceKey
                        """,
                        new MapSqlParameterSource()
                                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                                .addValue("runtimeEnvironment", Objects.requireNonNull(environment).value())
                                .addValue("workspaceKey", Objects.requireNonNull(workspaceKey).value()))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExecutionWorkspace> findRecoveringForUpdate(
            OrganizationId organizationId, RuntimeEnvironment environment, int limit) {
        requireLimit(limit);
        return query(
                """
                WHERE organization_id = :organizationId
                  AND runtime_environment = :runtimeEnvironment
                  AND status = 'RECOVERING'
                ORDER BY updated_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                        .addValue("runtimeEnvironment", Objects.requireNonNull(environment).value())
                        .addValue("limit", limit));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExecutionWorkspace> findRetentionDueForUpdate(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            UtcTimestamp authoritativeNow,
            int limit) {
        requireLimit(limit);
        return query(
                """
                WHERE organization_id = :organizationId
                  AND runtime_environment = :runtimeEnvironment
                  AND status IN ('COMPLETED', 'FAILED')
                  AND retain_until <= :authoritativeNow
                ORDER BY retain_until ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                        .addValue("runtimeEnvironment", Objects.requireNonNull(environment).value())
                        .addValue("authoritativeNow", CodingJdbcValue.timestamp(authoritativeNow))
                        .addValue("limit", limit));
    }

    private List<ExecutionWorkspace> query(
            String predicate, MapSqlParameterSource parameters) {
        return jdbc.query(
                SELECT + " " + predicate,
                parameters,
                (row, ignored) -> mapper.executionWorkspace(row));
    }

    private void throwVersionConflict(ExecutionWorkspace workspace, long expectedVersion) {
        List<Long> versions = jdbc.query(
                """
                SELECT version FROM crewscope.execution_workspace
                 WHERE organization_id = :organizationId
                   AND team_id = :teamId
                   AND workspace_id = :workspaceId
                   AND project_id = :projectId
                   AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", workspace.scope().organizationId().value())
                        .addValue("teamId", workspace.scope().teamId().value())
                        .addValue("workspaceId", workspace.scope().workspaceId().value())
                        .addValue("projectId", workspace.scope().projectId().value())
                        .addValue("id", workspace.id().value()),
                (row, ignored) -> row.getLong("version"));
        if (versions.isEmpty()) {
            throw new AggregateNotFoundException("ExecutionWorkspace", workspace.id());
        }
        throw new OptimisticLockConflictException(
                "ExecutionWorkspace", workspace.id(), expectedVersion, versions.get(0));
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new DomainValidationException("limit", "must be from 1 to 1000");
        }
    }

    private static MapSqlParameterSource scopeParameters(
            OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                .addValue("teamId", Objects.requireNonNull(teamId).value())
                .addValue("projectId", Objects.requireNonNull(workProjectId).value());
    }

    private static MapSqlParameterSource parameters(ExecutionWorkspace workspace) {
        PrincipalId createdBy = workspace.audit().createdBy().orElseThrow(() ->
                new DomainValidationException(
                        "executionWorkspace.createdByPrincipalId", "must identify a Principal"));
        PrincipalId updatedBy = workspace.audit().updatedBy().orElse(createdBy);
        return new MapSqlParameterSource()
                .addValue("id", workspace.id().value())
                .addValue("organizationId", workspace.scope().organizationId().value())
                .addValue("teamId", workspace.scope().teamId().value())
                .addValue("workspaceId", workspace.scope().workspaceId().value())
                .addValue("projectId", workspace.scope().projectId().value())
                .addValue("taskId", workspace.taskId().value())
                .addValue("taskExecutionId", workspace.taskExecutionId().value())
                .addValue("attempt", workspace.attempt())
                .addValue("codingTargetId", workspace.codingTarget().snapshotId().value())
                .addValue("codingTargetRevision", workspace.codingTarget().revision())
                .addValue("codingTargetHash", workspace.codingTarget().snapshotHash().value())
                .addValue("repositoryBindingId", workspace.repositoryBindingId().value())
                .addValue("repositoryBindingVersion", workspace.repositoryBindingVersion())
                .addValue("repositoryKey", workspace.repositoryKey().value())
                .addValue("baselineCommit", workspace.baselineCommit().value())
                .addValue("workspaceKey", workspace.workspaceKey().value())
                .addValue("managedBranch", workspace.managedBranch().value())
                .addValue("archiveReference", workspace.archiveReference().value())
                .addValue("runtimeEnvironment", workspace.ownership().environment().value())
                .addValue("runtimeId", workspace.ownership().runtimeId().value())
                .addValue("workerId", workspace.ownership().workerId().value())
                .addValue("leaseId", workspace.ownership().leaseId().value())
                .addValue("fencingToken", workspace.ownership().fencingToken().value())
                .addValue("status", workspace.status().name())
                .addValue("recoveryTargetStatus", workspace.recoveryTargetStatus()
                        .map(Enum::name).orElse(null))
                .addValue("recoveryGeneration", workspace.recoveryGeneration())
                .addValue("completionReason", workspace.completionReason()
                        .map(Enum::name).orElse(null))
                .addValue("failureCode", workspace.failure()
                        .map(ExecutionWorkspaceFailure::code).orElse(null))
                .addValue("retainUntil", CodingJdbcValue.timestamp(workspace.retention().retainUntil()))
                .addValue("fingerprint", workspace.fingerprint().value())
                .addValue("version", workspace.version())
                .addValue("createdAt", CodingJdbcValue.timestamp(workspace.audit().createdAt()))
                .addValue("createdBy", createdBy.value())
                .addValue("updatedAt", CodingJdbcValue.timestamp(workspace.audit().updatedAt()))
                .addValue("updatedBy", updatedBy.value());
    }
}
