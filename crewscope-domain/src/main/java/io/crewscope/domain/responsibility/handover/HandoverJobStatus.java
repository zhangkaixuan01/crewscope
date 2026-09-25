package io.crewscope.domain.responsibility.handover;

/** Lifecycle of one handover job; terminal states are COMPLETED and CANCELLED. */
public enum HandoverJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED
}
