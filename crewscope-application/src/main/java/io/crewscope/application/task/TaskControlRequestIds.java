package io.crewscope.application.task;

import io.crewscope.domain.task.TaskExecutionControlRequest;
import io.crewscope.domain.task.TaskExecutionId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Derives a stable non-secret runtime control ID from one immutable durable request fact. */
public final class TaskControlRequestIds {

    private TaskControlRequestIds() {}

    public static UUID from(
            TaskExecutionId executionId, TaskExecutionControlRequest request) {
        TaskExecutionId execution = Objects.requireNonNull(executionId, "executionId");
        TaskExecutionControlRequest required = Objects.requireNonNull(request, "request");
        String canonical = String.join(
                "|",
                "crewscope-task-control-v1",
                execution.toString(),
                required.type().name(),
                required.requestedByPrincipalId().toString(),
                required.requestedAt().toString(),
                required.reason());
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }
}
