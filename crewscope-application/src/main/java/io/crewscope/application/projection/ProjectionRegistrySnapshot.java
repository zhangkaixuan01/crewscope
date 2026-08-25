package io.crewscope.application.projection;

import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationState;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionPointer;
import io.crewscope.domain.projection.ProjectionRebuildJob;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Objects;

/** Consistent Registry read used to derive an optimistic domain mutation. */
public record ProjectionRegistrySnapshot(
        ProjectionDefinition definition,
        ProjectionPointer pointer,
        List<ProjectionGenerationState> generations,
        List<ProjectionRebuildJob> jobs) {

    public ProjectionRegistrySnapshot {
        definition = Objects.requireNonNull(definition, "definition");
        pointer = Objects.requireNonNull(pointer, "pointer");
        generations = List.copyOf(Objects.requireNonNull(generations, "generations"));
        jobs = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
        if (!definition.name().equals(pointer.projectionName())
                || generations.isEmpty()) {
            throw new IllegalArgumentException("Projection Registry snapshot has mixed scope");
        }
        for (ProjectionGenerationState generation : generations) {
            if (!pointer.organizationId().equals(generation.key().organizationId())
                    || !pointer.projectionName().equals(generation.key().projectionName())) {
                throw new IllegalArgumentException("Projection Registry snapshot has mixed scope");
            }
        }
        for (ProjectionRebuildJob job : jobs) {
            if (!pointer.organizationId().equals(job.organizationId())
                    || !pointer.projectionName().equals(job.projectionName())) {
                throw new IllegalArgumentException("Projection Registry snapshot has mixed scope");
            }
        }
    }

    public OrganizationId organizationId() {
        return pointer.organizationId();
    }

    public ProjectionName projectionName() {
        return pointer.projectionName();
    }

    public ProjectionGenerationState requireGeneration(ProjectionGeneration generation) {
        return generations.stream()
                .filter(value -> value.key().generation().equals(generation))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Projection Generation was not found"));
    }

    public ProjectionRebuildJob requireJob(ProjectionRebuildJobId jobId) {
        return jobs.stream()
                .filter(value -> value.id().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Projection RebuildJob was not found"));
    }
}
