package io.crewscope.infrastructure.agentscope.snapshot;

/** Fails a Task recovery closed when no trustworthy AgentState snapshot can be restored. */
public final class AgentStateSnapshotRecoveryException extends RuntimeException {

    public AgentStateSnapshotRecoveryException(String safeMessage) {
        super(safeMessage);
    }

    public AgentStateSnapshotRecoveryException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}
