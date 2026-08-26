package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** One database-owned shadow replay claim with independent Worker and Generation fences. */
public record ProjectionSupervisorClaim(
        ProjectionGenerationKey generationKey,
        ProjectionFencingToken generationFencingToken,
        String ownerId,
        long workerFencingToken,
        Optional<ProjectionHistoryCursor> cursor) {

    public ProjectionSupervisorClaim {
        generationKey = Objects.requireNonNull(generationKey, "generationKey");
        generationFencingToken = Objects.requireNonNull(
                generationFencingToken, "generationFencingToken");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 160) {
            throw new IllegalArgumentException("ownerId must contain between 1 and 160 characters");
        }
        ownerId = ownerId.strip();
        if (workerFencingToken < 1) {
            throw new IllegalArgumentException("workerFencingToken must be positive");
        }
        cursor = Objects.requireNonNull(cursor, "cursor");
    }

    public ProjectionGenerationLease generationLease() {
        return new ProjectionGenerationLease(generationKey, generationFencingToken);
    }
}
