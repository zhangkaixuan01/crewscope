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
    private final AtomicInteger maxAttemptsStored = new AtomicInteger(1);
    private final AtomicBoolean fallbackUsed = new AtomicBoolean();
    private final AtomicBoolean firstChunkRecorded = new AtomicBoolean();
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
        maxAttemptsStored.set(Math.max(1, maxAttempts));
        emit(AgentCallObservationEvent.STARTED, modelName, AgentModelRole.LOGICAL,
                0, maxAttempts, AgentCallTokenUsage.none(), Optional.empty());
    }

    /**
     * First response event of this model call (M12-I03 model-side first token). The scope's
     * {@code startedAt} is the model-call initiation, so the record's latency is exactly
     * "initiation to first byte"; the one-shot guard keeps a single record when a retried
     * attempt re-emits or the stream emits many chunks.
     */
    void firstChunk(String modelName) {
        if (firstChunkRecorded.compareAndSet(false, true)) {
            emit(AgentCallObservationEvent.FIRST_CHUNK, modelName, activeModelRole.get(),
                    retryCount.get() + 1, maxAttemptsStored.get(),
                    AgentCallTokenUsage.none(), Optional.empty());
        }
    }

    /**
     * Invocation acceptance to this first model-call initiation (M12-I03 application
     * overhead); the middleware computed the latency against its own clock and passes it
     * explicitly because this scope's {@code startedAt} anchors a different interval.
     */
    void applicationOverhead(String modelName, long overheadMillis) {
        emitWithLatency(AgentCallObservationEvent.APPLICATION_OVERHEAD, modelName,
                AgentModelRole.LOGICAL, 0, 1, AgentCallTokenUsage.none(), Optional.empty(),
                Math.max(0L, overheadMillis));
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
        emitWithLatency(event, modelName, role, attempt, maxAttempts, usage, safeErrorCode,
                Math.max(0L, Duration.between(startedAt, now).toMillis()));
    }

    private void emitWithLatency(
            AgentCallObservationEvent event,
            String modelName,
            AgentModelRole role,
            int attempt,
            int maxAttempts,
            AgentCallTokenUsage usage,
            Optional<String> safeErrorCode,
            long latencyMillis) {
        Instant now = clock.instant();
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
