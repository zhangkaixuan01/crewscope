package io.crewscope.application.runtime;

import java.util.Objects;

/** Safe result returned after a bounded operational maintenance command. */
public record CodingRuntimeMaintenanceOutcome(
        CodingRuntimeMaintenanceOperation operation, CodingRuntimeSnapshot snapshot) {

    public CodingRuntimeMaintenanceOutcome {
        operation = Objects.requireNonNull(operation, "operation");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
}
