package io.crewscope.application.execution;

/** Runtime observation only; domain state transitions remain owned by CrewScope services. */
public enum TaskExecutionRuntimePhase {
    PLANNING,
    EXECUTING,
    WAITING_APPROVAL,
    PAUSING,
    RESUMING,
    CANCELLING
}
