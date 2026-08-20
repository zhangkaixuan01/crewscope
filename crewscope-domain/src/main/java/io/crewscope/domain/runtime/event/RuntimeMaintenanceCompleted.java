package io.crewscope.domain.runtime.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Identifier-free audit payload for a completed Runtime maintenance command. */
public record RuntimeMaintenanceCompleted(
        String operation,
        String environment,
        String health,
        int recoveredWorkspaces,
        int failedWorkspaces,
        int archivedWorkspaces,
        int archiveFailures,
        int removedSandboxOrphans,
        int purgedArtifacts,
        boolean capacityLimited)
        implements DomainEvent {

    public RuntimeMaintenanceCompleted {
        operation = requireText(operation, "operation");
        environment = requireText(environment, "environment");
        health = requireText(health, "health");
        if (recoveredWorkspaces < 0
                || failedWorkspaces < 0
                || archivedWorkspaces < 0
                || archiveFailures < 0
                || removedSandboxOrphans < 0
                || purgedArtifacts < 0) {
            throw new IllegalArgumentException("maintenance counts must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty() || required.length() > 100) {
            throw new IllegalArgumentException(field + " must contain between 1 and 100 characters");
        }
        return required;
    }
}
