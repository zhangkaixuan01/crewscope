package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for durable Task execution attempts. */
public interface TaskExecutionRepository {

    /** Inserts an execution attempt at version zero. */
    TaskExecution create(TaskExecution execution);

    /** Commits a state or scheduling mutation using the previous version as lock predicate. */
    TaskExecution update(TaskExecution execution);

    Optional<TaskExecution> findById(
            OrganizationId organizationId, TaskExecutionId executionId);

    /** Returns every historical attempt for one Task ordered by ascending attempt number. */
    List<TaskExecution> findByTask(OrganizationId organizationId, TaskId taskId);

    /**
     * Locks a bounded RECOVERING batch for startup reconciliation.
     *
     * <p>The caller must keep an outer transaction open through orphan cleanup and requeue.
     */
    List<TaskExecution> findRecoveringForUpdate(OrganizationId organizationId, int limit);
}
