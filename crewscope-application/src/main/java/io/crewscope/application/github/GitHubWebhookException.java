package io.crewscope.application.github;

import java.util.Objects;

/** Safe Webhook rejection that never includes the payload, signature or Secret. */
public final class GitHubWebhookException extends RuntimeException {

    private final GitHubWebhookErrorCode code;

    public GitHubWebhookException(GitHubWebhookErrorCode code, String safeSummary) {
        super(requireSummary(safeSummary));
        this.code = Objects.requireNonNull(code, "code");
    }

    public GitHubWebhookErrorCode code() {
        return code;
    }

    private static String requireSummary(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.length() > 300) {
            throw new IllegalArgumentException("GitHub Webhook summary is invalid");
        }
        return value;
    }
}
