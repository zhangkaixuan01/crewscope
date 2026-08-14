package io.crewscope.domain.task;

/** Durable lifecycle of one logical Agent run across one or more finite stream Segments. */
public enum AgentRunStatus {
    RUNNING,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
