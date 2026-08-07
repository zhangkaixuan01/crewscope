package io.crewscope.application.artifact;

import java.util.Objects;

/** Safe storage-boundary failure that never includes artifact content or credentials. */
public class ArtifactStoreException extends RuntimeException {

    private final ArtifactStoreError error;

    public ArtifactStoreException(ArtifactStoreError error, String safeMessage) {
        super(requireMessage(safeMessage));
        this.error = Objects.requireNonNull(error, "error");
    }

    public ArtifactStoreException(
            ArtifactStoreError error, String safeMessage, Throwable cause) {
        super(requireMessage(safeMessage), Objects.requireNonNull(cause, "cause"));
        this.error = Objects.requireNonNull(error, "error");
    }

    public ArtifactStoreError error() {
        return error;
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value.strip();
    }
}
