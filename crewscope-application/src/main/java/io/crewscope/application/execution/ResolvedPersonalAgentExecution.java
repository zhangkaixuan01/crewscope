package io.crewscope.application.execution;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import java.util.Objects;

/** Current durable Session and authorization snapshot for one Personal Agent operation. */
public record ResolvedPersonalAgentExecution(
        AgentRuntimeSession runtimeSession, PlatformExecutionContext platformContext) {

    public ResolvedPersonalAgentExecution {
        runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        platformContext = Objects.requireNonNull(platformContext, "platformContext");
        platformContext.requireMatches(
                runtimeSession,
                platformContext.invocationId(),
                platformContext.correlationId());
    }
}
