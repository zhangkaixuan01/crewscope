package io.crewscope.domain.task;

/** Selection state of committed AgentStateSnapshot metadata. */
public enum AgentStateSnapshotStatus {
    CURRENT,
    SUPERSEDED,
    INVALID;

    public boolean isRecoveryCandidate() {
        return this != INVALID;
    }
}
