package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Atomic updates produced by one canonical validation attempt. */
public record ProjectionValidationPlan(
        ProjectionGenerationState generation,
        ProjectionRebuildJob job,
        ProjectionValidationResult result) {

    public ProjectionValidationPlan {
        generation = Objects.requireNonNull(generation, "generation");
        job = Objects.requireNonNull(job, "job");
        result = Objects.requireNonNull(result, "result");
    }

    public static ProjectionValidationPlan validate(
            ProjectionDefinition definition,
            ProjectionGenerationState generation,
            ProjectionRebuildJob job,
            long expectedGenerationVersion,
            long expectedJobVersion,
            ProjectionSnapshot expected,
            ProjectionSnapshot actual,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        ProjectionDefinition requiredDefinition = Objects.requireNonNull(definition, "definition");
        ProjectionGenerationState currentGeneration = Objects.requireNonNull(
                generation, "generation");
        ProjectionRebuildJob currentJob = Objects.requireNonNull(job, "job");
        requireBinding(requiredDefinition, currentGeneration, currentJob);
        ProjectionValidationResult result = new ProjectionValidationResult(
                requiredDefinition.version(), currentGeneration.key().generation(), currentJob.id(),
                expected, actual, actor, occurredAt);
        ProjectionGenerationState nextGeneration = currentGeneration.recordValidation(
                expectedGenerationVersion, result, occurredAt);
        ProjectionRebuildJob nextJob = currentJob.recordValidation(
                expectedJobVersion, result, occurredAt);
        return new ProjectionValidationPlan(nextGeneration, nextJob, result);
    }

    private static void requireBinding(
            ProjectionDefinition definition,
            ProjectionGenerationState generation,
            ProjectionRebuildJob job) {
        if (!definition.name().equals(generation.key().projectionName())
                || !definition.name().equals(job.projectionName())
                || !definition.version().equals(generation.definitionVersion())
                || !definition.version().equals(job.definitionVersion())
                || !generation.key().organizationId().equals(job.organizationId())
                || !generation.key().generation().equals(job.generation())
                || generation.rebuildJobId().filter(job.id()::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "Projection validation scope, Definition, Generation and Job must match");
        }
    }
}
