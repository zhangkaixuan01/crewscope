package io.crewscope.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.ResolvedProviderBinding;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Fails closed when a required Provider has no current server-resolved Binding snapshot. */
public final class ProviderBindingSecurityMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext runtimeContext,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return validated(runtimeContext, () -> next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext runtimeContext,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return validated(runtimeContext, () -> next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext runtimeContext,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return validated(runtimeContext, () -> next.apply(input));
    }

    private static Flux<AgentEvent> validated(
            RuntimeContext runtimeContext,
            java.util.function.Supplier<Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            PlatformExecutionContext context =
                    PlatformRuntimeContextMiddleware.requireTrustedContext(runtimeContext);
            if (!context.hasAllRequiredProviderBindings()) {
                throw PlatformRuntimeContextMiddleware.denied(
                        "REQUIRED_PROVIDER_BINDING_MISSING");
            }
            for (ResolvedProviderBinding binding : context.providerBindings().values()) {
                if (!binding.target().organizationId().equals(context.scope().organizationId())
                        || !binding.target().teamId().equals(context.scope().teamId())
                        || !binding.target().workspaceId().equals(
                                context.scope().workspaceId())) {
                    throw PlatformRuntimeContextMiddleware.denied(
                            "PROVIDER_BINDING_SCOPE_MISMATCH");
                }
            }
            return next.get();
        });
    }
}
