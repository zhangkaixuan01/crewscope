package io.crewscope.application.execution;

/** Idempotent result returned after propagating a Task runtime control request. */
public enum TaskExecutionControlResult {
    ACCEPTED,
    ALREADY_APPLIED,
    ALREADY_TERMINAL,
    STALE_OWNER,
    NOT_FOUND
}
