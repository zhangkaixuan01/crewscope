package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Complete tenant-scoped identity of one projection generation. */
public record ProjectionGenerationKey(
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionGeneration generation) {

    public ProjectionGenerationKey {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        generation = Objects.requireNonNull(generation, "generation");
    }
}
