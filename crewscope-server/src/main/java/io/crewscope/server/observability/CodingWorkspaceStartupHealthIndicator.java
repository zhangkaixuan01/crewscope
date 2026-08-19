package io.crewscope.server.observability;

import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceStartupHealth;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceStartupReconciler;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Identifier-free readiness details for bounded Workspace startup reconciliation. */
public final class CodingWorkspaceStartupHealthIndicator implements HealthIndicator {

    private final CodingWorkspaceStartupReconciler reconciler;

    public CodingWorkspaceStartupHealthIndicator(CodingWorkspaceStartupReconciler reconciler) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    }

    @Override
    public Health health() {
        CodingWorkspaceStartupHealth state = reconciler.health();
        Health.Builder builder = state.completed()
                        && !state.capacityLimited()
                        && state.archiveFailures() == 0
                ? Health.up()
                : Health.down();
        return builder
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
