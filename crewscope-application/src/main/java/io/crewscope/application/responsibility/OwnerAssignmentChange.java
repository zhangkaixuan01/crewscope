package io.crewscope.application.responsibility;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import java.util.Objects;
import java.util.Optional;

/** Atomic outcome of replacing the active Owner responsibility slot. */
public record OwnerAssignmentChange(
        Optional<ResponsibilityAssignment> released,
        ResponsibilityAssignment active) {

    public OwnerAssignmentChange {
        released = Objects.requireNonNull(released, "released");
        active = Objects.requireNonNull(active, "active");
    }
}
