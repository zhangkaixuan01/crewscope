package io.crewscope.application.credential;

import java.util.Objects;

/** Credential boundary failure whose message never contains plaintext or cryptographic details. */
public class CredentialStoreException extends RuntimeException {

    private final CredentialStoreError error;

    public CredentialStoreException(CredentialStoreError error, String safeMessage) {
        super(requireMessage(safeMessage));
        this.error = Objects.requireNonNull(error, "error");
    }

    public CredentialStoreException(
            CredentialStoreError error, String safeMessage, Throwable cause) {
        super(requireMessage(safeMessage), Objects.requireNonNull(cause, "cause"));
        this.error = Objects.requireNonNull(error, "error");
    }

    public CredentialStoreError error() {
        return error;
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value.strip();
    }
}
