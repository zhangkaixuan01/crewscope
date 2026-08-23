package io.crewscope.application.github;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Query-only observation of the exact confirmed Draft Pull Request coordinates. */
public record GitHubDraftPullRequestQueryResult(
        Optional<GitHubDraftPullRequestResult> pullRequest, UtcTimestamp observedAt) {

    public GitHubDraftPullRequestQueryResult {
        pullRequest = Objects.requireNonNull(pullRequest, "pullRequest");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
