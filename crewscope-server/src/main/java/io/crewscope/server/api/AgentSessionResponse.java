package io.crewscope.server.api;

import io.crewscope.domain.task.TaskAgentRuntimeSession;
import java.time.Instant;
import java.util.UUID;

/** One agent runtime session bound to the execution or one step. */
public record AgentSessionResponse(
        UUID id,
        UUID stepExecutionId,
        String purpose,
        UUID agentPrincipalId,
        UUID agentProfileId,
        long agentProfileVersion,
        String status,
        long version,
        AuditResponse audit) {
    static AgentSessionResponse from(TaskAgentRuntimeSession value) {
        return new AgentSessionResponse(
                value.id().value(),
                value.stepExecutionId().map(id -> id.value()).orElse(null),
                value.purpose().name(),
                value.agentPrincipalId().value(),
                value.agentProfileId().value(),
                value.agentProfileVersion(),
                value.status().name(),
                value.version(),
                AuditResponse.from(value.audit()));
    }
}
