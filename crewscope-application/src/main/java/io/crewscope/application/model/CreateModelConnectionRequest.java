package io.crewscope.application.model;

import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Public-safe connection draft; endpoint, credential identity and billing scope are server-owned. */
public record CreateModelConnectionRequest(
        ModelProviderKey providerKey,
        ModelConnectionOwnerType ownerType,
        Optional<TeamId> teamId,
        ModelRegion region,
        Optional<UtcTimestamp> credentialExpiresAt) {

    public CreateModelConnectionRequest {
        Objects.requireNonNull(providerKey, "providerKey");
        Objects.requireNonNull(ownerType, "ownerType");
        teamId = Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(region, "region");
        credentialExpiresAt = Objects.requireNonNull(credentialExpiresAt, "credentialExpiresAt");
        if ((ownerType == ModelConnectionOwnerType.TEAM) != teamId.isPresent()) {
            throw new IllegalArgumentException("teamId must be present exactly for TEAM ownership");
        }
    }
}
