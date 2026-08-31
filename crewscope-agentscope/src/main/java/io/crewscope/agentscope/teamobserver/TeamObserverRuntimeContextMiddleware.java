package io.crewscope.agentscope.teamobserver;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.crewscope.agentscope.PlatformExecutionSecurityException;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Validates the Team/member-bound Observer session before the read-only Agent starts reasoning.
 *
 * <p>Team Observer does not own a Personal Conversation, participant pair or personal provider
 * binding. Its trusted execution boundary is the server-created {@link TeamObserverRuntimeSession}
 * plus the exact AgentScope user/session coordinates derived from that immutable session.
 */
public final class TeamObserverRuntimeContextMiddleware implements MiddlewareBase {

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

    /** Returns the verified session for later Observer-specific interception points. */
    public static TeamObserverRuntimeSession requireTrustedContext(RuntimeContext runtimeContext) {
        RuntimeContext requiredRuntime = Objects.requireNonNull(runtimeContext, "runtimeContext");
        TeamObserverRuntimeSession session = requiredRuntime.get(TeamObserverRuntimeSession.class);
        if (session == null) {
            throw denied("TEAM_OBSERVER_CONTEXT_MISSING");
        }
        if (!session.agentScopeKey().userId().equals(requiredRuntime.getUserId())
                || !session.agentScopeKey().sessionId().equals(requiredRuntime.getSessionId())) {
            throw denied("TEAM_OBSERVER_SESSION_CONTEXT_MISMATCH");
        }
        if (!session.observerPrincipalId().equals(
                        TeamObserverInitialization.stablePrincipalId(session.teamId()))
                || !session.observerProfileId().equals(
                        TeamObserverInitialization.stableProfileId(session.teamId()))) {
            throw denied("TEAM_OBSERVER_IDENTITY_INVALID");
        }
        return session;
    }

    private static PlatformExecutionSecurityException denied(String code) {
        return new PlatformExecutionSecurityException(code);
    }
}
