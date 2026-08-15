package io.crewscope.application.execution;

/** Stable reason for excluding one committed AgentStateSnapshot recovery candidate. */
public enum TaskAgentStateSkipReason {
    MISSING,
    INTEGRITY_VIOLATION,
    METADATA_MISMATCH,
    INVALID_ENVELOPE
}
