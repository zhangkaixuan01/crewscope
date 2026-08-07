package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Policy-approved actor context for lifecycle mutations such as Tombstone creation. */
public record ArtifactMutationContext(
        OrganizationId organizationId,
        PrincipalId principalId) {

    public ArtifactMutationContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principalId, "principalId");
    }
}
