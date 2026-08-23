package io.crewscope.application.github;

import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.net.URI;
import java.util.Objects;

/** Secret-free exact Pull Request facts returned to the Action Worker. */
public record GitHubDraftPullRequestResult(
        GitHubDraftPullRequestOutcome outcome,
        ConnectionId connectionId,
        ExternalRepositoryId repositoryId,
        String pullRequestId,
        long number,
        URI webUrl,
        RepositoryBranchName head,
        RepositoryBranchName base,
        RepositoryCommitId headSha,
        String titleHash,
        String bodyHash,
        boolean draft,
        GitHubPullRequestState state,
        UtcTimestamp providerUpdatedAt) {

    public GitHubDraftPullRequestResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId");
        pullRequestId = requireNumeric(pullRequestId, "pullRequestId");
        if (number < 1) {
            throw new IllegalArgumentException("GitHub Pull Request number must be positive");
        }
        webUrl = Objects.requireNonNull(webUrl, "webUrl");
        head = Objects.requireNonNull(head, "head");
        base = Objects.requireNonNull(base, "base");
        headSha = Objects.requireNonNull(headSha, "headSha");
        titleHash = GitHubHash.requireHash(titleHash, "titleHash");
        bodyHash = GitHubHash.requireHash(bodyHash, "bodyHash");
        state = Objects.requireNonNull(state, "state");
        providerUpdatedAt = Objects.requireNonNull(providerUpdatedAt, "providerUpdatedAt");
    }

    public ExternalResultIdentity externalIdentity() {
        return new ExternalResultIdentity(
                connectionId,
                io.crewscope.domain.action.ExternalObjectType.PULL_REQUEST,
                pullRequestId,
                repositoryId.value() + ":pull-request:" + number);
    }

    private static String requireNumeric(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("GitHub " + field + " must be a positive numeric ID");
        }
        return value;
    }
}
