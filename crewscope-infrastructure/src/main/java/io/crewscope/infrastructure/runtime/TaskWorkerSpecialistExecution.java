package io.crewscope.infrastructure.runtime;

import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Optional Worker extension that executes a durable Specialist Step before Lease release. */
public interface TaskWorkerSpecialistExecution {

    TaskWorkerSpecialistExecution NOOP = (prepared, taskTerminal) -> taskTerminal;

    /**
     * Runs a Specialist only when the prepared execution owns a matching delegated workload.
     * The returned terminal event controls the TaskExecution Lease release; the Specialist writes
     * its own AgentRun events and checkpoints through its durable execution store.
     */
    TaskExecutionEvent executeAfterTaskAgent(
            TaskWorkerPreparedExecution prepared, TaskExecutionEvent taskTerminal);

    /**
     * Routes a durable member request to the active Specialist when Task planning has finished.
     * An empty result means that no Specialist Session currently owns this execution.
     */
    default Optional<TaskExecutionControlResult> controlTask(
            TaskWorkerPreparedExecution prepared,
            TaskExecutionControlAction action,
            UUID controlRequestId,
            String reason) {
        return Optional.empty();
    }

    static TaskExecutionEvent requireTerminal(TaskExecutionEvent event) {
        TaskExecutionEvent required = Objects.requireNonNull(event, "event");
        if (!required.terminal()) {
            throw new IllegalArgumentException("Specialist execution must return a terminal event");
        }
        return required;
    }
}
