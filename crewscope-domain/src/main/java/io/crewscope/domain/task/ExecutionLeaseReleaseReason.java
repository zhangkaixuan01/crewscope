package io.crewscope.domain.task;

/** Stable reason for relinquishing TaskExecution ownership and runtime capacity. */
public enum ExecutionLeaseReleaseReason {
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED,
    WAITING,
    EXPIRED,
    MANUAL_TAKEOVER,
    WORKER_SHUTDOWN
}
