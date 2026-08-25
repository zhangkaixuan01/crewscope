package io.crewscope.domain.projection;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Safe immutable lifecycle fact used to build the PROJECTION Audit view. */
public record ProjectionLifecycleEvent(
        UUID commandId,
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionDefinitionVersion definitionVersion,
        ProjectionGeneration generation,
        ProjectionRebuildJobId rebuildJobId,
        ProjectionLifecycleEventType eventType,
        ProjectionGenerationStatus generationStatus,
        ProjectionRebuildStatus rebuildStatus,
        Optional<ProjectionGeneration> previousActiveGeneration,
        Optional<ProjectionFailureCode> failureCode,
        PrincipalId actorId,
        UtcTimestamp occurredAt) implements DomainEvent {

    public ProjectionLifecycleEvent {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion");
        generation = Objects.requireNonNull(generation, "generation");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        eventType = Objects.requireNonNull(eventType, "eventType");
        generationStatus = Objects.requireNonNull(generationStatus, "generationStatus");
        rebuildStatus = Objects.requireNonNull(rebuildStatus, "rebuildStatus");
        previousActiveGeneration = Objects.requireNonNull(
                previousActiveGeneration, "previousActiveGeneration");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        actorId = Objects.requireNonNull(actorId, "actorId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if ((eventType == ProjectionLifecycleEventType.REBUILD_FAILED) != failureCode.isPresent()) {
            throw new IllegalArgumentException("Only REBUILD_FAILED exposes a bounded failure code");
        }
        if ((eventType == ProjectionLifecycleEventType.GENERATION_SWITCHED)
                != previousActiveGeneration.isPresent()) {
            throw new IllegalArgumentException(
                    "Only GENERATION_SWITCHED exposes the previous Generation number");
        }
        requireOutcome(eventType, generationStatus, rebuildStatus);
    }

    private static void requireOutcome(
            ProjectionLifecycleEventType eventType,
            ProjectionGenerationStatus generationStatus,
            ProjectionRebuildStatus rebuildStatus) {
        boolean valid = switch (eventType) {
            case REBUILD_STARTED, REBUILD_RETRIED ->
                    generationStatus == ProjectionGenerationStatus.BUILDING
                            && rebuildStatus == ProjectionRebuildStatus.BUILDING;
            case VALIDATION_PASSED ->
                    generationStatus == ProjectionGenerationStatus.VALIDATING
                            && rebuildStatus == ProjectionRebuildStatus.VALIDATING;
            case VALIDATION_FAILED ->
                    generationStatus.shadow()
                            && (rebuildStatus == ProjectionRebuildStatus.BUILDING
                                    || rebuildStatus == ProjectionRebuildStatus.VALIDATING);
            case GENERATION_SWITCHED ->
                    generationStatus == ProjectionGenerationStatus.ACTIVE
                            && rebuildStatus == ProjectionRebuildStatus.COMPLETED;
            case REBUILD_CANCELLED ->
                    generationStatus == ProjectionGenerationStatus.CANCELLED
                            && rebuildStatus == ProjectionRebuildStatus.CANCELLED;
            case REBUILD_FAILED ->
                    generationStatus == ProjectionGenerationStatus.FAILED
                            && rebuildStatus == ProjectionRebuildStatus.FAILED;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Projection lifecycle event does not match its lifecycle outcome");
        }
    }
}
