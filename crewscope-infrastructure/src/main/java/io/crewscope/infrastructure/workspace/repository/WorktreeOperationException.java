package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Worktree failure that exposes only a stable category and a safe host-path-free summary. */
public final class WorktreeOperationException extends RuntimeException {

    private final WorktreeOperationError error;

    WorktreeOperationException(WorktreeOperationError error, String safeSummary) {
        super(Objects.requireNonNull(safeSummary, "safeSummary"));
        this.error = Objects.requireNonNull(error, "error");
    }

    public WorktreeOperationError error() {
        return error;
    }
}
