package io.crewscope.agentscope.skill;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.crewscope.agentscope.PlatformExecutionSecurityException;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Validates the Team/member-bound Skill Distiller session before the tool-less Agent
 * reasons (M10-A03b).
 *
 * <p>The Distiller owns no Conversation, tools or personal provider binding. Its trusted
 * execution boundary is the server-created {@link SkillDistillerRuntimeSession} plus the
 * exact AgentScope user/session coordinates derived from that immutable session.
 */
public final class SkillDistillerRuntimeContextMiddleware implements MiddlewareBase {

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

    /** Returns the verified session for later Distiller-specific interception points. */
    public static SkillDistillerRuntimeSession requireTrustedContext(
            RuntimeContext runtimeContext) {
        RuntimeContext requiredRuntime = Objects.requireNonNull(runtimeContext, "runtimeContext");
        SkillDistillerRuntimeSession session =
                requiredRuntime.get(SkillDistillerRuntimeSession.class);
        if (session == null) {
            throw denied("SKILL_DISTILLER_CONTEXT_MISSING");
        }
        if (!session.agentScopeKey().userId().equals(requiredRuntime.getUserId())
                || !session.agentScopeKey().sessionId().equals(requiredRuntime.getSessionId())) {
            throw denied("SKILL_DISTILLER_SESSION_CONTEXT_MISMATCH");
        }
        if (!session.distillerPrincipalId().equals(
                        SkillDistillerInitialization.stablePrincipalId(session.teamId()))
                || !session.distillerProfileId().equals(
                        SkillDistillerInitialization.stableProfileId(session.teamId()))) {
            throw denied("SKILL_DISTILLER_IDENTITY_INVALID");
        }
        return session;
    }

    private static PlatformExecutionSecurityException denied(String code) {
        return new PlatformExecutionSecurityException(code);
    }
}
