package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Repository Preflight failure that never includes a host path or raw Git output. */
public final class RepositoryPreflightException extends RuntimeException {

    private final RepositoryPreflightError error;

    RepositoryPreflightException(RepositoryPreflightError error, String safeSummary) {
        super(Objects.requireNonNull(safeSummary, "safeSummary"));
        this.error = Objects.requireNonNull(error, "error");
    }

    public RepositoryPreflightError error() {
        return error;
    }
}
