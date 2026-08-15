package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentRun;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable event outcome; secret Interrupt Tokens are intentionally absent. */
public record TaskRuntimeEventCommitResult(
        TaskRuntimeEventCommitStatus status,
        UUID domainEventId,
        AgentRun agentRun,
        Optional<AgentInterruptId> interruptId) {

    public TaskRuntimeEventCommitResult {
        status = Objects.requireNonNull(status, "status");
        domainEventId = Objects.requireNonNull(domainEventId, "domainEventId");
        agentRun = Objects.requireNonNull(agentRun, "agentRun");
        interruptId = Objects.requireNonNull(interruptId, "interruptId");
    }
}
