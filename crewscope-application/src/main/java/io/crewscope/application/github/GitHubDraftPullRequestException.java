package io.crewscope.application.github;

import java.util.Objects;

/** Safe failure that excludes credentials, Provider bodies, URLs and host paths. */
public final class GitHubDraftPullRequestException extends RuntimeException {

    private final GitHubDraftPullRequestErrorCode code;

    public GitHubDraftPullRequestException(
            GitHubDraftPullRequestErrorCode code, String safeSummary) {
        this(code, safeSummary, null);
    }

    public GitHubDraftPullRequestException(
            GitHubDraftPullRequestErrorCode code, String safeSummary, Throwable cause) {
        super(requireSummary(safeSummary), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public GitHubDraftPullRequestErrorCode code() {
        return code;
    }

    private static String requireSummary(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.length() > 300) {
            throw new IllegalArgumentException("GitHub Draft PR summary is invalid");
        }
        return value;
    }
}
