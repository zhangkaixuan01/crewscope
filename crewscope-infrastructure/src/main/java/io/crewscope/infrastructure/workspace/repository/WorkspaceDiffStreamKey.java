package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;

/** Path-free durable coordinates that bind one Diff stream to a Workspace recovery generation. */
public record WorkspaceDiffStreamKey(
        WorkItemScope scope,
        ExecutionWorkspaceId workspaceId,
        ExecutionWorkspaceFingerprint workspaceFingerprint,
        long recoveryGeneration) {

    public WorkspaceDiffStreamKey {
        scope = Objects.requireNonNull(scope, "scope");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        workspaceFingerprint = Objects.requireNonNull(workspaceFingerprint, "workspaceFingerprint");
        if (recoveryGeneration < 0) {
            throw new IllegalArgumentException("recoveryGeneration must not be negative");
        }
    }

    public static WorkspaceDiffStreamKey from(ExecutionWorkspace workspace) {
        ExecutionWorkspace current = Objects.requireNonNull(workspace, "workspace");
        return new WorkspaceDiffStreamKey(
                current.scope(),
                current.id(),
                current.fingerprint(),
                current.recoveryGeneration());
    }
}
