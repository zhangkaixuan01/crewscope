package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Sanitized failure that never includes host paths, Patch content or raw Git output. */
public final class WorkspaceDiffException extends RuntimeException {

    private final WorkspaceDiffError error;

    WorkspaceDiffException(WorkspaceDiffError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    WorkspaceDiffException(WorkspaceDiffError error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error");
    }

    public WorkspaceDiffError error() {
        return error;
    }
}
