package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.workspace.AgentProfileId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** M2-I07 evidence for real model retries, fallback, usage, correlation and safe failures. */
class AgentCallObservabilityM2I07Test {

    private static final String PROVIDER_SECRET = "provider-secret-response-body";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-09T08:00:00Z"), ZoneOffset.UTC);
    private static final AgentCallTraceContext TRACE = new AgentCallTraceContext(
            java.util.Optional.of("0123456789abcdef0123456789abcdef"),
            java.util.Optional.of("0123456789abcdef"));

    @Test
    void recordsSuccessfulUsageAndAllConversationSessionTraceLinks() {
        Fixture fixture = Fixture.create();
        List<AgentCallObservationRecord> records = new ArrayList<>();
        Model model = new ObservableAgentScopeModel(
                new SequenceModel("primary-model", Flux.just(response(12, 5, 3))),
                AgentModelRole.PRIMARY);

        invoke(fixture, records, model, options(1)).blockLast();

        AgentCallObservationRecord completed = records.stream()
                .filter(record -> record.event() == AgentCallObservationEvent.COMPLETED)
                .findFirst()
                .orElseThrow();
        assertEquals(fixture.context.conversationId(), completed.conversationId());
        assertEquals(fixture.context.runtimeSessionId(), completed.runtimeSessionId());
        assertEquals(fixture.context.invocationId(), completed.invocationId());
        assertEquals(fixture.context.correlationId(), completed.correlationId());
        assertEquals(TRACE.traceId(), completed.traceId());
        assertEquals(AgentModelRole.PRIMARY, completed.modelRole());
        assertEquals(12, completed.inputTokens());
        assertEquals(5, completed.outputTokens());
        assertEquals(3, completed.cachedTokens());
        assertEquals(17, completed.totalTokens());
    }

    @Test
    void recordsActualRetryThenCompletesWithoutProviderContent() {
        Fixture fixture = Fixture.create();
        List<AgentCallObservationRecord> records = new ArrayList<>();
        AtomicInteger subscriptions = new AtomicInteger();
        Model flaky = model("primary-model", () -> subscriptions.getAndIncrement() == 0
                ? Flux.error(providerFailure(429))
                : Flux.just(response(8, 2, 0)));
        Model observed = new ObservableAgentScopeModel(flaky, AgentModelRole.PRIMARY);

        invoke(fixture, records, observed, options(2)).blockLast();

        assertEquals(2, subscriptions.get());
        AgentCallObservationRecord retry = records.stream()
                .filter(record -> record.event() == AgentCallObservationEvent.RETRYING)
                .findFirst()
                .orElseThrow();
        assertEquals(2, retry.attempt());
        assertEquals(1, retry.retryCount());
        assertFalse(records.toString().contains(PROVIDER_SECRET));
    }

    @Test
    void recordsFallbackAfterPrimaryBudgetAndUsesSanitizedFailureAcrossTheSwitch() {
        Fixture fixture = Fixture.create();
        List<AgentCallObservationRecord> records = new ArrayList<>();
        AtomicReference<Model> active = new AtomicReference<>();
        Model primary = new ObservableAgentScopeModel(
                model("primary-model", () -> Flux.error(providerFailure(503))),
                AgentModelRole.PRIMARY);
        Model fallback = new ObservableAgentScopeModel(
                new SequenceModel("fallback-model", Flux.just(response(6, 4, 0))),
                AgentModelRole.FALLBACK);
        active.set(primary);
        Model switching = model("primary-model", () -> primary.stream(List.of(), List.of(), options(1))
                .onErrorResume(failure -> {
                    assertInstanceOf(SafeModelExecutionException.class, failure);
                    assertFalse(failure.toString().contains(PROVIDER_SECRET));
                    active.set(fallback);
                    return fallback.stream(List.of(), List.of(), options(1));
                }), () -> active.get().getModelName());

        invoke(fixture, records, switching, options(1)).blockLast();

        assertTrue(records.stream().anyMatch(record ->
                record.event() == AgentCallObservationEvent.FALLBACK_SELECTED
                        && record.modelName().equals("fallback-model")));
        AgentCallObservationRecord completed = records.get(records.size() - 1);
        assertEquals(AgentCallObservationEvent.COMPLETED, completed.event());
        assertTrue(completed.fallbackUsed());
        assertEquals(AgentModelRole.FALLBACK, completed.modelRole());
        assertFalse(records.toString().contains(PROVIDER_SECRET));
    }

