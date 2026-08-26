package io.crewscope.application.operations;

import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationStatus;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Administrator-only Projection coordinates and bounded failure diagnostics. */
public record ProjectionHealthDiagnostic(
        ProjectionName projectionName,
        ProjectionDefinitionVersion definitionVersion,
        ProjectionGeneration activeGeneration,
        long pointerVersion,
        long activeGenerationVersion,
        Optional<ProjectionGeneration> shadowGeneration,
        Optional<ProjectionGenerationStatus> shadowStatus,
        OptionalLong shadowGenerationVersion,
        Optional<ProjectionRebuildJobId> rebuildJobId,
        OptionalLong rebuildJobVersion,
        long lagSeconds,
        long gapCount,
        long deadLetterCount,
        Optional<ProjectionFailureCode> latestFailureCode) {

    public ProjectionHealthDiagnostic {
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion");
        activeGeneration = Objects.requireNonNull(activeGeneration, "activeGeneration");
        shadowGeneration = Objects.requireNonNull(shadowGeneration, "shadowGeneration");
        shadowStatus = Objects.requireNonNull(shadowStatus, "shadowStatus");
        shadowGenerationVersion = Objects.requireNonNull(
                shadowGenerationVersion, "shadowGenerationVersion");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        rebuildJobVersion = Objects.requireNonNull(rebuildJobVersion, "rebuildJobVersion");
        latestFailureCode = Objects.requireNonNull(latestFailureCode, "latestFailureCode");
        if (pointerVersion < 0
                || activeGenerationVersion < 0
                || lagSeconds < 0
                || gapCount < 0
                || deadLetterCount < 0
                || shadowGenerationVersion.stream().anyMatch(value -> value < 0)
                || rebuildJobVersion.stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("projection diagnostic values must not be negative");
        }
        boolean hasShadow = shadowGeneration.isPresent();
        if (hasShadow != shadowStatus.isPresent()
                || hasShadow != shadowGenerationVersion.isPresent()
                || hasShadow != rebuildJobId.isPresent()
                || hasShadow != rebuildJobVersion.isPresent()) {
            throw new IllegalArgumentException(
                    "shadow Generation diagnostics must contain a complete coordinate set");
        }
        shadowStatus.ifPresent(status -> {
            if (status != ProjectionGenerationStatus.BUILDING
                    && status != ProjectionGenerationStatus.VALIDATING) {
                throw new IllegalArgumentException(
                        "only a live shadow Generation belongs in current diagnostics");
            }
        });
        if (shadowGeneration.isPresent()
                && shadowGeneration.orElseThrow().value() <= activeGeneration.value()) {
            throw new IllegalArgumentException(
                    "shadow Generation must be newer than the active Generation");
        }
    }
}
