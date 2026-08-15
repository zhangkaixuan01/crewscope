package io.crewscope.application.task;

/** Fixed low-cardinality Lease operation label. */
public enum LeaseCoordinatorOperation {
    PREPARE,
    BEGIN_RUN,
    HEARTBEAT,
    OWNED_UPDATE,
    RELEASE,
    SWEEP
}
