package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Removes only label-verified Sandbox containers whose durable owner is recoverable or gone. */
final class CodingSandboxOrphanCleaner {

    private final DockerSandboxControl docker;
    private final ExecutionWorkspaceRepository workspaces;

    CodingSandboxOrphanCleaner(
            DockerSandboxControl docker, ExecutionWorkspaceRepository workspaces) {
        this.docker = Objects.requireNonNull(docker, "docker");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    /** Force-removal is also the process-tree termination boundary for interrupted commands. */
    boolean closeKnown(ExecutionWorkspace workspace) {
        ExecutionWorkspace required = Objects.requireNonNull(workspace, "workspace");
        String name = TaskExecutionSandboxDescriptor.containerName(required);
        Optional<DockerContainerSnapshot> existing = docker.inspect(name);
        if (existing.isEmpty()) {
            return false;
        }
        DockerContainerSnapshot container = existing.orElseThrow();
        if (!TaskExecutionSandboxDescriptor.ownsIdentity(required, container)) {
            throw TaskExecutionSandboxFacts.failure(
                    TaskExecutionSandboxError.CONTAINER_CONFLICT,
                    "Interrupted Sandbox identity does not close against durable Workspace facts");
        }
        docker.remove(name);
        return true;
    }

    int closeUnknown(OrganizationId organizationId, RuntimeEnvironment environment) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        RuntimeEnvironment runtimeEnvironment = Objects.requireNonNull(environment, "environment");
        int removed = 0;
        for (DockerContainerSnapshot container :
                docker.listManaged(organization.toString(), runtimeEnvironment.value())) {
            Optional<ExecutionWorkspaceKey> key = workspaceKey(container.labels());
            Optional<ExecutionWorkspace> workspace = key.flatMap(value ->
                    workspaces.findByWorkspaceKey(organization, runtimeEnvironment, value));
            if (workspace.isPresent() && mustPreserve(workspace.orElseThrow().status())) {
                continue;
            }
            docker.remove(container.name());
            removed++;
        }
        return removed;
    }

    private static boolean mustPreserve(ExecutionWorkspaceStatus status) {
        return status == ExecutionWorkspaceStatus.PENDING
                || status == ExecutionWorkspaceStatus.PROVISIONING
                || status == ExecutionWorkspaceStatus.READY
                || status == ExecutionWorkspaceStatus.ACTIVE
                || status == ExecutionWorkspaceStatus.FINALIZING;
    }

    private static Optional<ExecutionWorkspaceKey> workspaceKey(Map<String, String> labels) {
        try {
            String value = labels.get(TaskExecutionSandboxDescriptor.LABEL_PREFIX + "workspace-key");
            return value == null ? Optional.empty() : Optional.of(new ExecutionWorkspaceKey(value));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
