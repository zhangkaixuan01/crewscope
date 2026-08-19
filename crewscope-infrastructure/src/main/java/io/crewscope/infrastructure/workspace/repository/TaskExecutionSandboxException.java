package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Docker Sandbox failure exposing only a stable category and safe summary. */
public final class TaskExecutionSandboxException extends RuntimeException {

    private final TaskExecutionSandboxError error;

    TaskExecutionSandboxException(TaskExecutionSandboxError error, String safeSummary) {
        super(Objects.requireNonNull(safeSummary, "safeSummary"));
        this.error = Objects.requireNonNull(error, "error");
    }

    public TaskExecutionSandboxError error() {
        return error;
    }
}
