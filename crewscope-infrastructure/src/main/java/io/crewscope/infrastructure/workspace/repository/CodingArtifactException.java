package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;
import java.util.OptionalLong;

/** Safe boundary exception that never includes Artifact content or storage locations. */
public class CodingArtifactException extends RuntimeException {

    private final CodingArtifactError error;
    private final OptionalLong totalSize;

    public CodingArtifactException(CodingArtifactError error, String safeMessage) {
        super(requireMessage(safeMessage));
        this.error = Objects.requireNonNull(error, "error");
        this.totalSize = OptionalLong.empty();
    }

    public CodingArtifactException(CodingArtifactError error, String safeMessage, Throwable cause) {
        super(requireMessage(safeMessage), Objects.requireNonNull(cause, "cause"));
        this.error = Objects.requireNonNull(error, "error");
        this.totalSize = OptionalLong.empty();
    }

    CodingArtifactException(
            CodingArtifactError error, String safeMessage, long totalSize, Throwable cause) {
        super(requireMessage(safeMessage), Objects.requireNonNull(cause, "cause"));
        this.error = Objects.requireNonNull(error, "error");
        if (totalSize < 0) {
            throw new IllegalArgumentException("totalSize must not be negative");
        }
        this.totalSize = OptionalLong.of(totalSize);
    }

    public CodingArtifactError error() {
        return error;
    }

    public OptionalLong totalSize() {
        return totalSize;
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value.strip();
    }
}
