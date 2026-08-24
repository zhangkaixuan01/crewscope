package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Public Repository Catalog item containing no remote URL or raw Provider payload. */
public record GitHubRepositoryView(
        String externalRepositoryId,
        String fullName,
        RepositoryBranchName defaultBranch,
        GitHubRepositoryVisibility visibility,
        UtcTimestamp discoveredAt,
        UtcTimestamp cacheExpiresAt) {

    public GitHubRepositoryView {
        externalRepositoryId = GitHubHash.requireText(externalRepositoryId);
        fullName = GitHubHash.requireText(fullName);
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        Objects.requireNonNull(cacheExpiresAt, "cacheExpiresAt");
    }

    public static GitHubRepositoryView from(GitHubRepositoryCatalogEntry entry) {
        GitHubRepositoryCatalogEntry value = Objects.requireNonNull(entry, "entry");
        return new GitHubRepositoryView(
                value.externalRepositoryId(),
                value.fullName(),
                value.defaultBranch(),
                value.visibility(),
                value.discoveredAt(),
                value.cacheExpiresAt());
    }
}
