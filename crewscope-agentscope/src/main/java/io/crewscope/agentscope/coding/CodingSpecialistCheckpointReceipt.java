package io.crewscope.agentscope.coding;

import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.task.AgentStateSnapshotId;
import java.util.Objects;

/** Durable coordinates returned after the event, AgentState and Coding checkpoint all commit. */
public record CodingSpecialistCheckpointReceipt(
        CodingCheckpoint checkpoint,
        AgentStateSnapshotId snapshotId,
        long eventSequence) {

    public CodingSpecialistCheckpointReceipt {
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        if (eventSequence < 1) {
            throw new IllegalArgumentException("eventSequence must be positive");
        }
    }
}
