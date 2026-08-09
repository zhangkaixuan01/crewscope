package io.crewscope.application.execution;

/** Idempotent result of propagating one explicit runtime cancellation. */
public enum ExecutionCancelResult {
    ACCEPTED,
    ALREADY_TERMINAL,
    NOT_FOUND
}
