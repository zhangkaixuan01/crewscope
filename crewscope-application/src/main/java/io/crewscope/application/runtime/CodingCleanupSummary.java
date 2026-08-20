package io.crewscope.application.runtime;

import java.util.Objects;
import java.util.Optional;

/** Safe outcome of the latest bounded Workspace recovery and retention pass. */
public record CodingCleanupSummary(
        CodingRuntimeComponentHealth health,
        boolean completed,
        int recoveredWorkspaces,
        int failedWorkspaces,
        int archivedWorkspaces,
        int archiveFailures,
        int removedSandboxOrphans,
        int purgedArtifacts,
        boolean capacityLimited,
        Optional<String> lastFailureType) {

    public CodingCleanupSummary {
        health = Objects.requireNonNull(health, "health");
        lastFailureType = Objects.requireNonNull(lastFailureType, "lastFailureType");
        if (recoveredWorkspaces < 0
                || failedWorkspaces < 0
                || archivedWorkspaces < 0
                || archiveFailures < 0
                || removedSandboxOrphans < 0
                || purgedArtifacts < 0) {
            throw new IllegalArgumentException("cleanup counts must not be negative");
        }
    }
}
