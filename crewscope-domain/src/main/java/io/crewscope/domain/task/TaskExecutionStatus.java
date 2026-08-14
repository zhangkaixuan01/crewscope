package io.crewscope.domain.task;

/** Durable lifecycle of one Task execution attempt, independent from the Task business status. */
public enum TaskExecutionStatus {
    CREATED,
    READY,
    CLAIMED,
    PREPARING,
    RUNNING,
    WAITING,
    PAUSE_REQUESTED,
    PAUSED,
    RECOVERING,
    CANCEL_REQUESTED,
    MANUAL_TAKEOVER,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** Returns whether no further mutation is allowed on this historical attempt. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
