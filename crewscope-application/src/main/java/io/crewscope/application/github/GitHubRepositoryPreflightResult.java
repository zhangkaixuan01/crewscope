package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import java.util.Objects;

/** Current repository authority returned without tokens, endpoint or raw Provider payload. */
public record GitHubRepositoryPreflightResult(
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        String externalRepositoryId,
        String fullName,
        RepositoryBranchName defaultBranch,
        String permissionsHash) {

    public GitHubRepositoryPreflightResult {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(grantId, "grantId");
        if (connectionVersion < 0 || grantVersion < 0) {
            throw new IllegalArgumentException("GitHub Preflight versions must not be negative");
        }
        externalRepositoryId = GitHubHash.requireText(externalRepositoryId);
        fullName = GitHubHash.requireText(fullName);
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        permissionsHash = GitHubHash.requireHash(permissionsHash, "permissionsHash");
    }
}
