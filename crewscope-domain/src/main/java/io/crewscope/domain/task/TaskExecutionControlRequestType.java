package io.crewscope.domain.task;

/** User or platform control request awaiting convergence at a safe execution point. */
public enum TaskExecutionControlRequestType {
    PAUSE,
    CANCEL
}
