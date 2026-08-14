package io.crewscope.domain.task;

/** Resource preparation uses a short lease before the renewable run lease. */
public enum ExecutionLeasePhase {
    PREPARE,
    RUN
}
