package io.crewscope.application.github;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for non-secret GitHub profile, catalog and rate-limit facts. */
public interface GitHubProviderRepository {

    Optional<GitHubConnectionProfile> findProfile(
            OrganizationId organizationId, ConnectionId connectionId, long connectionVersion);

    GitHubConnectionProfile insertProfile(GitHubConnectionProfile profile);

    /** Atomically replaces the current catalog, marks missing entries stale and appends rate facts. */
    void synchronizeCatalog(
            GitHubConnectionProfile profile,
            List<GitHubRepositoryCatalogEntry> entries,
            GitHubRateLimitSnapshot rateLimit);

    /** Updates one remote Preflight fact without marking unrelated Catalog entries stale. */
    void recordPreflight(
            GitHubConnectionProfile profile,
            GitHubRepositoryCatalogEntry entry,
            GitHubRateLimitSnapshot rateLimit);

    Optional<GitHubRepositoryCatalogEntry> findRepository(
            OrganizationId organizationId,
            ConnectionId connectionId,
            String externalRepositoryId);

    List<GitHubRepositoryCatalogEntry> findDeliverableRepositories(
            OrganizationId organizationId, ConnectionId connectionId);

    Optional<GitHubRateLimitSnapshot> findCurrentRateLimit(
            OrganizationId organizationId, ConnectionId connectionId, String resource);
}
