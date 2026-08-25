package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Persisted validation attempt bound to one Definition, Generation, Job and actor. */
public record ProjectionValidationResult(
        ProjectionDefinitionVersion definitionVersion,
        ProjectionGeneration generation,
        ProjectionRebuildJobId rebuildJobId,
        ProjectionSnapshot expected,
        ProjectionSnapshot actual,
        PrincipalId validatedBy,
        UtcTimestamp validatedAt) {

    public ProjectionValidationResult {
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion");
        generation = Objects.requireNonNull(generation, "generation");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        expected = Objects.requireNonNull(expected, "expected");
        actual = Objects.requireNonNull(actual, "actual");
        validatedBy = Objects.requireNonNull(validatedBy, "validatedBy");
        validatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
    }

    /** Count/hash equality and zero observed gaps or failures are required for switching. */
    public boolean passed() {
        return expected.rowCount() == actual.rowCount()
                && expected.canonicalHash().equals(actual.canonicalHash())
                && expected.healthy()
                && actual.healthy();
    }

    /** A switch must compare a fresh snapshot with the exact saved successful result. */
    public void requireCurrent(ProjectionSnapshot current) {
        ProjectionSnapshot required = Objects.requireNonNull(current, "currentSnapshot");
        if (!passed() || !actual.equals(required)) {
            throw new IllegalStateException(
                    "Projection validation is unsuccessful or stale; revalidation is required");
        }
    }
}