    @Test
    void recordsInterruptedStreamFailureCancellationAndBestEffortSinkFailure() {
        Fixture fixture = Fixture.create();
        List<AgentCallObservationRecord> records = new ArrayList<>();
        Model interrupted = new ObservableAgentScopeModel(
                new SequenceModel(
                        "primary-model",
                        Flux.concat(
                                Flux.just(response(1, 1, 0)),
                                Flux.error(providerFailure(500)))),
                AgentModelRole.PRIMARY);

        SafeModelExecutionException failure = assertThrows(
                SafeModelExecutionException.class,
                () -> invoke(fixture, records, interrupted, options(1)).blockLast());
        assertEquals("MODEL_PROVIDER_UNAVAILABLE", failure.safeCode());
        assertEquals(
                "MODEL_TIMEOUT",
                AgentCallFailureClassifier.classify(new ModelException(
                        "Model request timeout after PT1S",
                        "provider-model",
                        "provider")));
        AgentCallObservationRecord failed = records.get(records.size() - 1);
        assertEquals(AgentCallObservationEvent.FAILED, failed.event());
        assertEquals("MODEL_PROVIDER_UNAVAILABLE", failed.safeErrorCode().orElseThrow());
        assertFalse(failed.toString().contains(PROVIDER_SECRET));

        Model never = new ObservableAgentScopeModel(
                new SequenceModel("primary-model", Flux.never()), AgentModelRole.PRIMARY);
        Disposable subscription = invoke(fixture, records, never, options(1)).subscribe();
        subscription.dispose();
        assertTrue(records.stream().anyMatch(record ->
                record.event() == AgentCallObservationEvent.CANCELED));

        PlatformAuditMiddleware bestEffort = new PlatformAuditMiddleware(
                ignored -> {},
                ignored -> { throw new IllegalStateException("telemetry unavailable"); },
                () -> TRACE,
                CLOCK);
        bestEffort.onModelCall(
                        null,
                        fixture.runtimeContext,
                        new ModelCallInput(List.of(), List.of(), options(1), never),
                        ignored -> Flux.empty())
                .blockLast();

        PlatformAuditMiddleware unavailableTrace = new PlatformAuditMiddleware(
                ignored -> {},
                records::add,
                () -> { throw new IllegalStateException("tracing unavailable"); },
                CLOCK);
        unavailableTrace.onModelCall(
                        null,
                        fixture.runtimeContext,
                        new ModelCallInput(List.of(), List.of(), options(1), never),
                        ignored -> Flux.empty())
                .blockLast();
        assertTrue(records.get(records.size() - 1).traceId().isEmpty());
    }

    @Test
    void bridgesRetryAndFallbackIntoTheTaskRuntimeObservationScope() {
        List<TaskExecutionEventPayload.ModelTransition> transitions = new ArrayList<>();
        AtomicInteger subscriptions = new AtomicInteger();
        Model primary = new ObservableAgentScopeModel(
                model("primary-model", () -> subscriptions.getAndIncrement() == 0
                        ? Flux.error(providerFailure(429))
                        : Flux.just(response(1, 1, 0))),
                AgentModelRole.PRIMARY);

        primary.stream(List.of(), List.of(), options(2))
                .contextWrite(TaskAgentCallObservationScope.install(transitions::add))
                .blockLast();
        Model fallback = new ObservableAgentScopeModel(
                new SequenceModel("fallback-model", Flux.just(response(1, 1, 0))),
                AgentModelRole.FALLBACK);
        fallback.stream(List.of(), List.of(), options(1))
                .contextWrite(TaskAgentCallObservationScope.install(transitions::add))
                .blockLast();

        assertTrue(transitions.stream().anyMatch(value ->
                value.type() == TaskExecutionEventPayload.ModelTransitionType.RETRYING
                        && value.modelRole() == TaskExecutionEventPayload.ModelRole.PRIMARY
                        && value.attempt() == 2));
        assertTrue(transitions.stream().anyMatch(value ->
                value.type() == TaskExecutionEventPayload.ModelTransitionType.FALLBACK_SELECTED
                        && value.modelRole() == TaskExecutionEventPayload.ModelRole.FALLBACK));
    }

