package io.crewscope.domain.task;

/** Durable state of a serial Step running under its TaskExecution Lease. */
public enum StepExecutionStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED_FINAL || this == SKIPPED || this == CANCELLED;
    }
}
