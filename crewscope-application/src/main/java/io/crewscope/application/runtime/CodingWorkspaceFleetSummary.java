package io.crewscope.application.runtime;

import java.util.Objects;

/** Member-safe Coding Workspace capacity and component health projection. */
public record CodingWorkspaceFleetSummary(
        CodingRuntimeComponentHealth health,
        RuntimeCapacitySummary capacity,
        CodingRuntimeComponentSummary sandboxes,
        CodingRuntimeComponentSummary watchers,
        CodingRuntimeComponentHealth cleanupHealth,
        boolean cleanupCapacityLimited) {

    public CodingWorkspaceFleetSummary {
        health = Objects.requireNonNull(health, "health");
        capacity = Objects.requireNonNull(capacity, "capacity");
        sandboxes = Objects.requireNonNull(sandboxes, "sandboxes");
        watchers = Objects.requireNonNull(watchers, "watchers");
        cleanupHealth = Objects.requireNonNull(cleanupHealth, "cleanupHealth");
    }

    public static CodingWorkspaceFleetSummary from(CodingRuntimeSnapshot snapshot) {
        CodingRuntimeSnapshot source = Objects.requireNonNull(snapshot, "snapshot");
        return new CodingWorkspaceFleetSummary(
                source.health(),
                source.workspaceCapacity(),
                source.sandboxes(),
                source.watchers(),
                source.cleanup().health(),
                source.cleanup().capacityLimited());
    }
}
