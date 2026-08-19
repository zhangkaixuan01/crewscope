package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Sanitized failure that never retains command output, container identity or host paths. */
public final class SandboxCommandException extends RuntimeException {

    private final SandboxCommandError error;

    SandboxCommandException(SandboxCommandError error, String message) {
        super(message, null, false, false);
        this.error = Objects.requireNonNull(error, "error");
    }

    public SandboxCommandError error() {
        return error;
    }
}
