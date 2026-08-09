package io.crewscope.application.execution;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageType;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted initial input for one Personal Agent Conversation invocation. */
public record ConversationExecutionRequest(
        RuntimeInvocationId invocationId,
        AgentRuntimeSession runtimeSession,
        Message inputMessage,
        Optional<StructuredOutputSpec<?>> structuredOutput,
        UUID correlationId,
        PlatformExecutionContext platformContext) {

    public ConversationExecutionRequest {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        runtimeSession = requireActiveSession(runtimeSession);
        inputMessage = requireUserMessage(runtimeSession, inputMessage);
        structuredOutput = Objects.requireNonNull(structuredOutput, "structuredOutput");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        platformContext = Objects.requireNonNull(platformContext, "platformContext");
        platformContext.requireMatches(runtimeSession, invocationId, correlationId);
    }

    static AgentRuntimeSession requireActiveSession(AgentRuntimeSession session) {
        AgentRuntimeSession required = Objects.requireNonNull(session, "runtimeSession");
        if (!required.canInvoke()) {
            throw new IllegalArgumentException("runtimeSession must be ACTIVE");
        }
        return required;
    }

    static Message requireUserMessage(AgentRuntimeSession session, Message message) {
        Message required = Objects.requireNonNull(message, "message");
        if (required.type() != MessageType.USER_MESSAGE
                || !required.scope().equals(session.scope())
                || !required.conversationId().equals(session.conversationId())
                || required.authorPrincipalId().filter(session.ownerPrincipalId()::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "message must be a committed USER Message from the Session owner in the bound Conversation");
        }
        return required;
    }
}
