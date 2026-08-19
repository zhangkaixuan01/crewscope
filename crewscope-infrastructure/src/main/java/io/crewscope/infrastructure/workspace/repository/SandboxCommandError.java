package io.crewscope.infrastructure.workspace.repository;

/** Stable safe error classes for the structured command facade. */
public enum SandboxCommandError {
    INVALID_CONTEXT,
    INVALID_REQUEST,
    COMMAND_NOT_ALLOWED,
    SELECTOR_NOT_ALLOWED,
    BUDGET_EXCEEDED,
    EVIDENCE_PUBLICATION_FAILED
}
