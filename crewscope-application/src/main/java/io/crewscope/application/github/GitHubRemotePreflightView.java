package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBranchName;
import java.util.Objects;

/** Whitelisted remote Preflight result; internal Grant coordinates stay server-side. */
public record GitHubRemotePreflightView(
        long connectionVersion,
        String externalRepositoryId,
        String fullName,
        RepositoryBranchName defaultBranch,
        String permissionsHash) {

    public GitHubRemotePreflightView {
        if (connectionVersion < 0) {
            throw new IllegalArgumentException("connectionVersion must not be negative");
        }
        externalRepositoryId = GitHubHash.requireText(externalRepositoryId);
        fullName = GitHubHash.requireText(fullName);
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        permissionsHash = GitHubHash.requireHash(permissionsHash, "permissionsHash");
    }

    public static GitHubRemotePreflightView from(GitHubRepositoryPreflightResult result) {
        GitHubRepositoryPreflightResult value = Objects.requireNonNull(result, "result");
        return new GitHubRemotePreflightView(
                value.connectionVersion(),
                value.externalRepositoryId(),
                value.fullName(),
                value.defaultBranch(),
                value.permissionsHash());
    }
}
