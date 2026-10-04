package io.crewscope.agentscope.skill;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Records one usage fact per real Provider attempt of a Skill Distiller call (M10-A03b)
 * without retaining prompts or model output. Follows the Knowledge Distiller telemetry
 * shape: the accumulator is a trusted {@link RuntimeContext} slot created by the runtime,
 * so the middleware is a no-op for any other caller of the shared builder.
 */
public final class SkillDistillerUsageMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext runtimeContext,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        SkillDistillerUsageAccumulator collector = collector(runtimeContext);
        if (collector == null) {
            return next.apply(input);
        }
        return observed(next.apply(input), collector, input.model().getModelName());
    }

    private static Flux<AgentEvent> observed(
            Flux<AgentEvent> events,
            SkillDistillerUsageAccumulator collector,
            String modelName) {
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        // A terminated attempt (complete, failed or cancelled) still consumed Provider
        // capacity and is a real usage fact. Reactor guarantees the terminal signals are
        // mutually exclusive and fire at most once per subscription, so record() runs at
        // most once per attempt; a resubscription is a genuinely new Provider attempt and
        // correctly records a second fact. Only the last ModelCallEndEvent's usage is
        // kept — the Distiller is a single-turn call, so one end event per attempt.
        return events
                .doOnNext(event -> {
                    if (event instanceof ModelCallEndEvent ended) {
                        usage.set(ended.getUsage());
                    }
                })
                .doOnComplete(() -> collector.record(usage.get(), modelName))
                .doOnError(ignored -> collector.record(usage.get(), modelName))
                .doOnCancel(() -> collector.record(usage.get(), modelName));
    }

    private static SkillDistillerUsageAccumulator collector(RuntimeContext context) {
        return context == null ? null : context.get(SkillDistillerUsageAccumulator.class);
    }
}
