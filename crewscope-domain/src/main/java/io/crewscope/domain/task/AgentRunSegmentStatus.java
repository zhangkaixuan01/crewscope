package io.crewscope.domain.task;

/** Lifecycle of one finite Agent event stream Segment. */
public enum AgentRunSegmentStatus {
    ACTIVE,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
