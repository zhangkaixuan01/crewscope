package io.crewscope.application.github;

import java.util.Objects;

/** Current authorization facts revalidated by both the API and the claiming Worker. */
public record GitHubRepositoryImportAuthorization(
        GitHubAccessRequest access,
        GitHubRepositoryCatalogEntry catalog,
        GitHubRepositoryPolicy policy) {

    public GitHubRepositoryImportAuthorization {
        access = Objects.requireNonNull(access, "access");
        catalog = Objects.requireNonNull(catalog, "catalog");
        policy = Objects.requireNonNull(policy, "policy");
    }
}
