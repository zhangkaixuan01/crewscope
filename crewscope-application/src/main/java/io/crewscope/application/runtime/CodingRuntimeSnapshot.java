package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Local Worker Coding resource facts without host, container, Lease or token coordinates. */
public record CodingRuntimeSnapshot(
        OrganizationId organizationId,
        RuntimeEnvironment environment,
        UtcTimestamp observedAt,
        CodingRuntimeComponentHealth health,
        RuntimeCapacitySummary workspaceCapacity,
        CodingRuntimeComponentSummary sandboxes,
        CodingRuntimeComponentSummary watchers,
        CodingCleanupSummary cleanup) {

    public CodingRuntimeSnapshot {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        environment = Objects.requireNonNull(environment, "environment");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        health = Objects.requireNonNull(health, "health");
        workspaceCapacity = Objects.requireNonNull(workspaceCapacity, "workspaceCapacity");
        sandboxes = Objects.requireNonNull(sandboxes, "sandboxes");
        watchers = Objects.requireNonNull(watchers, "watchers");
        cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }
}
