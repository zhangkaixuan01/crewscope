package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBranchName;
import java.util.Objects;

/** Revalidates one selected repository against current remote and persisted authority. */
public record PreflightGitHubRepositoryRequest(
        GitHubAccessRequest access,
        String externalRepositoryId,
        RepositoryBranchName expectedDefaultBranch,
        GitHubRepositoryPolicy repositoryPolicy) {

    public PreflightGitHubRepositoryRequest {
        Objects.requireNonNull(access, "access");
        externalRepositoryId = GitHubHash.requireText(externalRepositoryId);
        if (externalRepositoryId.length() > 100) {
            throw new IllegalArgumentException("externalRepositoryId exceeds its maximum length");
        }
        Objects.requireNonNull(expectedDefaultBranch, "expectedDefaultBranch");
        Objects.requireNonNull(repositoryPolicy, "repositoryPolicy");
    }
}
