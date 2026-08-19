package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;
import java.util.Optional;

/** Identifier-free startup reconciliation capacity and outcome projection. */
public record CodingWorkspaceStartupHealth(
        boolean completed,
        int recoveredWorkspaces,
        int failedWorkspaces,
        int archivedWorkspaces,
        int archiveFailures,
        int removedSandboxOrphans,
        int purgedArtifacts,
        boolean capacityLimited,
        Optional<String> lastFailureType) {

    public CodingWorkspaceStartupHealth {
        if (recoveredWorkspaces < 0
                || failedWorkspaces < 0
                || archivedWorkspaces < 0
                || archiveFailures < 0
                || removedSandboxOrphans < 0
                || purgedArtifacts < 0) {
            throw new IllegalArgumentException("Startup reconciliation counts must not be negative");
        }
        lastFailureType = Objects.requireNonNull(lastFailureType, "lastFailureType");
    }

    public static CodingWorkspaceStartupHealth pending() {
        return new CodingWorkspaceStartupHealth(
                false, 0, 0, 0, 0, 0, 0, false, Optional.empty());
    }
}
