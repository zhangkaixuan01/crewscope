package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionGenerationLease;
import java.util.Objects;

/** Committed Pointer version and new ACTIVE lease returned by an atomic switch. */
public record ProjectionSwitchOutcome(
        ProjectionGenerationLease activeLease,
        long pointerVersion,
        long previousGenerationVersion,
        long targetGenerationVersion,
        long jobVersion) {

    public ProjectionSwitchOutcome {
        activeLease = Objects.requireNonNull(activeLease, "activeLease");
        if (pointerVersion < 0
                || previousGenerationVersion < 0
                || targetGenerationVersion < 0
                || jobVersion < 0) {
            throw new IllegalArgumentException("Switch result versions must not be negative");
        }
    }
}
