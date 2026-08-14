package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Explicit loss interval carried by the replacement AgentRun after inexact recovery. */
public record AgentRunContinuityGap(
        AgentRunId previousRunId,
        Optional<AgentStateSnapshotId> lastValidSnapshotId,
        long firstMissingCheckpoint,
        long lastMissingCheckpoint,
        AgentRunContinuityGapReason reason,
        UtcTimestamp detectedAt) {

    public AgentRunContinuityGap {
        previousRunId = Objects.requireNonNull(previousRunId, "previousRunId");
        lastValidSnapshotId = Objects.requireNonNull(lastValidSnapshotId, "lastValidSnapshotId");
        if (firstMissingCheckpoint < 1 || lastMissingCheckpoint < firstMissingCheckpoint) {
            throw new DomainValidationException(
                    "agentRun.continuityGap.checkpoint",
                    "must describe a positive non-empty checkpoint interval");
        }
        reason = Objects.requireNonNull(reason, "reason");
        detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
    }
}
