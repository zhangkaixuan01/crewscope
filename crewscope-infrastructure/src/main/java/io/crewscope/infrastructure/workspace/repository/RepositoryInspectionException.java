package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Fail-closed repository inspection error whose message never contains a host path. */
public final class RepositoryInspectionException extends RuntimeException {

    private final RepositoryInspectionError error;

    RepositoryInspectionException(RepositoryInspectionError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    RepositoryInspectionException(
            RepositoryInspectionError error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error");
    }

    public RepositoryInspectionError error() {
        return error;
    }
}
