package io.crewscope.server.observability;

import io.crewscope.server.api.ApiCorrelationIds;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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

    public ApiObservabilityWebFilter(ApiObservabilityMetrics metrics, Tracer tracer) {
        this.metrics = metrics;
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        exchange.getResponse().getHeaders().set(ApiCorrelationIds.HEADER, correlationId.toString());
        metrics.recordCorrelation(ApiCorrelationIds.source(exchange));

        long startedAt = System.nanoTime();
        AtomicBoolean recorded = new AtomicBoolean();
        AtomicReference<TraceIds> traceIds = new AtomicReference<>(TraceIds.unavailable());

        traceIds.set(currentTraceIds());
        return chain.filter(exchange)
                .doOnEach(signal -> captureTraceIds(signal, traceIds))
                .doFinally(signalType -> {
                    if (recorded.compareAndSet(false, true)) {
                        ApiRequestObservation observation = metrics.record(
                                exchange, System.nanoTime() - startedAt, signalType);
                        logCompletion(correlationId, traceIds.get(), observation);
                    }
                })
                .contextWrite(context -> context.put(
                        CorrelationIdThreadLocalAccessor.KEY, correlationId.toString()));
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
