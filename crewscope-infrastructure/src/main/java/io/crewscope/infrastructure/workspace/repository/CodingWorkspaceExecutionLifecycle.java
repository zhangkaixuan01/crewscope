package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.application.execution.TaskExecutionTerminalStatus;
import java.util.Optional;

/** Optional Coding resource participant in the generic Durable Worker lifecycle. */
public interface CodingWorkspaceExecutionLifecycle {

    CodingWorkspaceExecutionLifecycle NOOP = new CodingWorkspaceExecutionLifecycle() {};

    default Optional<CodingWorkspaceExecution> prepare(
            TaskExecution execution, ExecutionLease lease, PolicySnapshot policy) {
        return Optional.empty();
    }

    default void activate(
            CodingWorkspaceExecution execution,
            TaskExecution runningExecution,
            ExecutionLease runLease) {}

    default void beforeRelease(
            CodingWorkspaceExecution execution,
            TaskExecution currentExecution,
            ExecutionLease lease,
            TaskExecutionTerminalStatus terminalStatus) {}

    default void afterRelease(
            CodingWorkspaceExecution execution,
            TaskExecution terminalExecution,
            TaskExecutionTerminalStatus terminalStatus) {}

    default void abandon(CodingWorkspaceExecution execution) {}
}
