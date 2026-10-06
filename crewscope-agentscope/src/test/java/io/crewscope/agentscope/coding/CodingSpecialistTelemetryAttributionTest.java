package io.crewscope.agentscope.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.agentscope.model.ModelCallAttributionRegistry;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelUsageRole;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * M10-F03 chat usage attribution: the telemetry middleware resolves the calling Model
 * instance against the call-scoped registry, so completed, failed and forced-choice-recovered
 * logical calls all land on one usage record carrying the pinned coordinates. Instances this
 * call never pinned (env-slot resolution) stay unattributed.
 */
class CodingSpecialistTelemetryAttributionTest {

    @Test
    void completedCallsCarryTheirPinnedCoordinates() {
        CodingSpecialistTelemetryAccumulator collector =
                new CodingSpecialistTelemetryAccumulator();
        Model primary = mock(Model.class);
        ModelCallAttribution attribution = attribution(ResolvedModelRole.PRIMARY);
        RuntimeContext context = context(collector, registry(primary, attribution));

        apply(context, input(primary, List.of()),
                ignored -> Flux.just(event(usage(1200, 80, 900)))).blockLast();

        CodingSpecialistModelUsage recorded = collector.snapshot().modelUsages().get(0);
        assertEquals(1200, recorded.inputTokens());
        assertEquals(1280, recorded.totalTokens());
        ModelCallAttribution resolved = recorded.attribution().orElseThrow();
        assertEquals("deepseek", resolved.providerKey().value());
        assertEquals("deepseek-flash", resolved.modelId().value());
        assertEquals(attribution.connectionId(), resolved.connectionId());
        assertEquals(3L, resolved.connectionVersion());
        assertEquals(ModelUsageRole.CHAT_PRIMARY, resolved.role());
    }

    @Test
    void failedCallsKeepTheirAttributionWithExplicitZeroUsage() {
        CodingSpecialistTelemetryAccumulator collector =
                new CodingSpecialistTelemetryAccumulator();
        Model fallback = mock(Model.class);
        RuntimeContext context = context(collector,
                registry(fallback, attribution(ResolvedModelRole.FALLBACK)));

        assertThrows(IllegalStateException.class, () ->
                apply(context, input(fallback, List.of()),
                        ignored -> Flux.<AgentEvent>error(new IllegalStateException("transport died")))
                        .blockLast());

        CodingSpecialistModelUsage recorded = collector.snapshot().modelUsages().get(0);
        assertEquals(0, recorded.inputTokens());
        assertEquals(0, recorded.totalTokens());
        assertEquals(ModelUsageRole.CHAT_FALLBACK,
                recorded.attribution().orElseThrow().role());
    }

    @Test
    void instancesThisCallNeverPinnedStayUnattributed() {
        CodingSpecialistTelemetryAccumulator collector =
                new CodingSpecialistTelemetryAccumulator();
        Model foreign = mock(Model.class);
        RuntimeContext context = context(collector, new ModelCallAttributionRegistry());

        apply(context, input(foreign, List.of()),
                ignored -> Flux.just(event(usage(10, 5, 0)))).blockLast();

        CodingSpecialistModelUsage recorded = collector.snapshot().modelUsages().get(0);
        assertEquals(15, recorded.totalTokens());
        assertTrue(recorded.attribution().isEmpty());
    }

    @Test
    void forcedChoiceDegradationAttributesBothTheRejectedAndRecoveredCalls() {
        CodingSpecialistTelemetryAccumulator collector =
                new CodingSpecialistTelemetryAccumulator();
        collector.requireStructuredOutput();
        Model primary = mock(Model.class);
        RuntimeContext context = context(collector,
                registry(primary, attribution(ResolvedModelRole.PRIMARY)));

        new CodingSpecialistTelemetryMiddleware()
                .onModelCall(mock(Agent.class), context, input(primary, List.of(
                        ToolSchema.builder()
                                .name("generate_response")
                                .description("Deliver the structured result")
                                .build())),
                        received -> received.options().getToolChoice() == null
                                // The degraded retry succeeds and completes the logical call.
                                ? Flux.just(event(usage(300, 40, 0)))
                                : Flux.error(new RuntimeException("model request rejected",
                                        new HttpTransportException(
                                                "HTTP request failed. Status: 400", 400,
                                                "{\"error\":{\"message\":\"Thinking mode does"
                                                        + " not support this tool_choice\"}}"))))
                .blockLast();

        // The rejected forced request and the degraded retry are two terminated logical
        // calls on the same pinned instance: the first keeps explicit zero counters, the
        // second carries the real ones — both with the same attribution coordinates.
        assertEquals(2, collector.snapshot().modelUsages().size());
        CodingSpecialistModelUsage rejected = collector.snapshot().modelUsages().get(0);
        assertEquals(0, rejected.totalTokens());
        assertEquals(ModelUsageRole.CHAT_PRIMARY, rejected.attribution().orElseThrow().role());
        CodingSpecialistModelUsage recovered = collector.snapshot().modelUsages().get(1);
        assertEquals(340, recovered.totalTokens());
        assertEquals(ModelUsageRole.CHAT_PRIMARY, recovered.attribution().orElseThrow().role());
    }

    private static ModelCallAttributionRegistry registry(
            Model model, ModelCallAttribution attribution) {
        return new ModelCallAttributionRegistry().register(model, attribution);
    }

    private static RuntimeContext context(
            CodingSpecialistTelemetryAccumulator collector,
            ModelCallAttributionRegistry attributions) {
        return RuntimeContext.builder()
                .put(CodingSpecialistTelemetryAccumulator.class, collector)
                .put(ModelCallAttributionRegistry.class, attributions)
                .build();
    }

    private static ModelCallInput input(Model model, List<ToolSchema> tools) {
        return new ModelCallInput(
                List.of(), tools, GenerateOptions.builder().build(), model);
    }

    private static Flux<AgentEvent> apply(
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return new CodingSpecialistTelemetryMiddleware()
                .onModelCall(mock(Agent.class), context, input, next);
    }

    private static ModelCallAttribution attribution(ResolvedModelRole role) {
        return ModelCallAttribution.chat(
                new ModelProviderKey("deepseek"),
                new ModelId("deepseek-flash"),
                new ModelConnectionId(UUID.randomUUID()),
                3L,
                role);
    }

    private static ChatUsage usage(int input, int output, int cached) {
        return new ChatUsage(input, output, cached, 0.001);
    }

    private static ModelCallEndEvent event(ChatUsage usage) {
        return new ModelCallEndEvent("m10-f03-attempt", usage);
    }
}
