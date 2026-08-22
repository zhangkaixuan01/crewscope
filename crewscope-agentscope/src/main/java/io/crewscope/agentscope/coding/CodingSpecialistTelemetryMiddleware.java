package io.crewscope.agentscope.coding;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Captures model and tool counters without retaining prompts, arguments or model output. */
final class CodingSpecialistTelemetryMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext runtimeContext,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        CodingSpecialistTelemetryAccumulator collector = collector(runtimeContext);
        if (collector == null) {
            return next.apply(input);
        }
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean();
        ModelCallInput effective = input;
        if (collector.structuredOutputRequired()
                && input.tools().stream().anyMatch(tool ->
                        "generate_response".equals(tool.getName()))) {
            // AgentScope 2.0 exposes generate_response for compatible providers. DeepSeek may
            // still end a completed tool loop with prose, so the bounded recovery call uses the
            // framework's native Specific tool choice instead of parsing untrusted free text.
            GenerateOptions options = GenerateOptions.mergeOptions(
                    GenerateOptions.builder()
                            .toolChoice(new ToolChoice.Specific("generate_response"))
                            .parallelToolCalls(false)
                            .build(),
                    input.options());
            effective = new ModelCallInput(
                    input.messages(), input.tools(), options, input.model());
        }
        return next.apply(effective)
                .doOnNext(event -> {
                    if (event instanceof ModelCallEndEvent ended) {
                        usage.set(ended.getUsage());
                    }
                })
                .doOnComplete(() -> recordModel(collector, usage.get(), recorded))
                .doOnError(ignored -> recordModel(collector, usage.get(), recorded))
                .doOnCancel(() -> recordModel(collector, usage.get(), recorded));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext runtimeContext,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        CodingSpecialistTelemetryAccumulator collector = collector(runtimeContext);
        if (collector != null) {
            List<ToolUseBlock> calls = input.toolCalls() == null
                    ? List.of()
                    : List.copyOf(input.toolCalls());
            collector.recordTools(calls.stream().map(ToolUseBlock::getName).toList());
        }
        return next.apply(input);
    }

    private static CodingSpecialistTelemetryAccumulator collector(RuntimeContext context) {
        return context == null ? null : context.get(CodingSpecialistTelemetryAccumulator.class);
    }

    private static void recordModel(
            CodingSpecialistTelemetryAccumulator collector,
            ChatUsage usage,
            AtomicBoolean recorded) {
        if (recorded.compareAndSet(false, true)) {
            // A failed logical call remains a real model call even when the Provider cannot return
            // token counters; zero usage is explicit, not estimated.
            collector.recordModel(usage);
        }
    }
}
