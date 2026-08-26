package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Optimistic coordinates required to validate one shadow Generation. */
public record ProjectionValidationRequest(
        ProjectionGenerationKey generationKey,
        ProjectionRebuildJobId rebuildJobId,
        ProjectionDefinitionVersion expectedDefinitionVersion,
        long expectedGenerationVersion,
        long expectedJobVersion,
        PrincipalId actorId) {

    public ProjectionValidationRequest {
        generationKey = Objects.requireNonNull(generationKey, "generationKey");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        expectedDefinitionVersion = Objects.requireNonNull(
                expectedDefinitionVersion, "expectedDefinitionVersion");
        actorId = Objects.requireNonNull(actorId, "actorId");
        if (expectedGenerationVersion < 0 || expectedJobVersion < 0) {
            throw new IllegalArgumentException("Validation versions must not be negative");
        }
    }
}
