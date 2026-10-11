package io.crewscope.server.api;

import io.crewscope.domain.task.AgentRunContinuityGap;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The exact checkpoint interval a recovery run had to close over. */
public record ContinuityGapResponse(
        UUID previousRunId,
        UUID lastValidSnapshotId,
        long firstMissingCheckpoint,
        long lastMissingCheckpoint,
        String reason,
        Instant detectedAt) {
    static ContinuityGapResponse from(AgentRunContinuityGap value) {
        return new ContinuityGapResponse(
                value.previousRunId().value(),
                value.lastValidSnapshotId().map(id -> id.value()).orElse(null),
                value.firstMissingCheckpoint(),
                value.lastMissingCheckpoint(),
                value.reason().name(),
                value.detectedAt().value());
    }
}
