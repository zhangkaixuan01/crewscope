package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskExecution;
import java.util.Locale;
import java.util.Objects;

/** Stable path-independent key from which the managed Worktree directory is resolved. */
public record ExecutionWorkspaceKey(String value) {

    public ExecutionWorkspaceKey {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches(
                "ws-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-a(?:(?:[1-9][0-9]?)|100)")) {
            throw new DomainValidationException(
                    "executionWorkspace.workspaceKey", "must use the managed Workspace key format");
        }
    }

    public static ExecutionWorkspaceKey derive(ExecutionWorkspaceId id, int attempt) {
        if (attempt < 1 || attempt > TaskExecution.MAX_SUPPORTED_ATTEMPTS) {
            throw new DomainValidationException(
                    "executionWorkspace.attempt", "must be within the supported attempt range");
        }
        return new ExecutionWorkspaceKey(
                "ws-" + Objects.requireNonNull(id, "id").toString().toLowerCase(Locale.ROOT)
                        + "-a" + attempt);
    }

    @Override
    public String toString() {
        return value;
    }
}
