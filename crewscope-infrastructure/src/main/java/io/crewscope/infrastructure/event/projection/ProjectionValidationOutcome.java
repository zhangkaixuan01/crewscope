package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionSnapshot;
import java.util.Objects;
import java.util.UUID;

/** Persisted validation result and the new fenced lease issued by its lifecycle transition. */
public record ProjectionValidationOutcome(
        UUID validationId,
        boolean passed,
        ProjectionSnapshot expected,
        ProjectionSnapshot actual,
        ProjectionGenerationLease lease,
        long generationVersion,
        long jobVersion) {

    public ProjectionValidationOutcome {
        validationId = Objects.requireNonNull(validationId, "validationId");
        expected = Objects.requireNonNull(expected, "expected");
        actual = Objects.requireNonNull(actual, "actual");
        lease = Objects.requireNonNull(lease, "lease");
        if (generationVersion < 0 || jobVersion < 0) {
            throw new IllegalArgumentException("Validation result versions must not be negative");
        }
    }
}
