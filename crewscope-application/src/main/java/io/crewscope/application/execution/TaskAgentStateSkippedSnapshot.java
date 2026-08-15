package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentStateSnapshotId;
import java.util.Objects;

/** Sanitized evidence for one snapshot skipped during bounded recovery fallback. */
public record TaskAgentStateSkippedSnapshot(
        AgentStateSnapshotId snapshotId,
        long checkpointSequence,
        TaskAgentStateSkipReason reason) {

    public TaskAgentStateSkippedSnapshot {
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        if (checkpointSequence < 1) {
            throw new IllegalArgumentException("checkpointSequence must be positive");
        }
        reason = Objects.requireNonNull(reason, "reason");
    }
}
