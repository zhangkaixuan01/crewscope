package io.crewscope.application.github;

import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import java.util.Objects;

/** Secret-free receipt facts returned to the Action Worker. */
public record GitHubPushResult(
        GitHubPushOutcome outcome,
        ExternalRepositoryId repositoryId,
        RepositoryBranchReference branch,
        RepositoryCommitId deliveryHead) {

    public GitHubPushResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(deliveryHead, "deliveryHead");
    }
}
