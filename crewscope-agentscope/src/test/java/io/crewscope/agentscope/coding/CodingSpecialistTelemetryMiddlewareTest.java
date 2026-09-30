package io.crewscope.agentscope.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Coverage for the forced-choice degradation (M9b-Q02 defect 11): thinking-mode providers
 * (deepseek-v4-flash) answer the forced generate_response tool_choice with 400, so the bounded
 * recovery call must retry once without the force instead of dying as CODING_RUNTIME_FAILED.
 */
class CodingSpecialistTelemetryMiddlewareTest {

    @Test
    void thinkingModeRejectionFallsBackToTheUnforcedRequest() {
        CodingSpecialistTelemetryAccumulator collector = new CodingSpecialistTelemetryAccumulator();
        collector.requireStructuredOutput();
        ModelCallInput input = structuredDeliveryInput();
        List<GenerateOptions> seen = new ArrayList<>();

        Function<ModelCallInput, Flux<AgentEvent>> next = received -> {
            seen.add(received.options());
            if (seen.size() == 1) {
                // Wrapped the way the model retry layer delivers transport failures.
                return Flux.error(new RuntimeException("model request rejected",
                        new HttpTransportException(
                                "HTTP request failed. Status: 400", 400,
                                "{\"error\":{\"message\":\"Thinking mode does not support this tool_choice\"}}")));
            }
            return Flux.empty();
        };

        new CodingSpecialistTelemetryMiddleware()
                .onModelCall(mock(Agent.class), context(collector), input, next)
                .blockLast();

        assertEquals(2, seen.size());
        assertInstanceOf(ToolChoice.Specific.class, (ToolChoice) seen.get(0).getToolChoice());
        assertNull(seen.get(1).getToolChoice());
    }

    @Test
    void unrelatedClientErrorPropagatesWithoutFallback() {
        CodingSpecialistTelemetryAccumulator collector = new CodingSpecialistTelemetryAccumulator();
        collector.requireStructuredOutput();
        ModelCallInput input = structuredDeliveryInput();
        List<GenerateOptions> seen = new ArrayList<>();

        Function<ModelCallInput, Flux<AgentEvent>> next = received -> {
            seen.add(received.options());
            return Flux.error(new HttpTransportException(
                    "HTTP request failed. Status: 400", 400,
                    "{\"error\":{\"message\":\"Messages with role 'tool' must be a response\"}}"));
        };

        assertThrows(HttpTransportException.class, () ->
                new CodingSpecialistTelemetryMiddleware()
                        .onModelCall(mock(Agent.class), context(collector), input, next)
                        .blockLast());

        assertEquals(1, seen.size());
    }

    @Test
    void structuredOutputNotRequiredNeverForcesTheChoice() {
        CodingSpecialistTelemetryAccumulator collector = new CodingSpecialistTelemetryAccumulator();
        ModelCallInput input = structuredDeliveryInput();
        List<GenerateOptions> seen = new ArrayList<>();

        Function<ModelCallInput, Flux<AgentEvent>> next = received -> {
            seen.add(received.options());
            return Flux.empty();
        };

        new CodingSpecialistTelemetryMiddleware()
                .onModelCall(mock(Agent.class), context(collector), input, next)
                .blockLast();

        assertEquals(1, seen.size());
        assertNull(seen.get(0).getToolChoice());
    }

    private static ModelCallInput structuredDeliveryInput() {
        return new ModelCallInput(
                List.of(),
                List.of(ToolSchema.builder()
                        .name("generate_response")
                        .description("Deliver the structured result")
                        .build()),
                GenerateOptions.builder().build(),
                null);
    }

    private static RuntimeContext context(CodingSpecialistTelemetryAccumulator collector) {
        RuntimeContext context = mock(RuntimeContext.class);
        when(context.get(CodingSpecialistTelemetryAccumulator.class)).thenReturn(collector);
        return context;
    }
}
