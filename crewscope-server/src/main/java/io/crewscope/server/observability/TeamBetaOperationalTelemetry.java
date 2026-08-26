package io.crewscope.server.observability;

import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.operations.OperationsHealthComponent;
import io.crewscope.application.operations.OperationsHealthLevel;
import io.crewscope.application.operations.OperationsMemberHealthSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/** Micrometer/OTel implementation of the closed M6 operational telemetry contract. */
@Component
public final class TeamBetaOperationalTelemetry implements OperationalTelemetry {

    public static final String OUTBOX_DURATION = "crewscope.m6.outbox.duration";
    public static final String PROJECTION_DURATION = "crewscope.m6.projection.duration";
    public static final String SSE_DURATION = "crewscope.m6.sse.duration";
    public static final String INBOX_DURATION = "crewscope.m6.inbox.duration";
    public static final String NOTIFICATION_DURATION = "crewscope.m6.notification.duration";
    public static final String PROVIDER_DURATION = "crewscope.m6.provider.duration";
    public static final String PROVIDER_ERRORS = "crewscope.m6.provider.errors";
    public static final String TEAM_OBSERVER_DURATION = "crewscope.m6.team.observer.duration";
    public static final String OPERATIONS_HEALTH = "crewscope.m6.operations.health";
    public static final String TELEMETRY_DROPPED = "crewscope.m6.telemetry.dropped";

    static final String BAGGAGE_CORRELATION_ID = "crewscope.correlation_id";
    static final String BAGGAGE_OPERATION = "crewscope.operation";
    static final String BAGGAGE_WORKER_ROLE = "crewscope.worker_role";

    private static final Logger LOGGER = LoggerFactory.getLogger(TeamBetaOperationalTelemetry.class);
    private static final Map<String, Boolean> BAGGAGE_WHITELIST = Map.of(
            BAGGAGE_CORRELATION_ID, true,
            BAGGAGE_OPERATION, true,
            BAGGAGE_WORKER_ROLE, true);

    private final MeterRegistry registry;
    private final Tracer tracer;
    private final TelemetryDegradationState degradation;
    private final Map<HealthKey, AtomicLong> healthGauges;

