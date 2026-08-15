package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentRunContinuityGap;
import io.crewscope.domain.task.AgentStateSnapshotId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Trusted state bytes and recovery evidence returned without exposing them through public events. */
public record TaskAgentStateRecoveryResult(
        String agentStateJson,
        AgentStateSnapshotId snapshotId,
        long checkpointSequence,
        Optional<AgentRunContinuityGap> continuityGap,
        List<TaskAgentStateSkippedSnapshot> skippedSnapshots) {

    public TaskAgentStateRecoveryResult {
        agentStateJson = Objects.requireNonNull(agentStateJson, "agentStateJson");
        if (agentStateJson.isBlank()) {
            throw new IllegalArgumentException("agentStateJson must not be blank");
        }
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        if (checkpointSequence < 1) {
            throw new IllegalArgumentException("checkpointSequence must be positive");
        }
        continuityGap = Objects.requireNonNull(continuityGap, "continuityGap");
        skippedSnapshots = List.copyOf(
                Objects.requireNonNull(skippedSnapshots, "skippedSnapshots"));
    }

    @Override
    public String toString() {
        return "TaskAgentStateRecoveryResult[snapshotId=" + snapshotId
                + ", checkpointSequence=" + checkpointSequence
                + ", continuityGap=" + continuityGap
                + ", skippedSnapshots=" + skippedSnapshots
                + ", agentStateJson=[REDACTED]]";
    }
}
