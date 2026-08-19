package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Safe boundary exception that never includes Artifact content or storage locations. */
public class CodingArtifactException extends RuntimeException {

    private final CodingArtifactError error;

    CodingArtifactException(CodingArtifactError error, String safeMessage) {
        super(requireMessage(safeMessage));
        this.error = Objects.requireNonNull(error, "error");
    }

    CodingArtifactException(CodingArtifactError error, String safeMessage, Throwable cause) {
        super(requireMessage(safeMessage), Objects.requireNonNull(cause, "cause"));
        this.error = Objects.requireNonNull(error, "error");
    }

    public CodingArtifactError error() {
        return error;
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value.strip();
    }
}
