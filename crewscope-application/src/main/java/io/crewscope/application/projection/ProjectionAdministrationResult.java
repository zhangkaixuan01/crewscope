package io.crewscope.application.projection;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationStatus;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.projection.ProjectionRebuildStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.OptionalLong;

/** Safe stable command result returned by first execution and exact idempotent replay. */
public record ProjectionAdministrationResult(
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionGeneration generation,
        ProjectionRebuildJobId rebuildJobId,
        ProjectionGenerationStatus generationStatus,
        ProjectionRebuildStatus rebuildStatus,
        OptionalLong pointerVersion) {

    public ProjectionAdministrationResult {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        generation = Objects.requireNonNull(generation, "generation");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        generationStatus = Objects.requireNonNull(generationStatus, "generationStatus");
        rebuildStatus = Objects.requireNonNull(rebuildStatus, "rebuildStatus");
        pointerVersion = Objects.requireNonNull(pointerVersion, "pointerVersion");
        if (pointerVersion.isPresent() && pointerVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("pointerVersion must not be negative");
        }
    }
}
