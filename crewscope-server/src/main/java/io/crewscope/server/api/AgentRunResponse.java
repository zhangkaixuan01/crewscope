package io.crewscope.server.api;

import io.crewscope.domain.task.AgentRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One agent run with its segments, continuity gap and terminal verdict. */
public record AgentRunResponse(
        UUID id,
        UUID stepExecutionId,
        UUID runtimeSessionId,
        UUID agentPrincipalId,
        UUID agentProfileId,
        long agentProfileVersion,
        long runSequence,
        String status,
        List<AgentRunSegmentResponse> segments,
        ContinuityGapResponse continuityGap,
        AgentRunTerminalResponse terminal,
        long version,
        AuditResponse audit) {
    static AgentRunResponse from(AgentRun value) {
        return new AgentRunResponse(
                value.id().value(),
                value.stepExecutionId().map(id -> id.value()).orElse(null),
                value.runtimeSessionId().value(),
                value.agentPrincipalId().value(),
                value.agentProfileId().value(),
                value.agentProfileVersion(),
                value.runSequence(),
                value.status().name(),
                value.segments().stream().map(AgentRunSegmentResponse::from).toList(),
                value.continuityGap().map(ContinuityGapResponse::from).orElse(null),
                value.terminal().map(AgentRunTerminalResponse::from).orElse(null),
                value.version(),
                AuditResponse.from(value.audit()));
    }
}
