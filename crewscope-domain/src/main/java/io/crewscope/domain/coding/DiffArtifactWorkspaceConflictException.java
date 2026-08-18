package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/** Reports a second terminal DiffArtifact for the same ExecutionWorkspace. */
public final class DiffArtifactWorkspaceConflictException extends DomainException {

    public DiffArtifactWorkspaceConflictException(ExecutionWorkspaceId workspaceId) {
        super(new DomainError(
                DomainErrorCode.DIFF_ARTIFACT_WORKSPACE_CONFLICT,
                "Final DiffArtifact already exists for this ExecutionWorkspace",
                Map.of(
                        "executionWorkspaceId",
                        Objects.requireNonNull(workspaceId, "workspaceId").toString())));
    }
}
