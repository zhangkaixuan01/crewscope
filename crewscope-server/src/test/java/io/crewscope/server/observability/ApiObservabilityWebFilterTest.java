package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.server.api.ApiCorrelationIds;
import io.crewscope.server.api.ApiExceptionHandler;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

/** Proves request correlation, reactive MDC, safe completion logging and API metrics. */
class ApiObservabilityWebFilterTest {

    private SimpleMeterRegistry registry;
    private WebTestClient client;
    private TeamBetaOperationalTelemetry operationalTelemetry;
    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterAll
    static void disableAutomaticContextPropagation() {
        Hooks.disableAutomaticContextPropagation();
        MDC.clear();
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ApiObservabilityMetrics metrics = new ApiObservabilityMetrics(registry);
        operationalTelemetry = new TeamBetaOperationalTelemetry(
                registry, Tracer.NOOP, new TelemetryDegradationState());
        client = WebTestClient.bindToController(new ObservabilityController())
                .controllerAdvice(new ApiExceptionHandler())
                .webFilter(new ApiObservabilityWebFilter(
                        metrics, Tracer.NOOP, operationalTelemetry))
                .build();

        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ApiObservabilityWebFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        registry.close();
        MDC.clear();
    }

    @Test
    void acceptsOneCanonicalCorrelationIdAndRestoresItAcrossReactorSignals() {
        String correlationId = "01989ee2-f6b0-7cda-97c4-1b337043d401";

        client.get()
                .uri("/api/v1/observability/context")
                .header(ApiCorrelationIds.HEADER, correlationId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(ApiCorrelationIds.HEADER, correlationId)
                .expectBody()
                .jsonPath("$.correlationId")
                .isEqualTo(correlationId);

        assertEquals(
                1.0,
                registry.get(ApiObservabilityMetrics.CORRELATION_IDS)
                        .tag("result", "accepted")
                        .counter()
                        .count());
        assertEquals(
                1,
                registry.get(ApiObservabilityMetrics.REQUESTS)
                        .tags(
                                "method", "GET",
                                "route", "/api/v1/observability/context",
                                "status", "200",
                                "outcome", "success")
                        .timer()
                        .count());
    }

    @Test
    void generatesNewIdsForMissingMalformedAndRepeatedHeaders() {
        String missing = responseCorrelation(client.get().uri("/api/v1/observability/context"));
        String malformed = responseCorrelation(client.get()
                .uri("/api/v1/observability/context")
                .header(ApiCorrelationIds.HEADER, "not-a-uuid"));
        String repeated = responseCorrelation(client.get()
                .uri("/api/v1/observability/context")
                .header(ApiCorrelationIds.HEADER, UUID.randomUUID().toString(), UUID.randomUUID().toString()));

        assertNotNull(UUID.fromString(missing));
        assertNotNull(UUID.fromString(malformed));
        assertNotNull(UUID.fromString(repeated));
        assertNotEquals(missing, malformed);
        assertNotEquals(malformed, repeated);
        assertEquals(
                3.0,
                registry.get(ApiObservabilityMetrics.CORRELATION_IDS)
                        .tag("result", "generated")
                        .counter()
                        .count());
    }

    @Test
    void usesTheRouteTemplateInsteadOfTheRawPathInMetricsAndLogs() {
        String resourceId = "private-resource-01989ee2";

        client.get()
                .uri("/api/v1/observability/resources/{resourceId}?token=private", resourceId)
                .exchange()
                .expectStatus()
                .isOk();

        List<Meter> requestMeters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(ApiObservabilityMetrics.REQUESTS))
                .toList();
        assertEquals(1, requestMeters.size());
        assertEquals(
                "/api/v1/observability/resources/{resourceId}",
                requestMeters.get(0).getId().getTag("route"));

        ILoggingEvent completion = completionEvent();
        String structuredValues = completion.getKeyValuePairs().toString();
        assertTrue(structuredValues.contains("/api/v1/observability/resources/{resourceId}"));
        assertFalse(structuredValues.contains(resourceId));
        assertFalse(structuredValues.contains("private"));
    }

