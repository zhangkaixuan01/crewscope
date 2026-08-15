package io.crewscope.application.task;

import java.util.Objects;
import java.util.Optional;

/** Versions committed by one first-time Worker command execution. */
public record WorkerTaskCommandResult(
        WorkerCommandOperation operation,
        Optional<Long> taskExecutionVersion,
        Optional<Long> leaseVersion) {

    public WorkerTaskCommandResult {
        operation = Objects.requireNonNull(operation, "operation");
        taskExecutionVersion = requireVersion(taskExecutionVersion, "taskExecutionVersion");
        leaseVersion = requireVersion(leaseVersion, "leaseVersion");
        if (taskExecutionVersion.isEmpty() && leaseVersion.isEmpty()) {
            throw new IllegalArgumentException("at least one committed version must exist");
        }
    }

    private static Optional<Long> requireVersion(Optional<Long> value, String field) {
        return Objects.requireNonNull(value, field).map(version -> {
            if (version < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
            return version;
        });
    }
}
