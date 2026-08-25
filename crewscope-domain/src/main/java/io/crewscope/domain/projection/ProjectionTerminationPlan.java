package io.crewscope.domain.projection;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Atomic terminal update for a shadow Generation and its RebuildJob. */
public record ProjectionTerminationPlan(
        ProjectionGenerationState generation, ProjectionRebuildJob job) {

    public ProjectionTerminationPlan {
        generation = Objects.requireNonNull(generation, "generation");
        job = Objects.requireNonNull(job, "job");
    }

    public static ProjectionTerminationPlan cancel(
            ProjectionGenerationState generation,
            ProjectionRebuildJob job,
            long expectedGenerationVersion,
            long expectedJobVersion,
            UtcTimestamp occurredAt) {
        requireBinding(generation, job);
        return new ProjectionTerminationPlan(
                generation.cancel(expectedGenerationVersion, occurredAt),
                job.cancel(expectedJobVersion, occurredAt));
    }

    public static ProjectionTerminationPlan fail(
            ProjectionGenerationState generation,
            ProjectionRebuildJob job,
            long expectedGenerationVersion,
            long expectedJobVersion,
            UtcTimestamp occurredAt) {
        requireBinding(generation, job);
        return new ProjectionTerminationPlan(
                generation.fail(expectedGenerationVersion, occurredAt),
                job.fail(expectedJobVersion, occurredAt));
    }

    private static void requireBinding(
            ProjectionGenerationState generation, ProjectionRebuildJob job) {
        if (!generation.key().organizationId().equals(job.organizationId())
                || !generation.key().projectionName().equals(job.projectionName())
                || !generation.key().generation().equals(job.generation())
                || generation.rebuildJobId().filter(job.id()::equals).isEmpty()) {
            throw new IllegalArgumentException("Generation and RebuildJob must match exactly");
        }
    }
}
