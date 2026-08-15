package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.RuntimeArtifactId;
import java.util.Objects;

/** Durable metadata produced for one AgentScope safe-point checkpoint. */
public record TaskAgentStateCheckpointResult(
        AgentStateSnapshotId snapshotId,
        RuntimeArtifactId runtimeArtifactId,
        long snapshotSequence,
        long checkpointSequence,
        TaskAgentStateSafePoint safePoint) {

    public TaskAgentStateCheckpointResult {
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        runtimeArtifactId = Objects.requireNonNull(runtimeArtifactId, "runtimeArtifactId");
        if (snapshotSequence < 1 || checkpointSequence < 1) {
            throw new IllegalArgumentException(
                    "snapshotSequence and checkpointSequence must be positive");
        }
        safePoint = Objects.requireNonNull(safePoint, "safePoint");
    }
}
