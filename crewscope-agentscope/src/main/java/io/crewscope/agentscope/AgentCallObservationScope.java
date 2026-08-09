package io.crewscope.agentscope;

import io.crewscope.application.execution.PlatformExecutionContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Per-model-call reactive observation state shared by Middleware and model decorators. */
final class AgentCallObservationScope {

    private static final Class<AgentCallObservationScope> KEY = AgentCallObservationScope.class;

    private final PlatformExecutionContext executionContext;
    private final AgentCallTraceContext traceContext;
    private final AgentCallObservationSink sink;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicInteger retryCount = new AtomicInteger();
    private final AtomicBoolean fallbackUsed = new AtomicBoolean();
    private final AtomicReference<AgentModelRole> activeModelRole =
            new AtomicReference<>(AgentModelRole.LOGICAL);

    AgentCallObservationScope(
            PlatformExecutionContext executionContext,
            AgentCallTraceContext traceContext,
            AgentCallObservationSink sink,
            Clock clock) {
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
    }

    static Optional<AgentCallObservationScope> find(ContextView contextView) {
        return contextView.hasKey(KEY)
                ? Optional.of(contextView.get(KEY))
                : Optional.empty();
    }

    Context install(Context context) {
        return context.put(KEY, this);
    }

    void started(String modelName, int maxAttempts) {
        emit(AgentCallObservationEvent.STARTED, modelName, AgentModelRole.LOGICAL,
                0, maxAttempts, AgentCallTokenUsage.none(), Optional.empty());
    }

    void retrying(String modelName, AgentModelRole role, int nextAttempt, int maxAttempts) {
        retryCount.incrementAndGet();
        emit(AgentCallObservationEvent.RETRYING, modelName, role, nextAttempt, maxAttempts,
                AgentCallTokenUsage.none(), Optional.empty());
    }

    void modelSelected(AgentModelRole role) {
        activeModelRole.set(Objects.requireNonNull(role, "role"));
    }

    void fallbackSelected(String modelName, int maxAttempts) {
        modelSelected(AgentModelRole.FALLBACK);
        if (fallbackUsed.compareAndSet(false, true)) {
            emit(AgentCallObservationEvent.FALLBACK_SELECTED, modelName, AgentModelRole.FALLBACK,
                    1, maxAttempts, AgentCallTokenUsage.none(), Optional.empty());
        }
    }

    void terminal(
            AgentCallObservationEvent event,
            String modelName,
            int maxAttempts,
            AgentCallTokenUsage usage,
            Optional<String> safeErrorCode) {
        emit(event, modelName, activeModelRole.get(), 0, maxAttempts, usage, safeErrorCode);
    }

    private void emit(
            AgentCallObservationEvent event,
            String modelName,
            AgentModelRole role,
            int attempt,
            int maxAttempts,
            AgentCallTokenUsage usage,
            Optional<String> safeErrorCode) {
        Instant now = clock.instant();
        long latencyMillis = Math.max(0L, Duration.between(startedAt, now).toMillis());
        try {
            sink.record(AgentCallObservationRecord.from(
                    now,
                    event,
                    executionContext,
                    traceContext,
                    modelName,
                    role,
                    attempt,
                    maxAttempts,
                    retryCount.get(),
                    fallbackUsed.get(),
                    usage,
                    latencyMillis,
                    safeErrorCode));
        } catch (RuntimeException ignored) {
            // Malformed provider telemetry and sink outages cannot alter a model call.
        }
    }
}
