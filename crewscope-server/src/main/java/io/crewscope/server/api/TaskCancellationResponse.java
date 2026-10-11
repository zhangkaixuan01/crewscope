package io.crewscope.server.api;

import io.crewscope.domain.task.TaskCancellation;
import java.time.Instant;
import java.util.UUID;

/** Who cancelled a Task, when, and why. */
public record TaskCancellationResponse(UUID cancelledByPrincipalId, Instant cancelledAt, String reason) {
    static TaskCancellationResponse from(TaskCancellation value) {
        return new TaskCancellationResponse(
                value.cancelledByPrincipalId().value(), value.cancelledAt().value(), value.reason());
    }
}
