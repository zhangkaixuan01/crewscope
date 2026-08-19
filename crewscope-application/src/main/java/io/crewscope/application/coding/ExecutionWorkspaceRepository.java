package io.crewscope.application.coding;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceAttemptConflictException;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for durable, attempt-scoped ExecutionWorkspace lifecycle facts. */
public interface ExecutionWorkspaceRepository {

    /**
     * Inserts one Workspace and atomically rejects another Workspace for the same attempt with
     * {@link ExecutionWorkspaceAttemptConflictException}.
     */
    ExecutionWorkspace create(ExecutionWorkspace workspace);

    /** Updates one Workspace using its previous aggregate version as the lock predicate. */
    ExecutionWorkspace update(ExecutionWorkspace workspace);

    Optional<ExecutionWorkspace> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId);

    Optional<ExecutionWorkspace> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId);

    /** Locks the attempt Workspace when startup recovery mutates it with TaskExecution. */
    default Optional<ExecutionWorkspace> findByTaskExecutionForUpdate(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return findByTaskExecution(
                organizationId, teamId, workProjectId, taskExecutionId);
    }

    /** Resolves one host-managed Workspace without exposing its physical path. */
    Optional<ExecutionWorkspace> findByWorkspaceKey(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionWorkspaceKey workspaceKey);

    /** Locks a bounded recovery batch inside a caller-owned transaction. */
    List<ExecutionWorkspace> findRecoveringForUpdate(
            OrganizationId organizationId, RuntimeEnvironment environment, int limit);

    /** Locks due completed or failed Workspaces inside a caller-owned transaction. */
    List<ExecutionWorkspace> findRetentionDueForUpdate(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            UtcTimestamp authoritativeNow,
            int limit);
}
