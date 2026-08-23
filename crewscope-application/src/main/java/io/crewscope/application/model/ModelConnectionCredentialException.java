package io.crewscope.application.model;

import java.util.Objects;

/** Stable, sanitized application failure for model credential orchestration. */
public final class ModelConnectionCredentialException extends RuntimeException {

    public enum Error {
        CONNECTION_NOT_FOUND,
        PROVIDER_NOT_FOUND,
        CREDENTIAL_NOT_FOUND,
        CREDENTIAL_MISMATCH,
        CREDENTIAL_UNAVAILABLE
    }

    private final Error error;

    public ModelConnectionCredentialException(Error error, String safeMessage) {
        super(requireText(safeMessage));
        this.error = Objects.requireNonNull(error, "error");
    }

    public Error error() {
        return error;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value.strip();
    }
}
