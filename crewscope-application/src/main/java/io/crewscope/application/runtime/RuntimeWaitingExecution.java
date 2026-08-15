package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import java.util.Objects;

/** One Team execution waiting specifically for a compatible Runtime capability carrier. */
public record RuntimeWaitingExecution(
        TaskExecution execution, RuntimeCapabilities requiredCapabilities) {

    public RuntimeWaitingExecution {
        execution = Objects.requireNonNull(execution, "execution");
        requiredCapabilities = Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities");
        if (execution.status() != TaskExecutionStatus.WAITING
                || execution.waiting().filter(value ->
                                value.reason() == TaskExecutionWaitReason.RUNTIME)
                        .isEmpty()) {
            throw new IllegalArgumentException(
                    "execution must be WAITING for the RUNTIME reason");
        }
    }
}
