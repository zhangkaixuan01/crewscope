package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted command resolving one durable interruption without persisting its raw answer or token. */
public record AgentRunResumeCommand(
        OrganizationId organizationId,
        AgentRunId agentRunId,
        AgentInterruptId interruptId,
        UUID resumeRequestId,
        ExecutionInterruptToken interruptToken,
        RuntimeContentHash responseHash,
        PrincipalId actorId,
        UUID correlationId,
        Optional<UUID> causationId) {

    public AgentRunResumeCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        interruptId = Objects.requireNonNull(interruptId, "interruptId");
        resumeRequestId = requireId(resumeRequestId, "resumeRequestId");
        interruptToken = Objects.requireNonNull(interruptToken, "interruptToken");
        responseHash = Objects.requireNonNull(responseHash, "responseHash");
        actorId = Objects.requireNonNull(actorId, "actorId");
        correlationId = requireId(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId")
                .map(value -> requireId(value, "causationId"));
    }

    private static UUID requireId(UUID value, String field) {
        return AggregateId.requireValue(value, field);
    }
}
