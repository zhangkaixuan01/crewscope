package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Safe failure carrying no host path, Sandbox output or repository content. */
public final class CodingFilesystemException extends RuntimeException {

    private final CodingFilesystemError error;

    CodingFilesystemException(CodingFilesystemError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    CodingFilesystemException(
            CodingFilesystemError error, String message, Throwable ignoredCause) {
        // This boundary intentionally severs infrastructure causes: IOException and Sandbox
        // failures may carry host paths, command fragments, or repository content.
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    public CodingFilesystemError error() {
        return error;
    }
}
