package io.crewscope.application.execution;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import java.util.Objects;
import java.util.UUID;

/** Exact logical invocation and Session identity for an explicit business cancellation. */
public record ConversationCancelRequest(
        RuntimeInvocationId invocationId,
        AgentRuntimeSession runtimeSession,
        String reason,
        UUID correlationId,
        PlatformExecutionContext platformContext) {

    public ConversationCancelRequest {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        reason = Objects.requireNonNull(reason, "reason").strip();
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        platformContext = Objects.requireNonNull(platformContext, "platformContext");
        platformContext.requireMatches(runtimeSession, invocationId, correlationId);
        if (reason.isEmpty()
                || reason.length() > 500
                || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "cancel reason must contain 1 to 500 printable characters");
        }
    }
}
