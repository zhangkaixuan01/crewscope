package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Locale;
import java.util.Objects;

/** Platform-generated Git branch owned by exactly one TaskExecution attempt. */
public record ManagedWorkspaceBranch(String value) {

    public ManagedWorkspaceBranch {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches(
                "crewscope/tasks/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/attempt-(?:(?:[1-9][0-9]?)|100)")) {
            throw new DomainValidationException(
                    "executionWorkspace.managedBranch",
                    "must use the platform-generated TaskExecution branch format");
        }
    }

    public static ManagedWorkspaceBranch derive(TaskExecutionId executionId, int attempt) {
        if (attempt < 1 || attempt > TaskExecution.MAX_SUPPORTED_ATTEMPTS) {
            throw new DomainValidationException(
                    "executionWorkspace.attempt", "must be within the supported attempt range");
        }
        return new ManagedWorkspaceBranch(
                "crewscope/tasks/"
                        + Objects.requireNonNull(executionId, "executionId")
                                .toString()
                                .toLowerCase(Locale.ROOT)
                        + "/attempt-"
                        + attempt);
    }

    @Override
    public String toString() {
        return value;
    }
}
