package io.crewscope.application.projection;

import io.crewscope.domain.projection.ProjectionSnapshot;
import java.util.Objects;

/** Canonical expected and actual snapshots captured while the target Generation is write-locked. */
public record ProjectionVerificationSnapshots(
        ProjectionSnapshot expected, ProjectionSnapshot actual) {

    public ProjectionVerificationSnapshots {
        expected = Objects.requireNonNull(expected, "expected");
        actual = Objects.requireNonNull(actual, "actual");
    }
}