    public TeamBetaOperationalTelemetry(
            MeterRegistry registry,
            Tracer tracer,
            TelemetryDegradationState degradation) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.degradation = Objects.requireNonNull(degradation, "degradation");
        this.healthGauges = registerHealthGauges(registry);
        registerDroppedCounters(registry, degradation);
    }

    @Override
    public Observation start(Request request) {
        Request required = Objects.requireNonNull(request, "request");
        long startedAt = System.nanoTime();
        Span span = startSpan(required);
        putBaggage(span, BAGGAGE_OPERATION, label(required.operation()));
        putBaggage(span, BAGGAGE_WORKER_ROLE, label(required.workerRole()));
        correlationId().ifPresent(value -> putBaggage(span, BAGGAGE_CORRELATION_ID, value));
        return new ActiveObservation(required, startedAt, span);
    }

    /** Updates identifier-free health gauges from the consistent M6-E07 summary. */
    public void recordHealth(OperationsMemberHealthSummary summary) {
        OperationsMemberHealthSummary required = Objects.requireNonNull(summary, "summary");
        healthGauges.values().forEach(value -> value.set(0));
        required.components().forEach(component -> healthGauges
                .get(new HealthKey(component.component(), component.health()))
                .set(1));
    }

    static boolean isAllowedBaggageKey(String key) {
        return BAGGAGE_WHITELIST.containsKey(key);
    }

    private Span startSpan(Request request) {
        try {
            Span span = tracer.spanBuilder()
                    .name("crewscope." + label(request.type()) + '.' + label(request.operation()))
                    .start();
            span.tag("crewscope.type", label(request.type()));
            span.tag("crewscope.operation", label(request.operation()));
            span.tag("crewscope.worker_role", label(request.workerRole()));
            if (request.providerKey() != ProviderKey.NONE) {
                span.tag("crewscope.provider_key", label(request.providerKey()));
            }
            if (request.projectionName() != ProjectionName.NONE) {
                span.tag("crewscope.projection_name", label(request.projectionName()));
            }
            if (request.streamType() != StreamType.NONE) {
                span.tag("crewscope.stream_type", label(request.streamType()));
            }
            correlationId().ifPresent(value -> span.tag("crewscope.correlation_id", value));
            return span;
        } catch (RuntimeException ignored) {
            degradation.record(TelemetryDegradationState.FailureType.TRACE);
            return Span.NOOP;
        }
    }

    private void putBaggage(Span span, String key, String value) {
        if (!isAllowedBaggageKey(key)) {
            degradation.record(TelemetryDegradationState.FailureType.BAGGAGE);
            return;
        }
        try {
            Baggage baggage = tracer.createBaggage(key);
            baggage.set(span.context(), value);
        } catch (RuntimeException ignored) {
            degradation.record(TelemetryDegradationState.FailureType.BAGGAGE);
        }
    }

    private void recordMetric(Request request, Outcome outcome, ErrorCode error, long nanos) {
        try {
            Timer.Builder timer = Timer.builder(metricName(request.type()))
                    .description("CrewScope M6 bounded operational latency");
            switch (request.type()) {
                case OUTBOX, INBOX, AGENT -> timer.tag("outcome", label(outcome));
                case PROJECTION -> timer.tags(
                        "projectionName", label(request.projectionName()),
                        "outcome", label(outcome));
                case SSE -> timer.tags(
                        "streamType", label(request.streamType()),
                        "outcome", label(outcome));
                case NOTIFICATION, PROVIDER -> timer.tags(
                        "providerKey", label(request.providerKey()),
                        "operation", label(request.operation()),
                        "outcome", label(outcome));
                case OPERATIONS -> throw new IllegalArgumentException(
                        "operations health uses bounded gauges");
            }
            timer.register(registry).record(nanos, TimeUnit.NANOSECONDS);
            if (request.type() == Type.PROVIDER && error != ErrorCode.NONE) {
                registry.counter(
                                PROVIDER_ERRORS,
                                "providerKey", label(request.providerKey()),
                                "errorCode", label(error))
                        .increment();
            }
        } catch (RuntimeException ignored) {
            degradation.record(TelemetryDegradationState.FailureType.METRIC);
        }
    }

    private void log(Request request, Outcome outcome, ErrorCode error, long nanos) {
        try {
            LoggingEventBuilder event = outcome == Outcome.FAILURE
                    ? LOGGER.atWarn()
                    : outcome == Outcome.SUCCESS ? LOGGER.atDebug() : LOGGER.atInfo();
            event.addKeyValue("event", "m6_operational_observation")
                    .addKeyValue("type", safe("type", label(request.type())))
                    .addKeyValue("operation", safe("operation", label(request.operation())))
                    .addKeyValue("workerRole", safe("workerRole", label(request.workerRole())))
                    .addKeyValue("outcome", safe("outcome", label(outcome)))
                    .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(nanos));
            if (request.providerKey() != ProviderKey.NONE) {
                event.addKeyValue(
                        "providerKey", safe("providerKey", label(request.providerKey())));
            }
            if (request.projectionName() != ProjectionName.NONE) {
                event.addKeyValue(
                        "projectionName",
                        safe("projectionName", label(request.projectionName())));
            }
            if (request.streamType() != StreamType.NONE) {
                event.addKeyValue(
                        "streamType", safe("streamType", label(request.streamType())));
            }
            if (error != ErrorCode.NONE) {
                event.addKeyValue("errorCode", safe("errorCode", label(error)));
            }
            event.log("M6 operational observation completed");
        } catch (RuntimeException ignored) {
            degradation.record(TelemetryDegradationState.FailureType.LOG);
        }
    }

    private static Map<HealthKey, AtomicLong> registerHealthGauges(MeterRegistry registry) {
        Map<HealthKey, AtomicLong> result = new java.util.LinkedHashMap<>();
        for (OperationsHealthComponent component : OperationsHealthComponent.values()) {
            for (OperationsHealthLevel level : OperationsHealthLevel.values()) {
                AtomicLong value = new AtomicLong();
                result.put(new HealthKey(component, level), value);
                Gauge.builder(OPERATIONS_HEALTH, value, AtomicLong::doubleValue)
                        .tag("type", label(component))
                        .tag("status", label(level))
                        .register(registry);
            }
        }
        return Map.copyOf(result);
    }

    private static void registerDroppedCounters(
            MeterRegistry registry, TelemetryDegradationState state) {
        for (TelemetryDegradationState.FailureType type
                : TelemetryDegradationState.FailureType.values()) {
            FunctionCounter.builder(TELEMETRY_DROPPED, state, value -> value.count(type))
                    .tag("result", label(type))
                    .register(registry);
        }
    }

    private static String metricName(Type type) {
        return switch (type) {
            case OUTBOX -> OUTBOX_DURATION;
            case PROJECTION -> PROJECTION_DURATION;
            case SSE -> SSE_DURATION;
            case INBOX -> INBOX_DURATION;
            case NOTIFICATION -> NOTIFICATION_DURATION;
            case PROVIDER -> PROVIDER_DURATION;
            case AGENT -> TEAM_OBSERVER_DURATION;
            case OPERATIONS -> OPERATIONS_HEALTH;
        };
    }

    private static java.util.Optional<String> correlationId() {
        String value = MDC.get(CorrelationIdThreadLocalAccessor.MDC_KEY);
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(value).toString());
        } catch (IllegalArgumentException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static String safe(String field, String value) {
        return StructuredLogSanitizer.sanitize(field, value);
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private record HealthKey(
            OperationsHealthComponent component, OperationsHealthLevel level) {}

    private final class ActiveObservation implements Observation {

        private final Request request;
        private final long startedAt;
        private final Span span;
        private final AtomicBoolean completed = new AtomicBoolean();

        private ActiveObservation(Request request, long startedAt, Span span) {
            this.request = request;
            this.startedAt = startedAt;
            this.span = span;
        }

        @Override
        public void complete(Outcome outcome, ErrorCode errorCode) {
            Outcome safeOutcome = Objects.requireNonNull(outcome, "outcome");
            ErrorCode safeError = Objects.requireNonNull(errorCode, "errorCode");
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            long elapsed = Math.max(0, System.nanoTime() - startedAt);
            recordMetric(request, safeOutcome, safeError, elapsed);
            log(request, safeOutcome, safeError, elapsed);
            try {
                span.tag("crewscope.outcome", label(safeOutcome));
                if (safeError != ErrorCode.NONE) {
                    span.tag("crewscope.error_code", label(safeError));
                }
                span.end();
            } catch (RuntimeException ignored) {
                degradation.record(TelemetryDegradationState.FailureType.TRACE);
            }
        }
    }
}
