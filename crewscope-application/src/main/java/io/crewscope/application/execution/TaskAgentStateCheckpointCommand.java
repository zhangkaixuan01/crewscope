package io.crewscope.application.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Sensitive Worker command for publishing state after the referenced runtime event is durable. */
public record TaskAgentStateCheckpointCommand(
        TaskExecutionRuntimeFacts facts,
        TaskAgentStateIdentity identity,
        long segmentSequence,
        long eventSequence,
        TaskAgentStateSafePoint safePoint,
        String agentStateJson,
        Optional<Duration> timeToLive) {

    public TaskAgentStateCheckpointCommand {
        facts = Objects.requireNonNull(facts, "facts");
        identity = Objects.requireNonNull(identity, "identity");
        if (segmentSequence < 1 || eventSequence < 1) {
            throw new IllegalArgumentException("segmentSequence and eventSequence must be positive");
        }
        safePoint = Objects.requireNonNull(safePoint, "safePoint");
        agentStateJson = Objects.requireNonNull(agentStateJson, "agentStateJson");
        if (agentStateJson.isBlank()) {
            throw new IllegalArgumentException("agentStateJson must not be blank");
        }
        timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
        timeToLive.ifPresent(value -> {
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("timeToLive must be positive");
            }
        });
        requireBoundary(facts, identity, segmentSequence);
    }

    private static void requireBoundary(
            TaskExecutionRuntimeFacts facts,
            TaskAgentStateIdentity identity,
            long segmentSequence) {
        boolean current = identity.taskExecutionId().equals(facts.execution().id().value())
                && identity.agentRunId().equals(facts.agentRun().id().value())
                && identity.userId().equals(facts.runtimeSession().agentScopeKey().userId())
                && identity.sessionId().equals(facts.runtimeSession().agentScopeKey().sessionId())
                && identity.agentVersion().equals(
                        Long.toString(facts.runtimeSession().agentProfileVersion()))
                && segmentSequence == facts.agentRun().currentSegment().sequence();
        if (!current) {
            throw new IllegalArgumentException(
                    "Agent state checkpoint crossed the Task, Run, Agent or Segment boundary");
        }
    }

    @Override
    public String toString() {
        return "TaskAgentStateCheckpointCommand[taskExecutionId=" + identity.taskExecutionId()
                + ", agentRunId=" + identity.agentRunId()
                + ", segmentSequence=" + segmentSequence
                + ", eventSequence=" + eventSequence
                + ", safePoint=" + safePoint
                + ", agentStateJson=[REDACTED]]";
    }
}
