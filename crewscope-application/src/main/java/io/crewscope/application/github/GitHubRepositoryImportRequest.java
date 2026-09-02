package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import java.util.Objects;

/** Trusted Worker request assembled from a verified Catalog entry and Connection coordinates. */
public record GitHubRepositoryImportRequest(
        GitHubAccessRequest access,
        String externalRepositoryId,
        String repositoryFullName,
        RepositoryKey repositoryKey,
        RepositoryBranchName defaultBranch) {
    public GitHubRepositoryImportRequest {
        Objects.requireNonNull(access, "access");
        externalRepositoryId = requireText(externalRepositoryId, "externalRepositoryId");
        repositoryFullName = requireText(repositoryFullName, "repositoryFullName");
        Objects.requireNonNull(repositoryKey, "repositoryKey");
        Objects.requireNonNull(defaultBranch, "defaultBranch");
    }
    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > 511 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
