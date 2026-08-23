package io.crewscope.application.github;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.UUID;

/** Trusted exact Connection and Grant coordinates for one GitHub operation. */
public record GitHubAccessRequest(
        OrganizationId organizationId,
        ConnectionId connectionId,
        long expectedConnectionVersion,
        ConnectionGrantId connectionGrantId,
        long expectedGrantVersion,
        ProviderOwner grantee,
        ProviderAccessScope requestedAccess,
        PrincipalId actor,
        UUID correlationId) {

    public GitHubAccessRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(connectionGrantId, "connectionGrantId");
        if (expectedConnectionVersion < 0 || expectedGrantVersion < 0) {
            throw new IllegalArgumentException("GitHub access versions must not be negative");
        }
        grantee = Objects.requireNonNull(grantee, "grantee");
        if (!organizationId.equals(grantee.organizationId())) {
            throw new IllegalArgumentException("GitHub grantee must belong to the Organization");
        }
        Objects.requireNonNull(requestedAccess, "requestedAccess");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
