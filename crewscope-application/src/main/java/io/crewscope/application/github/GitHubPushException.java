package io.crewscope.application.github;

import java.util.Objects;

/** Safe exception that never includes Git output, a remote URL, a host path or a credential. */
public final class GitHubPushException extends RuntimeException {

    private final GitHubPushErrorCode code;

    public GitHubPushException(GitHubPushErrorCode code, String safeSummary) {
        this(code, safeSummary, null);
    }

    public GitHubPushException(
            GitHubPushErrorCode code, String safeSummary, Throwable cause) {
        super(requireSummary(safeSummary), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public GitHubPushErrorCode code() {
        return code;
    }

    private static String requireSummary(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("GitHub Push summary must be non-blank");
        }
        return value;
    }
}
