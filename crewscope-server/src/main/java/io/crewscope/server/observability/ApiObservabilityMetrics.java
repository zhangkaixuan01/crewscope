package io.crewscope.server.observability;

import io.crewscope.server.api.ApiCorrelationIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.SignalType;

/** Records the low-cardinality API metrics defined by ADR-008. */
@Component
public final class ApiObservabilityMetrics {

    public static final String REQUESTS = "crewscope.api.requests";
    public static final String ERRORS = "crewscope.api.errors";
    public static final String CORRELATION_IDS = "crewscope.api.correlation.ids";

    private static final String UNMATCHED_ROUTE = "unmatched";

    private final MeterRegistry registry;

    public ApiObservabilityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Counts whether a request correlation identifier was accepted or generated. */
    public void recordCorrelation(ApiCorrelationIds.Source source) {
        Counter.builder(CORRELATION_IDS)
                .description("Request correlation identifier resolutions")
                .tag("result", source.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
    }

    /** Records one completed request and returns the exact safe values used by logging. */
    public ApiRequestObservation record(
            ServerWebExchange exchange, long durationNanos, SignalType signalType) {
        int status = status(exchange, signalType);
        String method = exchange.getRequest().getMethod().name();
        String route = route(exchange);
        String outcome = outcome(status, signalType);
        String errorCode = ApiObservabilityContext.errorCode(exchange);
        String failureType = ApiObservabilityContext.failureType(exchange);

        Timer.builder(REQUESTS)
                .description("CrewScope API request duration")
                .tags(
                        "method", method,
                        "route", route,
                        "status", Integer.toString(status),
                        "outcome", outcome)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        if (errorCode != null) {
            Counter.builder(ERRORS)
                    .description("CrewScope API errors by stable machine code")
                    .tags("code", errorCode, "status", Integer.toString(status))
                    .register(registry)
                    .increment();
        }
        return new ApiRequestObservation(
                method,
                route,
                Integer.toString(status),
                outcome,
                durationNanos,
                errorCode,
                failureType);
    }

    private static int status(ServerWebExchange exchange, SignalType signalType) {
        HttpStatusCode responseStatus = exchange.getResponse().getStatusCode();
        if (responseStatus != null) {
            return responseStatus.value();
        }
        if (signalType == SignalType.CANCEL) {
            return 499;
        }
        return signalType == SignalType.ON_ERROR ? 500 : 200;
    }

    private static String route(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return UNMATCHED_ROUTE;
        }
        String value = pattern.toString();
        return value.startsWith("/") && value.length() <= 200 ? value : UNMATCHED_ROUTE;
    }

    private static String outcome(int status, SignalType signalType) {
        if (signalType == SignalType.CANCEL) {
            return "cancelled";
        }
        if (status < 200) {
            return "informational";
        }
        if (status < 300) {
            return "success";
        }
        if (status < 400) {
            return "redirection";
        }
        if (status < 500) {
            return "client_error";
        }
        return "server_error";
    }
}
