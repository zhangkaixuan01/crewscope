package io.crewscope.infrastructure.workspace.git;

import java.util.Objects;
import java.util.OptionalInt;

/** Git execution failure that exposes only a stable category and a path-free safe summary. */
public final class GitCommandException extends RuntimeException {

    private final GitCommandError error;
    private final OptionalInt exitCode;

    GitCommandException(GitCommandError error, String safeSummary, OptionalInt exitCode) {
        super(Objects.requireNonNull(safeSummary, "safeSummary"));
        this.error = Objects.requireNonNull(error, "error");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
    }

    GitCommandException(
            GitCommandError error,
            String safeSummary,
            OptionalInt exitCode,
            Throwable cause) {
        super(Objects.requireNonNull(safeSummary, "safeSummary"), cause);
        this.error = Objects.requireNonNull(error, "error");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
    }

    public GitCommandError error() {
        return error;
    }

    public OptionalInt exitCode() {
        return exitCode;
    }
}
