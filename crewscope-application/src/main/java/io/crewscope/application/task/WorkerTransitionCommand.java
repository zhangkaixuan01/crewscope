package io.crewscope.application.task;

/** Expected TaskExecution and ExecutionLease versions for an atomic Worker transition. */
public record WorkerTransitionCommand(
        long expectedExecutionVersion, long expectedLeaseVersion) {
    public WorkerTransitionCommand {
        if (expectedExecutionVersion < 0 || expectedLeaseVersion < 0) {
            throw new IllegalArgumentException("expected versions must not be negative");
        }
    }
}
