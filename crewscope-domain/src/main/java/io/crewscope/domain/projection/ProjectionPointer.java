package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Strong-versioned source of truth for the currently readable projection Generation. */
public record ProjectionPointer(
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionGeneration activeGeneration,
        UtcTimestamp updatedAt,
        long version) {

    public ProjectionPointer {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        activeGeneration = Objects.requireNonNull(activeGeneration, "activeGeneration");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("Projection Pointer version must not be negative");
        }
    }

    public static ProjectionPointer initialize(
            ProjectionGenerationState active, UtcTimestamp occurredAt) {
        ProjectionGenerationState required = Objects.requireNonNull(active, "active");
        if (required.status() != ProjectionGenerationStatus.ACTIVE) {
            throw new IllegalArgumentException("Projection Pointer requires an ACTIVE Generation");
        }
        return new ProjectionPointer(
                required.key().organizationId(), required.key().projectionName(),
                required.key().generation(), occurredAt, 0);
    }

    public ProjectionPointer switchTo(
            long expectedVersion,
            ProjectionGenerationState target,
            UtcTimestamp occurredAt) {
        if (version != expectedVersion) {
            throw new IllegalStateException(
                    "Projection Pointer version conflict: expected "
                            + expectedVersion + ", actual " + version);
        }
        ProjectionGenerationState required = Objects.requireNonNull(target, "target");
        if (!organizationId.equals(required.key().organizationId())
                || !projectionName.equals(required.key().projectionName())
                || required.status() != ProjectionGenerationStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Projection Pointer target must be the exact ACTIVE tenant projection");
        }
        return new ProjectionPointer(
                organizationId, projectionName, required.key().generation(), occurredAt, version + 1);
    }
}