    @Test
    void recordsFirstChunkOnceAcrossMultiChunkStreamsAndRetries() {
        Fixture fixture = Fixture.create();
        List<AgentCallObservationRecord> records = new ArrayList<>();
        AtomicInteger subscriptions = new AtomicInteger();
        Model flaky = model("primary-model", () -> subscriptions.getAndIncrement() == 0
                ? Flux.error(providerFailure(429))
                // The successful attempt streams two chunks; only the first may record.
                : Flux.just(response(4, 1, 0), response(4, 1, 0)));
        Model observed = new ObservableAgentScopeModel(flaky, AgentModelRole.PRIMARY);

        invoke(fixture, records, observed, options(2)).blockLast();

        assertEquals(2, subscriptions.get());
        List<AgentCallObservationRecord> firstChunks = records.stream()
                .filter(record -> record.event() == AgentCallObservationEvent.FIRST_CHUNK)
                .toList();
        assertEquals(1, firstChunks.size());
        assertEquals("primary-model", firstChunks.get(0).modelName());
        assertEquals(AgentModelRole.PRIMARY, firstChunks.get(0).modelRole());
        // The first attempt dies before any chunk; the first byte belongs to attempt 2.
        assertEquals(2, firstChunks.get(0).attempt());
        assertEquals(2, firstChunks.get(0).maxAttempts());
    }

    @Test
    void recordsApplicationOverheadOnceAndFirstChunkLatencyFromScopeStart() {
        Fixture fixture = Fixture.create();
        List<AgentCallObservationRecord> records = new ArrayList<>();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T08:00:00Z"));
        PlatformAuditMiddleware middleware = new PlatformAuditMiddleware(
                ignored -> {}, records::add, () -> TRACE, clock);
        Model first = new ObservableAgentScopeModel(
                new SequenceModel("primary-model", Flux.just(response(3, 1, 0))),
                AgentModelRole.PRIMARY);
        Model second = new ObservableAgentScopeModel(
                new SequenceModel("primary-model", Flux.just(response(3, 1, 0))),
                AgentModelRole.PRIMARY);

        // Acceptance happens at t0; the model call assembles 5s later (application
        // overhead), and its stream emits the first chunk 2s after that.
        middleware
                .onAgent(
                        null,
                        fixture.runtimeContext,
                        new AgentInput(List.of()),
                        ignored -> {
                            clock.advance(Duration.ofSeconds(5));
                            return Flux.concat(
                                    middleware.onModelCall(
                                            null,
                                            fixture.runtimeContext,
                                            new ModelCallInput(List.of(), List.of(), options(1), first),
                                            current -> streamEvents(clock, current)),
                                    // A second model call inside the same invocation must
                                    // not re-record the overhead (one-shot guard).
                                    middleware.onModelCall(
                                            null,
                                            fixture.runtimeContext,
                                            new ModelCallInput(List.of(), List.of(), options(1), second),
                                            current -> streamEvents(clock, current)));
                        })
                .blockLast();

        List<AgentCallObservationRecord> overheads = records.stream()
                .filter(record -> record.event() == AgentCallObservationEvent.APPLICATION_OVERHEAD)
                .toList();
        assertEquals(1, overheads.size());
        assertEquals(5000, overheads.get(0).latencyMillis());
        assertEquals(AgentModelRole.LOGICAL, overheads.get(0).modelRole());

        // Each model call still owns one FIRST_CHUNK whose latency anchors at that call's
        // scope start (the model-call assembly), not at invocation acceptance.
        List<AgentCallObservationRecord> firstChunks = records.stream()
                .filter(record -> record.event() == AgentCallObservationEvent.FIRST_CHUNK)
                .toList();
        assertEquals(2, firstChunks.size());
        assertEquals(2000, firstChunks.get(0).latencyMillis());

        // A bare model call without the invocation middleware stays silent on overhead.
        invoke(fixture, records, new ObservableAgentScopeModel(
                new SequenceModel("primary-model", Flux.just(response(1, 1, 0))),
                AgentModelRole.PRIMARY), options(1)).blockLast();
        assertEquals(1, records.stream()
                .filter(record -> record.event() == AgentCallObservationEvent.APPLICATION_OVERHEAD)
                .count());
    }

