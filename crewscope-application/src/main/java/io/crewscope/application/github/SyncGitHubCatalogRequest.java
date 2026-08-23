package io.crewscope.application.github;

import java.util.Objects;

/** Refreshes repositories for an already verified GitHub profile. */
public record SyncGitHubCatalogRequest(
        GitHubAccessRequest access, GitHubRepositoryPolicy repositoryPolicy) {

    public SyncGitHubCatalogRequest {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(repositoryPolicy, "repositoryPolicy");
    }
}