    @Test
    void recordsSafeStableErrorDataWithoutLoggingTheExceptionMessage() {
        client.get()
                .uri("/api/v1/observability/fail")
                .exchange()
                .expectStatus()
                .is5xxServerError()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("internal_error")
                .jsonPath("$.message")
                .isEqualTo("The request could not be completed");

        assertEquals(
                1.0,
                registry.get(ApiObservabilityMetrics.ERRORS)
                        .tags("code", "internal_error", "status", "500")
                        .counter()
                        .count());
        ILoggingEvent completion = completionEvent();
        Map<String, String> fields = completion.getKeyValuePairs().stream()
                .collect(java.util.stream.Collectors.toMap(
                        pair -> pair.key, pair -> pair.value.toString()));
        assertEquals("internal_error", fields.get("errorCode"));
        assertEquals(IllegalStateException.class.getName(), fields.get("failureType"));
        assertFalse(completion.getFormattedMessage().contains("credential-secret"));
        assertFalse(completion.getKeyValuePairs().toString().contains("credential-secret"));
    }

    @Test
    void recordsDomainErrorsUsingOnlyTheirStableCodeAndStatus() {
        client.get()
                .uri("/api/v1/observability/domain-fail")
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_value");

        assertEquals(
                1.0,
                registry.get(ApiObservabilityMetrics.ERRORS)
                        .tags("code", "invalid_value", "status", "422")
                        .counter()
                        .count());
        Map<String, String> fields = completionEvent().getKeyValuePairs().stream()
                .collect(java.util.stream.Collectors.toMap(
                        pair -> pair.key, pair -> pair.value.toString()));
        assertEquals("invalid_value", fields.get("errorCode"));
        assertFalse(fields.containsKey("failureType"));
    }

    @Test
    void neverUsesRequestIdentifiersOrSensitiveFieldsAsMetricTags() {
        responseCorrelation(client.get()
                .uri("/api/v1/observability/context")
                .header(ApiCorrelationIds.HEADER, UUID.randomUUID().toString()));

        List<String> forbiddenTagKeys = List.of(
                "correlationId",
                "traceId",
                "spanId",
                "idempotencyKey",
                "organizationId",
                "principalId",
                "resourceId",
                "uri",
                "exception",
                "token",
                "credential");
        for (Meter meter : registry.getMeters()) {
            List<String> tagKeys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey())
                    .toList();
            assertTrue(java.util.Collections.disjoint(tagKeys, forbiddenTagKeys));
        }
    }

    @Test
    void classifiesSseAndInboxRequestsIntoTheM6BoundedMetrics() {
        client.get()
                .uri("/api/v1/observability/conversations/demo/events")
                .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/api/v1/observability/inbox")
                .exchange()
                .expectStatus()
                .isOk();

        assertEquals(1, registry.get(TeamBetaOperationalTelemetry.SSE_DURATION)
                .tags("streamType", "conversation", "outcome", "success")
                .timer().count());
        assertEquals(1, registry.get(TeamBetaOperationalTelemetry.INBOX_DURATION)
                .tag("outcome", "success")
                .timer().count());
    }

    private String responseCorrelation(WebTestClient.RequestHeadersSpec<?> request) {
        return request.exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(ApiCorrelationIds.HEADER, value -> assertNotNull(UUID.fromString(value)))
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst(ApiCorrelationIds.HEADER);
    }

    private ILoggingEvent completionEvent() {
        return appender.list.stream()
                .filter(event -> event.getKeyValuePairs().stream()
                        .map(pair -> pair.value)
                        .anyMatch("http_request_completed"::equals))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @RestController
    @RequestMapping("/api/v1/observability")
    static class ObservabilityController {

        @GetMapping("/context")
        Mono<Map<String, String>> context() {
            return Mono.defer(() -> Mono.just(Map.of(
                    "correlationId", MDC.get(CorrelationIdThreadLocalAccessor.MDC_KEY))));
        }

        @GetMapping("/resources/{resourceId}")
        String resource(@PathVariable String resourceId) {
            return "ok";
        }

        @GetMapping("/fail")
        void fail() {
            throw new IllegalStateException("credential-secret must never enter logs");
        }

        @GetMapping("/domain-fail")
        void domainFail() {
            throw new DomainValidationException("workItem.title", "must not be blank");
        }

        @GetMapping(
                path = "/conversations/demo/events",
                produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
        Mono<String> stream() {
            return Mono.just("event");
        }

        @GetMapping("/inbox")
        String inbox() {
            return "ok";
        }
    }
}
