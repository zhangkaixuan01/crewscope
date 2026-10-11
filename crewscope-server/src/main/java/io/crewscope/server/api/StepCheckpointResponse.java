package io.crewscope.server.api;

import io.crewscope.domain.task.StepCheckpoint;
import java.time.Instant;
import java.util.UUID;

/** One recorded step checkpoint; payload hashes never leave the server. */
public record StepCheckpointResponse(
        long sequence, String code, UUID recordedByPrincipalId, Instant recordedAt) {
    static StepCheckpointResponse from(StepCheckpoint value) {
        return new StepCheckpointResponse(
                value.sequence(),
                value.code(),
                value.recordedByPrincipalId().value(),
                value.recordedAt().value());
    }
}
