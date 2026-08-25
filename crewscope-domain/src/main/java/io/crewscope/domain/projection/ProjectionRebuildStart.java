package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomic creation plan for one new shadow Generation and its immutable rebuild attempt. */
public record ProjectionRebuildStart(
        ProjectionGenerationState generation,
        ProjectionRebuildJob job,
        long expectedPointerVersion) {

    public ProjectionRebuildStart {
        generation = Objects.requireNonNull(generation, "generation");
        job = Objects.requireNonNull(job, "job");
        if (expectedPointerVersion < 0) {
            throw new IllegalArgumentException("expectedPointerVersion must not be negative");
        }
        if (!generation.key().organizationId().equals(job.organizationId())
                || !generation.key().projectionName().equals(job.projectionName())
                || !generation.key().generation().equals(job.generation())
                || generation.rebuildJobId().filter(job.id()::equals).isEmpty()) {
            throw new IllegalArgumentException("Rebuild start Generation and Job must match exactly");
        }
    }

    public static ProjectionRebuildStart start(
            OrganizationId organizationId,
            ProjectionDefinition definition,
            ProjectionPointer pointer,
            List<ProjectionGenerationState> existingGenerations,
            ProjectionRebuildJobId jobId,
            Optional<ProjectionRebuildJob> retryOf,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        ProjectionDefinition requiredDefinition = Objects.requireNonNull(definition, "definition");
        ProjectionPointer requiredPointer = Objects.requireNonNull(pointer, "pointer");
        List<ProjectionGenerationState> existing = List.copyOf(
                Objects.requireNonNull(existingGenerations, "existingGenerations"));
        requireScope(organizationId, requiredDefinition.name(), requiredPointer, existing);
        List<ProjectionGenerationState> active = existing.stream()
                .filter(value -> value.status() == ProjectionGenerationStatus.ACTIVE)
                .toList();
        if (active.size() != 1
                || !active.get(0).key().generation().equals(requiredPointer.activeGeneration())) {
            throw new IllegalStateException(
                    "Projection Registry must contain exactly the ACTIVE Generation named by Pointer");
        }
        if (existing.stream().anyMatch(value -> value.status().shadow())) {
            throw new IllegalStateException("Projection already has a BUILDING or VALIDATING Generation");
        }
        if (existing.stream().map(value -> value.key().generation()).distinct().count()
                != existing.size()) {
            throw new IllegalStateException("Projection Registry contains duplicate Generations");
        }
        ProjectionGeneration next = existing.stream()
                .map(value -> value.key().generation())
                .max(Comparator.naturalOrder())
                .orElseThrow()
                .next();
        Optional<ProjectionRebuildJobId> retryId = requireRetry(
                organizationId, requiredDefinition.name(), retryOf);
        ProjectionRebuildJob job = ProjectionRebuildJob.start(
                Objects.requireNonNull(jobId, "jobId"), organizationId, requiredDefinition, next,
                retryId, Objects.requireNonNull(actor, "actor"), occurredAt);
        ProjectionGenerationState generation = ProjectionGenerationState.building(
                organizationId, requiredDefinition, next, job.id(), occurredAt);
        return new ProjectionRebuildStart(generation, job, requiredPointer.version());
    }

    private static Optional<ProjectionRebuildJobId> requireRetry(
            OrganizationId organizationId,
            ProjectionName projectionName,
            Optional<ProjectionRebuildJob> retryOf) {
        Optional<ProjectionRebuildJob> required = Objects.requireNonNull(retryOf, "retryOf");
        if (required.isEmpty()) {
            return Optional.empty();
        }
        ProjectionRebuildJob previous = required.orElseThrow();
        if (!organizationId.equals(previous.organizationId())
                || !projectionName.equals(previous.projectionName())
                || (previous.status() != ProjectionRebuildStatus.FAILED
                        && previous.status() != ProjectionRebuildStatus.CANCELLED)) {
            throw new IllegalArgumentException(
                    "Only a FAILED or CANCELLED Job in the exact projection scope can be retried");
        }
        return Optional.of(previous.id());
    }

    private static void requireScope(
            OrganizationId organizationId,
            ProjectionName projectionName,
            ProjectionPointer pointer,
            List<ProjectionGenerationState> existing) {
        if (!Objects.requireNonNull(organizationId, "organizationId")
                        .equals(pointer.organizationId())
                || !projectionName.equals(pointer.projectionName())
                || existing.isEmpty()
                || existing.stream().anyMatch(value ->
                        !organizationId.equals(value.key().organizationId())
                                || !projectionName.equals(value.key().projectionName()))) {
            throw new IllegalArgumentException("Projection Registry snapshot has mixed scope");
        }
    }
}
