package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shares one live Coding Workspace between the Worker lifecycle and Specialist Gateway. */
public final class CodingWorkspaceRuntimeRegistry {

    private final ConcurrentMap<TaskExecutionId, CodingWorkspaceExecution> executions =
            new ConcurrentHashMap<>();

    void register(CodingWorkspaceExecution execution) {
        CodingWorkspaceExecution required = Objects.requireNonNull(execution, "execution");
        CodingWorkspaceExecution previous = executions.putIfAbsent(
                required.workspace().taskExecutionId(), required);
        if (previous != null && previous != required) {
            throw new IllegalStateException("Coding Workspace is already active in this Worker");
        }
    }

    public Optional<CodingWorkspaceExecution> find(TaskExecutionId executionId) {
        return Optional.ofNullable(executions.get(Objects.requireNonNull(executionId, "executionId")));
    }

    /** Returns an identity-private point-in-time copy for local health aggregation. */
    public List<CodingWorkspaceExecution> snapshot() {
        return List.copyOf(executions.values());
    }

    void forget(CodingWorkspaceExecution execution) {
        executions.remove(execution.workspace().taskExecutionId(), execution);
    }
}
