package io.crewscope.application.runtime;

/** Current actionable diagnosis for an execution persisted as WAITING_RUNTIME. */
public enum RuntimeWaitCause {
    CAPABILITY_UNAVAILABLE,
    NO_ACTIVE_WORKER,
    HEARTBEAT_STALE,
    DRAINING,
    CAPACITY_EXHAUSTED,
    REQUEUE_PENDING
}
