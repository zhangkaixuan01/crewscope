package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Immutable metadata for one finite event stream within a durable AgentRun. */
public record AgentRunSegment(
        long sequence,
        AgentRunSegmentKind kind,
        Optional<AgentInterruptId> resumedFromInterruptId,
        AgentRunSegmentStatus status,
        UtcTimestamp startedAt,
        Optional<UtcTimestamp> endedAt) {

    public AgentRunSegment {
        if (sequence < 1) {
            throw new DomainValidationException("agentRun.segment.sequence", "must be positive");
        }
        kind = Objects.requireNonNull(kind, "kind");
        resumedFromInterruptId = Objects.requireNonNull(
                resumedFromInterruptId, "resumedFromInterruptId");
        status = Objects.requireNonNull(status, "status");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        endedAt = Objects.requireNonNull(endedAt, "endedAt");
        if ((kind == AgentRunSegmentKind.RESUME) != resumedFromInterruptId.isPresent()) {
            throw new DomainValidationException(
                    "agentRun.segment.resumedFromInterruptId",
                    "must be present only for a RESUME Segment");
        }
        if (status.isTerminal() != endedAt.isPresent()) {
            throw new DomainValidationException(
                    "agentRun.segment.endedAt", "must exist exactly for a terminal Segment");
        }
        if (endedAt.isPresent() && endedAt.orElseThrow().compareTo(startedAt) < 0) {
            throw new DomainValidationException(
                    "agentRun.segment.endedAt", "must not be before startedAt");
        }
    }

    static AgentRunSegment open(
            long sequence,
            AgentRunSegmentKind kind,
            Optional<AgentInterruptId> resumedFromInterruptId,
            UtcTimestamp startedAt) {
        return new AgentRunSegment(
                sequence,
                kind,
                resumedFromInterruptId,
                AgentRunSegmentStatus.ACTIVE,
                startedAt,
                Optional.empty());
    }

    AgentRunSegment finish(AgentRunSegmentStatus terminalStatus, UtcTimestamp endedAt) {
        AgentRunSegmentStatus required = Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (status != AgentRunSegmentStatus.ACTIVE || !required.isTerminal()) {
            throw new DomainValidationException(
                    "agentRun.segment.status", "an ACTIVE Segment may terminate exactly once");
        }
        return new AgentRunSegment(
                sequence,
                kind,
                resumedFromInterruptId,
                required,
                startedAt,
                Optional.of(Objects.requireNonNull(endedAt, "endedAt")));
    }
}
