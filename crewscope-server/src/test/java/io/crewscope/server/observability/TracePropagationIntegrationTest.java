package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import io.crewscope.server.api.ApiCorrelationIds;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** Proves that the real Spring Boot tracing stack continues W3C parent context. */
@SpringBootTest(
        classes = TracePropagationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.reactor.context-propagation=auto",
            "management.tracing.propagation.type=w3c",
            "management.tracing.sampling.probability=1.0",
            "management.tracing.export.otlp.enabled=false"
        })
class TracePropagationIntegrationTest {

    private static final String UPSTREAM_TRACE_ID =
            "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UPSTREAM_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT =
            "00-" + UPSTREAM_TRACE_ID + "-" + UPSTREAM_SPAN_ID + "-01";
    private static final String CORRELATION_ID =
            "01989ee2-f6b0-7cda-97c4-1b337043d401";

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void continuesTheUpstreamTraceAndKeepsCorrelationInReactiveMdc() {
        TraceResponse response = client.get()
                .uri("/api/v1/observability/trace")
                .header("traceparent", TRACEPARENT)
                .header(ApiCorrelationIds.HEADER, CORRELATION_ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(ApiCorrelationIds.HEADER, CORRELATION_ID)
                .expectBody(TraceResponse.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(response);
        assertEquals(UPSTREAM_TRACE_ID, response.traceId());
        assertEquals(UPSTREAM_SPAN_ID, response.parentId());
        assertNotEquals(UPSTREAM_SPAN_ID, response.spanId());
        assertEquals(UPSTREAM_TRACE_ID, response.mdcTraceId());
        assertEquals(CORRELATION_ID, response.mdcCorrelationId());
        assertEquals(CORRELATION_ID, response.exchangeCorrelationId());

        LoggerContext loggingContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertTrue(loggingContext.getStatusManager().getCopyOfStatusList().stream()
                .noneMatch(status -> status.getMessage().contains("has already been written")));

        client.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertTrue(body.contains("crewscope_api_requests_seconds_count"));
                    assertTrue(body.contains("crewscope_api_correlation_ids_total"));
                    assertTrue(body.contains("http_server_requests_seconds_count"));
                });
    }

    @Test
    void replacesAnInvalidTraceparentWithANewServerTrace() {
        TraceResponse response = client.get()
                .uri("/api/v1/observability/trace")
                .header("traceparent", "invalid-parent")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(ApiCorrelationIds.HEADER, value -> assertNotNull(UUID.fromString(value)))
                .expectBody(TraceResponse.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(response);
        assertNotEquals(UPSTREAM_TRACE_ID, response.traceId());
        assertTrue(response.traceId().matches("[0-9a-f]{32}"));
        assertTrue(response.spanId().matches("[0-9a-f]{16}"));
        assertEquals(response.traceId(), response.mdcTraceId());
        assertEquals(response.exchangeCorrelationId(), response.mdcCorrelationId());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration",
                "org.springframework.boot.security.oauth2.client.autoconfigure.reactive.ReactiveOAuth2ClientAutoConfiguration",
                "org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration"
            })
    @Import({
        ApiObservabilityWebFilter.class,
        ApiObservabilityMetrics.class,
        TraceController.class
    })
    static class TestApplication {}

    @RestController
    @RequestMapping("/api/v1/observability")
    static class TraceController {

        private final Tracer tracer;

        TraceController(Tracer tracer) {
            this.tracer = tracer;
        }

        @GetMapping("/trace")
        TraceResponse trace(ServerWebExchange exchange) {
            Span span = tracer.currentSpan();
            if (span == null || span.isNoop()) {
                throw new IllegalStateException("A real server span is required");
            }
            return new TraceResponse(
                    span.context().traceId(),
                    span.context().spanId(),
                    span.context().parentId(),
                    MDC.get("traceId"),
                    MDC.get(CorrelationIdThreadLocalAccessor.MDC_KEY),
                    ApiCorrelationIds.resolve(exchange).toString());
        }
    }

    record TraceResponse(
            String traceId,
            String spanId,
            String parentId,
            String mdcTraceId,
            String mdcCorrelationId,
            String exchangeCorrelationId) {}
}
