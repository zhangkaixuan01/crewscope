package io.crewscope.server.api;

import io.crewscope.domain.task.TaskExecutionControlRequest;
import java.time.Instant;
import java.util.UUID;

/** A pending member control request travelling with its row. */
public record ControlRequestResponse(
        String type, UUID requestedByPrincipalId, Instant requestedAt, String reason) {
    static ControlRequestResponse from(TaskExecutionControlRequest value) {
        return new ControlRequestResponse(
                value.type().name(),
                value.requestedByPrincipalId().value(),
                value.requestedAt().value(),
                value.reason());
    }
}
