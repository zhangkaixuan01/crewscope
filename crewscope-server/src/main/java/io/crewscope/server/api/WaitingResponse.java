package io.crewscope.server.api;

import io.crewscope.domain.task.TaskExecutionWaiting;
import java.time.Instant;

/** Why an execution is parked and since when. */
public record WaitingResponse(String reason, Instant waitingSince) {
    static WaitingResponse from(TaskExecutionWaiting value) {
        return new WaitingResponse(value.reason().name(), value.waitingSince().value());
    }
}
