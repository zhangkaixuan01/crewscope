package io.crewscope.application.github;

import java.util.Objects;

/** Exception whose message is intentionally safe for application and audit boundaries. */
public final class GitHubProviderException extends RuntimeException {

    private final GitHubProviderErrorCode code;

    public GitHubProviderException(GitHubProviderErrorCode code, String safeSummary) {
        super(requireSummary(safeSummary));
        this.code = Objects.requireNonNull(code, "code");
    }

    public GitHubProviderErrorCode code() {
        return code;
    }

    private static String requireSummary(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 300) {
            throw new IllegalArgumentException("safeSummary must contain at most 300 characters");
        }
        return value.strip();
    }
}
