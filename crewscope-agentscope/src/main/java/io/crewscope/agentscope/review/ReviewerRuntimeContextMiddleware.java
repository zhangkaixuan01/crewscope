package io.crewscope.agentscope.review;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.crewscope.agentscope.PlatformExecutionSecurityException;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Validates the server-created REVIEW session before the advisory Reviewer starts reasoning.
 *
 * <p>A reviewer owns no Personal Conversation, participant pair or personal provider binding,
 * so the conversation-semantics {@code PlatformRuntimeContextMiddleware} cannot apply (defect
 * 20, M9b-Q02: it rejected the reviewer call with PLATFORM_CONTEXT_MISSING). Its trusted
 * execution boundary is the server-created step-less REVIEW {@link TaskAgentRuntimeSession}
 * plus the exact AgentScope user/session coordinates derived from that immutable session —
 * the same boundary shape as the Team Observer chain.
 */
public final class ReviewerRuntimeContextMiddleware implements MiddlewareBase {

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

    /** Returns the verified REVIEW session for later reviewer-specific interception points. */
    public static TaskAgentRuntimeSession requireTrustedContext(RuntimeContext runtimeContext) {
        RuntimeContext requiredRuntime = Objects.requireNonNull(runtimeContext, "runtimeContext");
        TaskAgentRuntimeSession session = requiredRuntime.get(TaskAgentRuntimeSession.class);
        if (session == null) {
            throw denied("REVIEWER_CONTEXT_MISSING");
        }
        if (session.purpose() != TaskAgentSessionPurpose.REVIEW || !session.canInvoke()) {
            throw denied("REVIEWER_SESSION_PURPOSE_INVALID");
        }
        if (!session.agentScopeKey().userId().equals(requiredRuntime.getUserId())
                || !session.agentScopeKey().sessionId().equals(requiredRuntime.getSessionId())) {
            throw denied("REVIEWER_SESSION_CONTEXT_MISMATCH");
        }
        return session;
    }

    private static PlatformExecutionSecurityException denied(String code) {
        return new PlatformExecutionSecurityException(code);
    }
}
