package io.crewscope.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.crewscope.application.execution.AgentStatePreflight;
import io.crewscope.application.execution.PlatformExecutionContext;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Revalidates Redis state inside AgentScope's per-Session FIFO gate before reasoning or acting. */
public final class AgentStatePreflightMiddleware implements MiddlewareBase {

    private final AgentStatePreflight preflight;

    public AgentStatePreflightMiddleware(AgentStatePreflight preflight) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext runtimeContext,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            verifyOnce(runtimeContext);
            return next.apply(input);
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext runtimeContext,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            verifyOnce(runtimeContext);
            return next.apply(input);
        });
    }

    private void verifyOnce(RuntimeContext runtimeContext) {
        RuntimeContext required = Objects.requireNonNull(runtimeContext, "runtimeContext");
        synchronized (required) {
            if (required.get(VerifiedStatePreflight.class) != null) {
                return;
            }
            PlatformExecutionContext trusted =
                    PlatformRuntimeContextMiddleware.requireTrustedContext(required);
            preflight.verifyReady(trusted.agentScopeSessionKey());
            required.put(VerifiedStatePreflight.class, new VerifiedStatePreflight());
        }
    }

    /** RuntimeContext-local marker; it contains no identity or state content. */
    private static final class VerifiedStatePreflight {}
}
