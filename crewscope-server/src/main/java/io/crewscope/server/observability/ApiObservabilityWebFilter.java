package io.crewscope.server.observability;

import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.server.api.ApiCorrelationIds;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

/** Establishes request correlation, safe completion logging and API metrics. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class ApiObservabilityWebFilter implements WebFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiObservabilityWebFilter.class);
    private static final String UNAVAILABLE_TRACE_ID = "unavailable";

    static {
        CorrelationIdThreadLocalAccessor.register();
    }

    private final ApiObservabilityMetrics metrics;
    private final Tracer tracer;
    private final OperationalTelemetry operationalTelemetry;

    public ApiObservabilityWebFilter(ApiObservabilityMetrics metrics, Tracer tracer) {
        this(metrics, tracer, OperationalTelemetry.noop());
    }

    @Autowired
    public ApiObservabilityWebFilter(
            ApiObservabilityMetrics metrics,
            Tracer tracer,
            ObjectProvider<OperationalTelemetry> operationalTelemetry) {
        this(
                metrics,
                tracer,
                operationalTelemetry.getIfAvailable(OperationalTelemetry::noop));
    }

    ApiObservabilityWebFilter(
            ApiObservabilityMetrics metrics,
            Tracer tracer,
            OperationalTelemetry operationalTelemetry) {
        this.metrics = metrics;
        this.tracer = tracer;
        this.operationalTelemetry = Objects.requireNonNull(
                operationalTelemetry, "operationalTelemetry");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        exchange.getResponse().getHeaders().set(ApiCorrelationIds.HEADER, correlationId.toString());
        metrics.recordCorrelation(ApiCorrelationIds.source(exchange));

        long startedAt = System.nanoTime();
        AtomicBoolean recorded = new AtomicBoolean();
        AtomicReference<TraceIds> traceIds = new AtomicReference<>(TraceIds.unavailable());
        Optional<OperationalTelemetry.Observation> boundary = boundary(exchange)
                .map(operationalTelemetry::start);

        traceIds.set(currentTraceIds());
        return chain.filter(exchange)
                .doOnEach(signal -> captureTraceIds(signal, traceIds))
                .doFinally(signalType -> {
                    if (recorded.compareAndSet(false, true)) {
                        ApiRequestObservation observation = metrics.record(
                                exchange, System.nanoTime() - startedAt, signalType);
                        logCompletion(correlationId, traceIds.get(), observation);
                        boundary.ifPresent(value -> completeBoundary(
                                value, observation, signalType));
                    }
                })
                .contextWrite(context -> context.put(
                        CorrelationIdThreadLocalAccessor.KEY, correlationId.toString()));
    }

    private static Optional<OperationalTelemetry.Request> boundary(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        boolean sse = exchange.getRequest().getHeaders().getAccept().stream()
                .anyMatch(MediaType.TEXT_EVENT_STREAM::isCompatibleWith);
        if (sse) {
            OperationalTelemetry.StreamType type;
            if (path.contains("/conversations/")) {
                type = OperationalTelemetry.StreamType.CONVERSATION;
            } else if (path.contains("/tasks/")) {
                type = OperationalTelemetry.StreamType.TASK;
            } else {
                type = OperationalTelemetry.StreamType.TEAM;
            }
            return Optional.of(OperationalTelemetry.Request.sse(type));
        }
        if (path.contains("/inbox") || path.contains("/inbox/")) {
            return Optional.of(OperationalTelemetry.Request.inbox());
        }
        return Optional.empty();
    }

    private static void completeBoundary(
            OperationalTelemetry.Observation observation,
            ApiRequestObservation request,
            reactor.core.publisher.SignalType signalType) {
        if (signalType == reactor.core.publisher.SignalType.CANCEL) {
            observation.cancel();
            return;
        }
        int status = Integer.parseInt(request.status());
        if (status >= 500) {
            observation.fail(OperationalTelemetry.ErrorCode.INTERNAL);
        } else if (status >= 400) {
            observation.complete(
                    OperationalTelemetry.Outcome.REJECTED,
                    OperationalTelemetry.ErrorCode.INVALID_INPUT);
        } else {
            observation.succeed();
        }
    }

    private void captureTraceIds(Signal<Void> signal, AtomicReference<TraceIds> traceIds) {
        if (signal.isOnComplete() || signal.isOnError()) {
            TraceIds current = currentTraceIds();
            if (!current.equals(TraceIds.unavailable())) {
                traceIds.set(current);
            }
        }
    }

    private TraceIds currentTraceIds() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) {
            return TraceIds.unavailable();
        }
        return new TraceIds(span.context().traceId(), span.context().spanId());
    }

    private static void logCompletion(
            UUID correlationId, TraceIds traceIds, ApiRequestObservation observation) {
        LoggingEventBuilder event = LOGGER.atInfo()
                .addKeyValue("event", "http_request_completed")
                .addKeyValue(
                        "method",
                        StructuredLogSanitizer.sanitize("method", observation.method()))
                .addKeyValue(
                        "route", StructuredLogSanitizer.sanitize("route", observation.route()))
                .addKeyValue(
                        "status", StructuredLogSanitizer.sanitize("status", observation.status()))
                .addKeyValue(
                        "outcome",
                        StructuredLogSanitizer.sanitize("outcome", observation.outcome()))
                .addKeyValue("durationMs", observation.durationMillis());
        if (MDC.get(CorrelationIdThreadLocalAccessor.MDC_KEY) == null) {
            event = event.addKeyValue("correlationId", correlationId);
        }
        if (MDC.get("traceId") == null) {
            event = event.addKeyValue("traceId", traceIds.traceId())
                    .addKeyValue("spanId", traceIds.spanId());
        }
        if (observation.errorCode() != null) {
            event = event.addKeyValue(
                    "errorCode",
                    StructuredLogSanitizer.sanitize("errorCode", observation.errorCode()));
        }
        if (observation.failureType() != null) {
            event = event.addKeyValue(
                    "failureType",
                    StructuredLogSanitizer.sanitize("failureType", observation.failureType()));
        }
        event.log("HTTP request completed");
    }

    private record TraceIds(String traceId, String spanId) {

        private static TraceIds unavailable() {
            return new TraceIds(UNAVAILABLE_TRACE_ID, UNAVAILABLE_TRACE_ID);
        }
    }
}
