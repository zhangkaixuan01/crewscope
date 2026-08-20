package io.crewscope.application.coding;

import java.util.Objects;

/** Safe application failure that never carries a host path or raw Git output. */
public final class RepositoryBindingPreflightException extends RuntimeException {

    private final RepositoryBindingPreflightError error;

    public RepositoryBindingPreflightException(
            RepositoryBindingPreflightError error, String safeMessage) {
        super(requireMessage(safeMessage));
        this.error = Objects.requireNonNull(error, "error");
    }

    public RepositoryBindingPreflightError error() {
        return error;
    }

    public boolean retryable() {
        return error == RepositoryBindingPreflightError.SERVICE_UNAVAILABLE
                || error == RepositoryBindingPreflightError.COMMAND_FAILED;
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value;
    }
}
