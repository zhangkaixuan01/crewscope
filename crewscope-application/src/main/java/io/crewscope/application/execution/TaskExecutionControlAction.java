package io.crewscope.application.execution;

/** Explicit business control propagated independently from event-stream transport cancellation. */
public enum TaskExecutionControlAction {
    PAUSE,
    RESUME,
    CANCEL
}
