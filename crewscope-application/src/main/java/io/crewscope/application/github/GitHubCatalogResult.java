package io.crewscope.application.github;

import java.util.List;
import java.util.Objects;

/** Safe catalog result containing deliverable resources plus rate-limit facts. */
public record GitHubCatalogResult(
        GitHubConnectionProfile profile,
        List<GitHubRepositoryCatalogEntry> deliverableRepositories,
        int blockedRepositoryCount,
        GitHubRateLimitSnapshot rateLimit) {

    public GitHubCatalogResult {
        Objects.requireNonNull(profile, "profile");
        deliverableRepositories = List.copyOf(
                Objects.requireNonNull(deliverableRepositories, "deliverableRepositories"));
        if (blockedRepositoryCount < 0) {
            throw new IllegalArgumentException("blockedRepositoryCount must not be negative");
        }
        Objects.requireNonNull(rateLimit, "rateLimit");
    }
}
