package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persisted GitHub repository discovery fact with explicit delivery eligibility. */
public record GitHubRepositoryCatalogEntry(
        UUID id,
        OrganizationId organizationId,
        ConnectionId connectionId,
        long connectionVersion,
        ProviderExecutionIdentity externalIdentity,
        String externalRepositoryId,
        String ownerLogin,
        String repositoryName,
        String fullName,
        RepositoryBranchName defaultBranch,
        GitHubRepositoryVisibility visibility,
        boolean archived,
        boolean fork,
        boolean canPull,
        boolean canPush,
        boolean canCreatePullRequest,
        String permissionsHash,
        Optional<String> etagHash,
        UtcTimestamp discoveredAt,
        UtcTimestamp cacheExpiresAt,
        GitHubRepositoryStatus status,
        long version,
        AuditMetadata audit) {

    public GitHubRepositoryCatalogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0 || version < 0) {
            throw new IllegalArgumentException("GitHub repository versions must not be negative");
        }
        Objects.requireNonNull(externalIdentity, "externalIdentity");
        externalRepositoryId = bounded(externalRepositoryId, 100, "externalRepositoryId");
        ownerLogin = bounded(ownerLogin, 255, "ownerLogin");
        repositoryName = bounded(repositoryName, 255, "repositoryName");
        fullName = bounded(fullName, 511, "fullName");
        if (!fullName.equals(ownerLogin + "/" + repositoryName)) {
            throw new IllegalArgumentException("GitHub repository fullName is inconsistent");
        }
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        Objects.requireNonNull(visibility, "visibility");
        permissionsHash = GitHubHash.requireHash(permissionsHash, "permissionsHash");
        etagHash = Objects.requireNonNull(etagHash, "etagHash")
                .map(value -> GitHubHash.requireHash(value, "etagHash"));
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        Objects.requireNonNull(cacheExpiresAt, "cacheExpiresAt");
        if (cacheExpiresAt.compareTo(discoveredAt) < 0) {
            throw new IllegalArgumentException("GitHub repository cache expiry precedes discovery");
        }
        Objects.requireNonNull(status, "status");
        if (status == GitHubRepositoryStatus.DELIVERABLE
                && (archived || fork || !canPull || !canPush || !canCreatePullRequest)) {
            throw new IllegalArgumentException("Deliverable GitHub repository lacks current authority");
        }
        Objects.requireNonNull(audit, "audit");
    }

    public String grantResourceKey() {
        return "github:repository:" + fullName.toLowerCase(Locale.ROOT);
    }

    public boolean isCurrentAt(UtcTimestamp now) {
        return status != GitHubRepositoryStatus.STALE
                && cacheExpiresAt.compareTo(Objects.requireNonNull(now, "now")) > 0;
    }

    private static String bounded(String value, int maximum, String field) {
        String normalized = GitHubHash.requireText(value);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds its maximum length");
        }
        return normalized;
    }
}