    private static Flux<AgentEvent> streamEvents(MutableClock clock, ModelCallInput current) {
        return Flux.concat(
                Flux.just(new ModelCallStartEvent("reply")),
                Flux.defer(() -> {
                    clock.advance(Duration.ofSeconds(2));
                    return current.model()
                            .stream(current.messages(), current.tools(), current.options())
                            .map(response -> (AgentEvent) new ModelCallEndEvent(
                                    "reply", response.getUsage()));
                }));
    }

    private static Flux<AgentEvent> invoke(
            Fixture fixture,
            List<AgentCallObservationRecord> records,
            Model model,
            GenerateOptions options) {
        PlatformAuditMiddleware middleware = new PlatformAuditMiddleware(
                ignored -> {}, records::add, () -> TRACE, CLOCK);
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), options, model);
        return middleware.onModelCall(
                null,
                fixture.runtimeContext,
                input,
                current -> Flux.concat(
                        Flux.just(new ModelCallStartEvent("reply")),
                        current.model()
                                .stream(current.messages(), current.tools(), current.options())
                                .map(response -> (AgentEvent) new ModelCallEndEvent(
                                        "reply", response.getUsage()))));
    }

    private static GenerateOptions options(int maxAttempts) {
        return GenerateOptions.builder()
                .executionConfig(ExecutionConfig.builder()
                        .maxAttempts(maxAttempts)
                        .initialBackoff(Duration.ofMillis(1))
                        .maxBackoff(Duration.ofMillis(2))
                        .retryOn(ignored -> true)
                        .build())
                .build();
    }

    private static ChatResponse response(int inputTokens, int outputTokens, int cachedTokens) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("safe response").build()))
                .usage(new ChatUsage(inputTokens, outputTokens, cachedTokens, 0.01))
                .finishReason("stop")
                .build();
    }

    private static HttpTransportException providerFailure(int status) {
        return new HttpTransportException(PROVIDER_SECRET, status, PROVIDER_SECRET);
    }

    private static Model model(String name, java.util.function.Supplier<Flux<ChatResponse>> calls) {
        return model(name, calls, () -> name);
    }

    private static Model model(
            String name,
            java.util.function.Supplier<Flux<ChatResponse>> calls,
            java.util.function.Supplier<String> currentName) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return calls.get();
            }

            @Override
            public String getModelName() {
                return currentName.get();
            }
        };
    }

    /** A controllable clock so latency assertions observe real elapsed milliseconds. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private record SequenceModel(String name, Flux<ChatResponse> responses) implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return responses;
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    private record Fixture(
            PlatformExecutionContext context,
            RuntimeContext runtimeContext) {

        private static Fixture create() {
            AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(
                    AgentProfileId.generate(), 1);
            PlatformExecutionContext context = AgentScopeRuntimeTestFixture.platformContext(
                    session, RuntimeInvocationId.generate(), UUID.randomUUID());
            RuntimeContext runtime = RuntimeContext.builder()
                    .userId(session.agentScopeKey().userId())
                    .sessionId(session.agentScopeKey().sessionId())
                    .put(PlatformExecutionContext.class, context)
                    .build();
            return new Fixture(context, runtime);
        }
    }
}
