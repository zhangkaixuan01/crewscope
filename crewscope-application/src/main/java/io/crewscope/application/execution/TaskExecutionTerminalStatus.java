package io.crewscope.application.execution;

/** Exactly one terminal closes a finite Task AgentRun Segment event stream. */
public enum TaskExecutionTerminalStatus {
    COMPLETED,
    INTERRUPTED,
    PAUSED,
    CANCELED,
    FAILED
}
