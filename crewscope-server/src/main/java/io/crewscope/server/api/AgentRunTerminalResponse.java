package io.crewscope.server.api;

import io.crewscope.domain.task.AgentRunTerminal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The terminal verdict of one agent run. */
public record AgentRunTerminalResponse(
        String status, String failureCode, UUID resultArtifactId, Instant occurredAt) {
    static AgentRunTerminalResponse from(AgentRunTerminal value) {
        return new AgentRunTerminalResponse(
                value.status().name(),
                value.failureCode().orElse(null),
                value.resultArtifactId().map(id -> id.value()).orElse(null),
                value.occurredAt().value());
    }
}
