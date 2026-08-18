package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Platform-generated local Git reference retaining the finalized delivery commit. */
public record WorkspaceArchiveReference(String value) {

    public WorkspaceArchiveReference {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches(
                "refs/crewscope/archives/ws-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-a(?:(?:[1-9][0-9]?)|100)")) {
            throw new DomainValidationException(
                    "executionWorkspace.archiveReference",
                    "must use the platform-generated archive reference format");
        }
    }

    public static WorkspaceArchiveReference derive(ExecutionWorkspaceKey workspaceKey) {
        return new WorkspaceArchiveReference(
                "refs/crewscope/archives/"
                        + Objects.requireNonNull(workspaceKey, "workspaceKey").value());
    }

    @Override
    public String toString() {
        return value;
    }
}
