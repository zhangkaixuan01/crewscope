package io.crewscope.agentscope.agui;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.application.execution.PlatformExecutionContext;
import java.util.Objects;
import java.util.UUID;

/** Trusted server-side facts that identify one Personal Agent protocol invocation. */
public final class ServerResolvedAguiInvocation {

    private final AgentRuntimeSession runtimeSession;
    private final PrincipalId requestPrincipalId;
    private final String resolvedAgentId;
    private final MessageId inputMessageId;
    private final UUID runId;
    private final UUID correlationId;
    private final PlatformExecutionContext platformContext;

    private ServerResolvedAguiInvocation(
            AgentRuntimeSession runtimeSession,
            PrincipalId requestPrincipalId,
            String resolvedAgentId,
            MessageId inputMessageId,
            UUID runId,
            UUID correlationId,
            PlatformExecutionContext platformContext) {
        this.runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        if (!this.runtimeSession.canInvoke()) {
            throw new IllegalArgumentException("AgentRuntimeSession must be active");
        }
        this.requestPrincipalId = Objects.requireNonNull(
                requestPrincipalId, "requestPrincipalId");
        this.resolvedAgentId = requireText(resolvedAgentId, "resolvedAgentId");
        this.inputMessageId = Objects.requireNonNull(inputMessageId, "inputMessageId");
        this.runId = requireUuid(runId, "runId");
        this.correlationId = requireUuid(correlationId, "correlationId");
        this.platformContext = Objects.requireNonNull(platformContext, "platformContext");
        this.platformContext.requireMatches(
                this.runtimeSession,
                this.platformContext.invocationId(),
                this.correlationId);
        if (!this.platformContext.requestPrincipalId().equals(this.requestPrincipalId)
                || !this.platformContext.invocationId().value().equals(this.runId)) {
            throw new IllegalArgumentException(
                    "PlatformExecutionContext must match the AG-UI invocation binding");
        }
    }

    /** Creates a protocol binding only from an active, already validated durable session. */
    public static ServerResolvedAguiInvocation forActiveSession(
            AgentRuntimeSession runtimeSession,
            PrincipalId requestPrincipalId,
            String resolvedAgentId,
            MessageId inputMessageId,
            UUID runId,
            UUID correlationId,
            PlatformExecutionContext platformContext) {
        return new ServerResolvedAguiInvocation(
                runtimeSession,
                requestPrincipalId,
                resolvedAgentId,
                inputMessageId,
                runId,
                correlationId,
                platformContext);
    }

    public AgentRuntimeSession runtimeSession() {
        return runtimeSession;
    }

    public ConversationScope scope() {
        return runtimeSession.scope();
    }

    public ConversationId conversationId() {
        return runtimeSession.conversationId();
    }

    public PrincipalId requestPrincipalId() {
        return requestPrincipalId;
    }

    public PrincipalId personalAgentPrincipalId() {
        return runtimeSession.personalAgentPrincipalId();
    }

    public AgentScopeSessionKey agentScopeSessionKey() {
        return runtimeSession.agentScopeKey();
    }

    public String resolvedAgentId() {
        return resolvedAgentId;
    }

    public MessageId inputMessageId() {
        return inputMessageId;
    }

    public String threadId() {
        return conversationId().toString();
    }

    public String runId() {
        return runId.toString();
    }

    public UUID correlationId() {
        return correlationId;
    }

    public PlatformExecutionContext platformContext() {
        return platformContext;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static UUID requireUuid(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (required.getMostSignificantBits() == 0L && required.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }
}
