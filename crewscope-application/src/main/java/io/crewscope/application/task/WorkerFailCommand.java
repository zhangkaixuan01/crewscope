package io.crewscope.application.task;

import io.crewscope.domain.task.TaskExecutionFailure;
import java.util.Objects;

/** Safe terminal failure and both expected ownership versions. */
public record WorkerFailCommand(
        long expectedExecutionVersion,
        long expectedLeaseVersion,
        TaskExecutionFailure failure) {
    public WorkerFailCommand {
        if (expectedExecutionVersion < 0 || expectedLeaseVersion < 0) {
            throw new IllegalArgumentException("expected versions must not be negative");
        }
        failure = Objects.requireNonNull(failure, "failure");
    }
}
