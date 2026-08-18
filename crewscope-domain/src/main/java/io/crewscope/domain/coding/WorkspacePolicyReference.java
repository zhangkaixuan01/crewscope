package io.crewscope.domain.coding;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Hash-closed immutable WorkspacePolicy reference. */
public record WorkspacePolicyReference(WorkspacePolicyId id, TaskFactHash policyHash) {

    public WorkspacePolicyReference {
        id = Objects.requireNonNull(id, "id");
        policyHash = Objects.requireNonNull(policyHash, "policyHash");
    }
}
