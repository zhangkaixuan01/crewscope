package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import java.util.Objects;

/** Complete optimistic coordinates for the fixed-order atomic Generation switch. */
public record ProjectionSwitchRequest(
        ProjectionGenerationKey targetGeneration,
        ProjectionGeneration previousActiveGeneration,
        ProjectionRebuildJobId rebuildJobId,
        ProjectionDefinitionVersion expectedDefinitionVersion,
        long expectedPointerVersion,
        long expectedPreviousGenerationVersion,
        long expectedTargetGenerationVersion,
        long expectedJobVersion) {

    public ProjectionSwitchRequest {
        targetGeneration = Objects.requireNonNull(targetGeneration, "targetGeneration");
        previousActiveGeneration = Objects.requireNonNull(
                previousActiveGeneration, "previousActiveGeneration");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        expectedDefinitionVersion = Objects.requireNonNull(
                expectedDefinitionVersion, "expectedDefinitionVersion");
        if (previousActiveGeneration.equals(targetGeneration.generation())) {
            throw new IllegalArgumentException("Switch target must differ from the active Generation");
        }
        if (expectedPointerVersion < 0
                || expectedPreviousGenerationVersion < 0
                || expectedTargetGenerationVersion < 0
                || expectedJobVersion < 0) {
            throw new IllegalArgumentException("Switch versions must not be negative");
        }
    }
}
