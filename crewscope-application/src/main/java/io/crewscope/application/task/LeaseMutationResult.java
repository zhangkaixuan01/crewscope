package io.crewscope.application.task;

import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.TaskExecution;
import java.util.Objects;

/** Atomic result when a command advances both TaskExecution and ExecutionLease. */
public record LeaseMutationResult(TaskExecution execution, ExecutionLease lease) {

    public LeaseMutationResult {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(lease, "lease");
    }
}
