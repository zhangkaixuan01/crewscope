package io.crewscope.application.execution;

/** Exactly one of these states terminates each invoke or resume event stream. */
public enum ExecutionTerminalStatus {
    COMPLETED,
    INTERRUPTED,
    CANCELED,
    FAILED
}
