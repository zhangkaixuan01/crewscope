package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Exact version and hash of a WorkspacePolicy overlay. */
public record WorkspacePolicyOverlayReference(
        WorkspacePolicyOverlayId id, long version, TaskFactHash overlayHash) {

    public WorkspacePolicyOverlayReference {
        id = Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay.version", "must be positive");
        }
        overlayHash = Objects.requireNonNull(overlayHash, "overlayHash");
    }
}
