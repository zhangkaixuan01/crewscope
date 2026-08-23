package io.crewscope.application.github;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Immutable GitHub rate-limit observation for one exact Connection version. */
public record GitHubRateLimitSnapshot(
        UUID id,
        OrganizationId organizationId,
        ConnectionId connectionId,
        long connectionVersion,
        String resource,
        long limit,
        long remaining,
        long used,
        UtcTimestamp resetsAt,
        UtcTimestamp observedAt,
        PrincipalId createdBy) {

    public GitHubRateLimitSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0 || limit < 0 || remaining < 0 || used < 0
                || remaining > limit || used > limit) {
            throw new IllegalArgumentException("GitHub rate-limit values are invalid");
        }
        resource = GitHubHash.requireText(resource);
        if (resource.length() > 64) {
            throw new IllegalArgumentException("GitHub rate-limit resource exceeds its maximum length");
        }
        Objects.requireNonNull(resetsAt, "resetsAt");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(createdBy, "createdBy");
    }
}
