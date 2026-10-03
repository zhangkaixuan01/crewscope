package io.crewscope.agentscope.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** M10-A02b usage middleware: one fact per real attempt, sanitized counters, no-op bypass. */
class KnowledgeDistillerUsageMiddlewareTest {

    private final KnowledgeDistillerUsageMiddleware middleware =
            new KnowledgeDistillerUsageMiddleware();

    @Test
    void recordsOneFactPerTerminatedAttemptWithTheRuntimeModelName() {
        KnowledgeDistillerUsageAccumulator collector = new KnowledgeDistillerUsageAccumulator();
        RuntimeContext context = RuntimeContext.builder()
                .put(KnowledgeDistillerUsageAccumulator.class, collector)
                .build();

        apply(context, Flux.just(event(usage(1200, 80, 900)))).blockLast();
        // A failed attempt still received its end event before the stream broke: the usage
        // was reported, so the fact carries real counters rather than unreported.
        apply(context, Flux.concat(
                        Flux.just(event(usage(40, 10, 0))),
                        Flux.<AgentEvent>error(new IllegalStateException("boom"))))
                .materialize().blockLast();

        assertEquals(2, collector.attempts().size());
        assertEquals(1, collector.attempts().get(0).attempt());
        assertEquals(1200, collector.attempts().get(0).usage().inputTokens());
        assertEquals(80, collector.attempts().get(0).usage().outputTokens());
        assertEquals(900, collector.attempts().get(0).usage().cachedTokens());
        assertEquals(1280, collector.attempts().get(0).usage().totalTokens());
        assertEquals(2, collector.attempts().get(1).attempt());
        assertEquals(40, collector.attempts().get(1).usage().inputTokens());
        assertTrue(collector.observedModelName().isPresent());
        assertEquals("a02b-usage-model", collector.observedModelName().orElseThrow());
    }

    @Test
    void degradesInconsistentProviderCountersToUnreportedInsteadOfFailingTheCall() {
        KnowledgeDistillerUsageAccumulator collector = new KnowledgeDistillerUsageAccumulator();
        RuntimeContext context = RuntimeContext.builder()
                .put(KnowledgeDistillerUsageAccumulator.class, collector)
                .build();

        // cached > input: the Provider lied; the fact survives as "not reported".
        apply(context, Flux.just(event(usage(50, 10, 60)))).blockLast();
        apply(context, Flux.just(event(null))).blockLast();

        assertEquals(0, collector.attempts().get(0).usage().inputTokens());
        assertEquals(0, collector.attempts().get(0).usage().totalTokens());
        assertEquals(0, collector.attempts().get(1).usage().inputTokens());
    }

    @Test
    void passesThroughUntouchedWhenNoAccumulatorIsBoundToTheContext() {
        RuntimeContext empty = RuntimeContext.builder().build();

        List<AgentEvent> events = apply(empty, Flux.just(event(usage(1, 1, 0))))
                .collectList().block();

        assertEquals(1, events.size());
    }

    private Flux<AgentEvent> apply(RuntimeContext context, Flux<AgentEvent> events) {
        return middleware.onModelCall(
                mock(Agent.class),
                context,
                new ModelCallInput(
                        List.of(mock(Msg.class)),
                        List.<ToolSchema>of(),
                        GenerateOptions.builder().build(),
                        loopbackModel()),
                input -> events);
    }

    private static Model loopbackModel() {
        return new Model() {
            @Override
            public Flux<io.agentscope.core.model.ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                throw new UnsupportedOperationException("not called in this test");
            }

            @Override
            public String getModelName() {
                return "a02b-usage-model";
            }
        };
    }

    private static ChatUsage usage(int input, int output, int cached) {
        return new ChatUsage(input, output, cached, 0.001);
    }

    private static ModelCallEndEvent event(ChatUsage usage) {
        return new ModelCallEndEvent("a02b-attempt", usage);
    }
}
