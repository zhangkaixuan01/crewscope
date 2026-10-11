package io.crewscope.server.api;

import io.crewscope.domain.task.AgentInterrupt;
import java.time.Instant;
import java.util.UUID;

/** One agent interrupt; its opaque tokens never leave the server. */
public record AgentInterruptResponse(
        UUID id,
        UUID agentRunId,
        long segmentSequence,
        String kind,
        String status,
        UUID resolvedByPrincipalId,
        Instant resolvedAt,
        long version,
        AuditResponse audit) {
    static AgentInterruptResponse from(AgentInterrupt value) {
        return new AgentInterruptResponse(
                value.id().value(),
                value.agentRunId().value(),
                value.segmentSequence(),
                value.kind().name(),
                value.status().name(),
                value.resolution().map(resolution -> resolution.resolvedBy().value()).orElse(null),
                value.resolution().map(resolution -> resolution.resolvedAt().value()).orElse(null),
                value.version(),
                AuditResponse.from(value.audit()));
    }
}
