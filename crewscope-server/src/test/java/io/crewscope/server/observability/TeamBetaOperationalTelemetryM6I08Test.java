package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.operations.OperationsComponentSummary;
import io.crewscope.application.operations.OperationsHealthComponent;
import io.crewscope.application.operations.OperationsHealthLevel;
import io.crewscope.application.operations.OperationsMemberHealthSummary;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

/** Locks the M6-I08 Trace, metric-budget, health, degradation and log-redaction boundary. */
class TeamBetaOperationalTelemetryM6I08Test {

    @Test
    void recordsEveryM6BoundaryUsingOnlyFrozenLowCardinalityLabels() {
        TeamBetaMetricPolicy policy = new TeamBetaMetricPolicy();
        SimpleMeterRegistry registry = registry(policy);
        try {
            TeamBetaOperationalTelemetry telemetry = new TeamBetaOperationalTelemetry(
                    registry, Tracer.NOOP, new TelemetryDegradationState());

            observations().forEach(request -> telemetry.start(request).succeed());
            OperationalTelemetry.Observation failed = telemetry.start(
                    OperationalTelemetry.Request.lark(
                            OperationalTelemetry.Operation.QUERY));
            failed.complete(
                    OperationalTelemetry.Outcome.RETRY,
                    OperationalTelemetry.ErrorCode.RATE_LIMITED);
            telemetry.recordHealth(healthSummary());

            assertEquals(1, registry.get(TeamBetaOperationalTelemetry.OUTBOX_DURATION)
                    .tag("outcome", "success").timer().count());
            assertEquals(1, registry.get(TeamBetaOperationalTelemetry.PROJECTION_DURATION)
                    .tags("projectionName", "team_activity", "outcome", "success")
                    .timer().count());
            assertEquals(1, registry.get(TeamBetaOperationalTelemetry.SSE_DURATION)
                    .tags("streamType", "conversation", "outcome", "success")
                    .timer().count());
            assertEquals(1, registry.get(TeamBetaOperationalTelemetry.PROVIDER_ERRORS)
                    .tags("providerKey", "lark", "errorCode", "rate_limited")
                    .counter().count());
            assertEquals(1, registry.get(TeamBetaOperationalTelemetry.OPERATIONS_HEALTH)
                    .tags("type", "projection", "status", "degraded")
                    .gauge().value());

            for (Meter meter : registry.getMeters()) {
                if (!meter.getId().getName().startsWith(TeamBetaMetricPolicy.PREFIX)) {
                    continue;
                }
                Set<String> tagKeys = meter.getId().getTags().stream()
                        .map(tag -> tag.getKey().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
                assertTrue(java.util.Collections.disjoint(
                        tagKeys,
                        Set.of(
                                "organizationid", "teamid", "memberid", "taskid",
                                "correlationid", "traceid", "exceptionmessage", "uri")));
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void enforcesThePerMetricAndTotalSeriesBudgetsBeforeRegistration() {
        TeamBetaMetricPolicy policy = new TeamBetaMetricPolicy();

        assertTrue(policy.totalSeriesUpperBound()
                <= TeamBetaMetricPolicy.MAXIMUM_TOTAL_SERIES);
        assertThrows(IllegalArgumentException.class, () -> policy.validateDefinition(
                "crewscope.m6.invalid.identity", Set.of("organizationId")));
        assertThrows(IllegalArgumentException.class, () -> policy.validateDefinition(
                "crewscope.m6.invalid.unknown", Set.of("arbitrary")));
        assertThrows(IllegalArgumentException.class, () -> policy.validateDefinition(
                "crewscope.m6.invalid.product", Set.of("errorCode", "operation", "status")));

        SimpleMeterRegistry registry = registry(policy);
        try {
            registry.counter("crewscope.m6.undeclared", "outcome", "success").increment();
            assertFalse(registry.find("crewscope.m6.undeclared").meters().iterator().hasNext());
        } finally {
            registry.close();
        }
    }

    @Test
    void keepsTelemetryFailuresOutOfTheBusinessResultAndExposesAggregateDegradation() {
        TeamBetaMetricPolicy policy = new TeamBetaMetricPolicy();
        FailingTimerRegistry registry = new FailingTimerRegistry();
        registry.config().meterFilter(policy.meterFilter());
        try {
            TelemetryDegradationState state = new TelemetryDegradationState();
            Tracer throwingTracer = (Tracer) Proxy.newProxyInstance(
                    Tracer.class.getClassLoader(),
                    new Class<?>[] {Tracer.class},
                    (proxy, method, arguments) -> {
                        throw new IllegalStateException("collector-secret-must-not-escape");
                    });
            TeamBetaOperationalTelemetry telemetry = new TeamBetaOperationalTelemetry(
                    registry, throwingTracer, state);
            registry.failTimers = true;

            assertDoesNotThrow(() -> telemetry.start(
                            OperationalTelemetry.Request.inbox())
                    .succeed());

            assertTrue(state.count(TelemetryDegradationState.FailureType.TRACE) >= 1);
            assertTrue(state.count(TelemetryDegradationState.FailureType.BAGGAGE) >= 1);
            assertEquals(1, state.count(TelemetryDegradationState.FailureType.METRIC));
            assertTrue(state.total() >= 3);
            assertNotNull(new TeamBetaObservabilityConfiguration()
                    .crewscopeTelemetryHealthIndicator(state)
                    .health()
                    .getDetails()
                    .get("dropped"));
        } finally {
            registry.close();
        }
    }

    @Test
    void allowsOnlyTheThreeInternalBaggageFieldsAndNeverProviderPayloadFields() {
        assertTrue(TeamBetaOperationalTelemetry.isAllowedBaggageKey(
                TeamBetaOperationalTelemetry.BAGGAGE_CORRELATION_ID));
        assertTrue(TeamBetaOperationalTelemetry.isAllowedBaggageKey(
                TeamBetaOperationalTelemetry.BAGGAGE_OPERATION));
        assertTrue(TeamBetaOperationalTelemetry.isAllowedBaggageKey(
                TeamBetaOperationalTelemetry.BAGGAGE_WORKER_ROLE));
        assertFalse(TeamBetaOperationalTelemetry.isAllowedBaggageKey("organizationId"));
        assertFalse(TeamBetaOperationalTelemetry.isAllowedBaggageKey("providerPayload"));
        assertFalse(TeamBetaOperationalTelemetry.isAllowedBaggageKey("authorization"));
    }

    @Test
    void filtersSecretPiiControlCharactersAndOversizedValuesFromStructuredJson() {
        SafeStructuredLoggingJsonCustomizer customizer =
                new SafeStructuredLoggingJsonCustomizer();
        JsonWriter<Object> writer = JsonWriter.of(members -> {
            members.add("email", source -> ((Probe) source).email());
            members.add("message", source -> ((Probe) source).message());
            members.add("authorization", source -> ((Probe) source).authorization());
            members.add("safe", source -> ((Probe) source).safe());
            customizer.customize(members);
        });
        String secret = "Bearer credential-material";
        String email = "member@example.test";

        String json = writer.writeToString(new Probe(
                email,
                "call member@example.test\nfor support",
                secret,
                "safe\rvalue",
                "x".repeat(400)));

        assertFalse(json.contains(email));
        assertFalse(json.contains(secret));
        assertFalse(json.contains("credential-material"));
        assertFalse(json.contains("\\n"));
        assertTrue(json.contains(StructuredLogSanitizer.REDACTED));
        assertTrue(json.contains("safe value"));
        assertEquals(
                StructuredLogSanitizer.REDACTED,
                StructuredLogSanitizer.sanitize("message", "api_key=private-value"));
        assertEquals(
                StructuredLogSanitizer.MAX_VALUE_LENGTH,
                StructuredLogSanitizer.sanitize("safe", "x".repeat(400)).length());
    }

    private static SimpleMeterRegistry registry(TeamBetaMetricPolicy policy) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(policy.meterFilter());
        return registry;
    }

    private static List<OperationalTelemetry.Request> observations() {
        return List.of(
                OperationalTelemetry.Request.outbox(),
                OperationalTelemetry.Request.projection(
                        OperationalTelemetry.ProjectionName.TEAM_ACTIVITY),
                OperationalTelemetry.Request.sse(
                        OperationalTelemetry.StreamType.CONVERSATION),
                OperationalTelemetry.Request.inbox(),
                OperationalTelemetry.Request.notification(
                        OperationalTelemetry.Operation.DISPATCH),
                OperationalTelemetry.Request.notification(
                        OperationalTelemetry.Operation.RECONCILE),
                OperationalTelemetry.Request.notification(
                        OperationalTelemetry.Operation.REDELIVER),
                OperationalTelemetry.Request.lark(OperationalTelemetry.Operation.QUERY),
                OperationalTelemetry.Request.lark(OperationalTelemetry.Operation.DISPATCH),
                OperationalTelemetry.Request.teamObserver());
    }

    private static OperationsMemberHealthSummary healthSummary() {
        Map<OperationsHealthComponent, OperationsHealthLevel> levels =
                new EnumMap<>(OperationsHealthComponent.class);
        Arrays.stream(OperationsHealthComponent.values())
                .forEach(component -> levels.put(component, OperationsHealthLevel.HEALTHY));
        levels.put(OperationsHealthComponent.PROJECTION, OperationsHealthLevel.DEGRADED);
        List<OperationsComponentSummary> components = levels.entrySet().stream()
                .map(entry -> new OperationsComponentSummary(
                        entry.getKey(), entry.getValue(), 0, 0, 0, 0, 0, false))
                .toList();
        return new OperationsMemberHealthSummary(
                UtcTimestamp.from(Instant.parse("2026-08-26T10:00:00Z")),
                OperationsHealthLevel.DEGRADED,
                components);
    }

    private record Probe(
            String email,
            String message,
            String authorization,
            String safe,
            String oversized) {}

    private static final class FailingTimerRegistry extends SimpleMeterRegistry {

        private boolean failTimers;

        @Override
        protected Timer newTimer(
                Meter.Id id,
                DistributionStatisticConfig distributionStatisticConfig,
                PauseDetector pauseDetector) {
            if (failTimers
                    && id.getName().equals(TeamBetaOperationalTelemetry.INBOX_DURATION)) {
                throw new IllegalStateException("prometheus unavailable");
            }
            return super.newTimer(id, distributionStatisticConfig, pauseDetector);
        }
    }
}
