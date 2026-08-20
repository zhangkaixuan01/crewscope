package io.crewscope.server.observability;

import io.crewscope.application.runtime.CodingRuntimeSnapshot;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceRuntimeOperationsAdapter;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Identifier-free readiness details for bounded Workspace startup reconciliation. */
public final class CodingWorkspaceStartupHealthIndicator implements HealthIndicator {

    private final CodingWorkspaceRuntimeOperationsAdapter operations;

    public CodingWorkspaceStartupHealthIndicator(CodingWorkspaceRuntimeOperationsAdapter operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public Health health() {
        CodingRuntimeSnapshot snapshot = operations.localSnapshot();
        var state = snapshot.cleanup();
        Health.Builder builder = snapshot.health()
                        == io.crewscope.application.runtime.CodingRuntimeComponentHealth.HEALTHY
                ? Health.up()
                : Health.down();
        return builder
                .withDetail("health", snapshot.health().name())
                .withDetail("workspaceCapacityMaximum", snapshot.workspaceCapacity().maximum())
                .withDetail("workspaceCapacityActive", snapshot.workspaceCapacity().active())
                .withDetail("workspaceCapacityAvailable", snapshot.workspaceCapacity().available())
                .withDetail("sandboxHealth", snapshot.sandboxes().health().name())
                .withDetail("activeSandboxes", snapshot.sandboxes().total())
                .withDetail("failedSandboxes", snapshot.sandboxes().failed())
                .withDetail("watcherHealth", snapshot.watchers().health().name())
                .withDetail("activeWatchers", snapshot.watchers().total())
                .withDetail("failedWatchers", snapshot.watchers().failed())
                .withDetail("completed", state.completed())
                .withDetail("recoveredWorkspaces", state.recoveredWorkspaces())
                .withDetail("failedWorkspaces", state.failedWorkspaces())
                .withDetail("archivedWorkspaces", state.archivedWorkspaces())
                .withDetail("archiveFailures", state.archiveFailures())
                .withDetail("removedSandboxOrphans", state.removedSandboxOrphans())
                .withDetail("purgedArtifacts", state.purgedArtifacts())
                .withDetail("capacityLimited", state.capacityLimited())
                .withDetail("lastFailureType", state.lastFailureType().orElse("NONE"))
                .build();
    }
}
