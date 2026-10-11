package io.crewscope.server.api;

import io.crewscope.domain.task.AgentRunSegment;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One finite run segment; a RESUME segment names the interrupt it recovered from. */
public record AgentRunSegmentResponse(
        long sequence,
        String kind,
        UUID resumedFromInterruptId,
        String status,
        Instant startedAt,
        Instant endedAt) {
    static AgentRunSegmentResponse from(AgentRunSegment value) {
        return new AgentRunSegmentResponse(
                value.sequence(),
                value.kind().name(),
                value.resumedFromInterruptId().map(id -> id.value()).orElse(null),
                value.status().name(),
                value.startedAt().value(),
                value.endedAt().map(time -> time.value()).orElse(null));
    }
}
