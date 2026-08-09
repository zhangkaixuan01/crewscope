package io.crewscope.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Validates the server-resolved identity and Scope snapshot before AgentScope starts reasoning. */
public final class PlatformRuntimeContextMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext runtimeContext,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            requireTrustedContext(runtimeContext);
            return next.apply(input);
        });
    }

    /** Shared validation used by later security Middleware interception points. */
    static PlatformExecutionContext requireTrustedContext(RuntimeContext runtimeContext) {
        RuntimeContext requiredRuntime = Objects.requireNonNull(
                runtimeContext, "runtimeContext");
        PlatformExecutionContext context = requiredRuntime.get(PlatformExecutionContext.class);
        if (context == null) {
            throw denied("PLATFORM_CONTEXT_MISSING");
        }
        if (!context.agentScopeSessionKey().userId().equals(requiredRuntime.getUserId())
                || !context.agentScopeSessionKey().sessionId().equals(
                        requiredRuntime.getSessionId())) {
            throw denied("AGENTSCOPE_SESSION_CONTEXT_MISMATCH");
        }
        if (context.workspaceType() != WorkspaceType.TEAM
                || context.teamRoleKeys().isEmpty()
                || !context.userParticipantId().equals(
                        ConversationParticipantId.forPrincipal(
                                context.conversationId(), context.requestPrincipalId()))
                || !context.agentParticipantId().equals(
                        ConversationParticipantId.forPrincipal(
                                context.conversationId(),
                                context.personalAgentPrincipalId()))) {
            throw denied("PLATFORM_SCOPE_INVALID");
        }
        return context;
    }

    static PlatformExecutionSecurityException denied(String code) {
        return new PlatformExecutionSecurityException(code);
    }
}
