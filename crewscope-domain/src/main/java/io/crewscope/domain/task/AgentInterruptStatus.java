package io.crewscope.domain.task;

/** Lifecycle of a durable Agent interruption. */
public enum AgentInterruptStatus {
    PENDING,
    RESOLVED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
