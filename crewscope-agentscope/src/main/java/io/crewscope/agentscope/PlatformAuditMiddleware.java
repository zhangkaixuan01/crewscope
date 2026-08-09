package io.crewscope.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.crewscope.application.execution.PlatformExecutionContext;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/** Emits content-free invocation, model and tool audit records around AgentScope execution. */
public final class PlatformAuditMiddleware implements MiddlewareBase {

    private final AgentExecutionAuditSink sink;
    private final AgentCallObservationSink observationSink;
    private final AgentCallTraceContextProvider traceContextProvider;
    private final Clock clock;

    public PlatformAuditMiddleware(AgentExecutionAuditSink sink, Clock clock) {
        this(sink, AgentCallObservationSink.noop(), AgentCallTraceContextProvider.none(), clock);
    }

    /** Creates the production interceptor with audit, telemetry and current Trace correlation. */
    public PlatformAuditMiddleware(
            AgentExecutionAuditSink sink,
            AgentCallObservationSink observationSink,
            AgentCallTraceContextProvider traceContextProvider,
            Clock clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.observationSink = Objects.requireNonNull(observationSink, "observationSink");
        this.traceContextProvider = Objects.requireNonNull(
                traceContextProvider, "traceContextProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext runtimeContext,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        int messageCount = input.msgs() == null ? 0 : input.msgs().size();
        return audited(
                runtimeContext,
                AgentExecutionAuditPhase.INVOCATION,
                Set.of(),
                messageCount,
                () -> next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext runtimeContext,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            PlatformExecutionContext context =
                    PlatformRuntimeContextMiddleware.requireTrustedContext(runtimeContext);
            int toolCount = input.tools() == null ? 0 : input.tools().size();
            int maxAttempts = maxAttempts(input);
            String initialModelName = modelName(input);
            AgentCallObservationScope observation = new AgentCallObservationScope(
                    context,
                    currentTraceContext(),
                    observationSink,
                    clock);
            write(context, AgentExecutionAuditPhase.MODEL_CALL,
                    AgentExecutionAuditOutcome.STARTED, Set.of(), toolCount, Optional.empty());
            observation.started(initialModelName, maxAttempts);

            AtomicReference<AgentCallTokenUsage> usage =
                    new AtomicReference<>(AgentCallTokenUsage.none());
            AtomicBoolean terminal = new AtomicBoolean();
            return Flux.defer(() -> next.apply(input))
                    .doOnNext(event -> {
                        if (event instanceof ModelCallEndEvent completed) {
                            usage.set(AgentCallTokenUsage.from(completed.getUsage()));
                        }
                    })
                    .doOnComplete(() -> {
                        if (terminal.compareAndSet(false, true)) {
                            write(context, AgentExecutionAuditPhase.MODEL_CALL,
                                    AgentExecutionAuditOutcome.COMPLETED, Set.of(), toolCount,
                                    Optional.empty());
                            observation.terminal(
                                    AgentCallObservationEvent.COMPLETED,
                                    modelName(input),
                                    maxAttempts,
                                    usage.get(),
                                    Optional.empty());
                        }
                    })
                    .doOnError(failure -> {
                        if (terminal.compareAndSet(false, true)) {
                            String errorCode = AgentCallFailureClassifier.classify(failure);
                            write(context, AgentExecutionAuditPhase.MODEL_CALL,
                                    AgentExecutionAuditOutcome.FAILED, Set.of(), toolCount,
                                    Optional.of(errorCode));
                            observation.terminal(
                                    AgentCallObservationEvent.FAILED,
                                    modelName(input),
                                    maxAttempts,
                                    usage.get(),
                                    Optional.of(errorCode));
                        }
                    })
                    .doOnCancel(() -> {
                        if (terminal.compareAndSet(false, true)) {
                            write(context, AgentExecutionAuditPhase.MODEL_CALL,
                                    AgentExecutionAuditOutcome.CANCELED, Set.of(), toolCount,
                                    Optional.empty());
                            observation.terminal(
                                    AgentCallObservationEvent.CANCELED,
                                    modelName(input),
                                    maxAttempts,
                                    usage.get(),
                                    Optional.empty());
                        }
                    })
                    .contextWrite(observation::install);
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext runtimeContext,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input.toolCalls() == null ? List.of() : input.toolCalls();
        Set<String> names = calls.stream()
                .map(ToolUseBlock::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        return audited(
                runtimeContext,
                AgentExecutionAuditPhase.TOOL_EXECUTION,
                names,
                calls.size(),
                () -> next.apply(input));
    }

    private Flux<AgentEvent> audited(
            RuntimeContext runtimeContext,
            AgentExecutionAuditPhase phase,
            Set<String> toolNames,
            int itemCount,
            Supplier<Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            PlatformExecutionContext context =
                    PlatformRuntimeContextMiddleware.requireTrustedContext(runtimeContext);
            write(context, phase, AgentExecutionAuditOutcome.STARTED, toolNames, itemCount,
                    Optional.empty());
            return Flux.defer(next)
                    .doOnComplete(() -> write(
                            context,
                            phase,
                            AgentExecutionAuditOutcome.COMPLETED,
                            toolNames,
                            itemCount,
                            Optional.empty()))
                    .doOnError(failure -> write(
                            context,
                            phase,
                            AgentExecutionAuditOutcome.FAILED,
                            toolNames,
                            itemCount,
                            Optional.of(safeFailureType(failure))))
                    .doOnCancel(() -> write(
                            context,
                            phase,
                            AgentExecutionAuditOutcome.CANCELED,
                            toolNames,
                            itemCount,
                            Optional.empty()));
        });
    }

    private void write(
            PlatformExecutionContext context,
            AgentExecutionAuditPhase phase,
            AgentExecutionAuditOutcome outcome,
            Set<String> toolNames,
            int itemCount,
            Optional<String> safeFailureType) {
        sink.record(AgentExecutionAuditRecord.from(
                clock.instant(),
                context,
                phase,
                outcome,
                toolNames,
                itemCount,
                safeFailureType));
    }

    private static String safeFailureType(Throwable failure) {
        if (failure instanceof PlatformExecutionSecurityException security) {
            return security.safeCode();
        }
        return failure instanceof RuntimeException
                ? "EXECUTION_FAILED"
                : "EXECUTION_ERROR";
    }

    private static int maxAttempts(ModelCallInput input) {
        if (input.options() == null || input.options().getExecutionConfig() == null) {
            return ExecutionConfig.MODEL_DEFAULTS.getMaxAttempts();
        }
        Integer configured = input.options().getExecutionConfig().getMaxAttempts();
        return configured == null ? ExecutionConfig.MODEL_DEFAULTS.getMaxAttempts() : configured;
    }

    private static String modelName(ModelCallInput input) {
        return input.model() == null
                ? "unknown"
                : AgentCallObservationRecord.safeModelName(input.model().getModelName());
    }

    private AgentCallTraceContext currentTraceContext() {
        try {
            return Objects.requireNonNullElseGet(
                    traceContextProvider.current(), AgentCallTraceContext::empty);
        } catch (RuntimeException ignored) {
            // A tracing outage cannot become a model execution outage.
            return AgentCallTraceContext.empty();
        }
    }
}
