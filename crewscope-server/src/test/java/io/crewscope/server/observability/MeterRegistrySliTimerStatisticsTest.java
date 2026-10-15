package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.observability.sli.SliTimerStatistics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The deployment-scoped Timer readings behind the SLI surface (M12-I03): series merge
 * across route/method/outcome dimensions, the actuator and file-download exclusions
 * applied query-side, and the 5xx share taken from the same excluded population. The
 * Prometheus registry is cumulative, so freshly recorded samples are visible at once.
 */
class MeterRegistrySliTimerStatisticsTest {

    private static final String API_ROUTE = "/api/v1/organizations/{organizationId}/tasks";
    private static final String PATCH_ROUTE =
            "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}"
                    + "/attempts/{executionId}/coding/artifacts/patch";

    private final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final MeterRegistrySliTimerStatistics statistics =
            new MeterRegistrySliTimerStatistics(registry);

    @Test
    void mergesSeriesAcrossDimensionsAndExcludesNonApiRoutes() {
        apiTimer("GET", API_ROUTE, "200", "success").record(Duration.ofMillis(100));
        apiTimer("GET", API_ROUTE, "200", "success").record(Duration.ofMillis(100));
        apiTimer("POST", API_ROUTE, "500", "server_error").record(Duration.ofMillis(400));
        // Actuator and file-download durations are transfers, not API latency.
        apiTimer("GET", "/actuator/prometheus", "200", "success")
                .record(Duration.ofMillis(5_000));
        apiTimer("GET", PATCH_ROUTE, "200", "success").record(Duration.ofMillis(8_000));

        Optional<SliTimerStatistics.Distribution> p95 = statistics.apiLatencyP95();
        assertTrue(p95.isPresent());
        // The excluded routes contribute neither samples nor latency.
        assertEquals(3L, p95.orElseThrow().sampleCount());
        double percentile = p95.orElseThrow().percentile95Millis();
        assertTrue(percentile >= 300 && percentile <= 500,
                "p95 " + percentile + " should ride the 400ms sample's bucket");
        assertEquals(1.0 / 3, statistics.apiServerErrorRatio().orElseThrow(), 1e-9);
    }

    @Test
    void seriesWithoutHistogramsStayEmptyRatherThanFakingZero() {
        // A timer registered without publishPercentileHistogram carries no buckets.
        Timer.builder(ApiObservabilityMetrics.REQUESTS)
                .tag("method", "GET")
                .tag("route", API_ROUTE)
                .tag("status", "200")
                .tag("outcome", "success")
                .register(registry)
                .record(Duration.ofMillis(100));
        assertTrue(statistics.apiLatencyP95().isEmpty());
        // The ratio is count-based and still answers from the same population.
        assertEquals(0.0, statistics.apiServerErrorRatio().orElseThrow(), 1e-9);
    }

    @Test
    void absentMetersAnswerEmpty() {
        assertTrue(statistics.apiLatencyP95().isEmpty());
        assertTrue(statistics.apiServerErrorRatio().isEmpty());
        assertTrue(statistics.firstTokenModelSideP95().isEmpty());
        assertTrue(statistics.firstTokenAppOverheadP95().isEmpty());
        assertTrue(statistics.outboxDeliveryP95().isEmpty());
        assertTrue(statistics.projectionReplayP95().isEmpty());
        assertTrue(statistics.knowledgeRetrievalP95().isEmpty());
    }

    private Timer apiTimer(String method, String route, String status, String outcome) {
        return Timer.builder(ApiObservabilityMetrics.REQUESTS)
                .tag("method", method)
                .tag("route", route)
                .tag("status", status)
                .tag("outcome", outcome)
                .publishPercentileHistogram(true)
                .register(registry);
    }
}
