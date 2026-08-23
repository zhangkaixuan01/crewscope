package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionReconciliationHealth;
import io.crewscope.application.action.ActionReconciliationOutcome;
import io.crewscope.application.action.ActionReconciliationTrace;
import io.crewscope.domain.action.ActionClaimMode;
import io.crewscope.domain.action.ActionKind;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** Low-cardinality metric and health contract for the M5-I12 reconciliation fleet. */
class ActionReconciliationObservabilityM5I12Test {

    @Test
    void identifiersStayInTraceContextAndNeverBecomeMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ActionReconciliationMetricsObserver observer =
                new ActionReconciliationMetricsObserver(registry, Tracer.NOOP);
        observer.record(
                new ActionReconciliationTrace(
                        OrganizationId.generate(),
                        TeamId.generate(),
                        TaskExecutionId.generate(),
                        ReviewDecisionId.generate(),
                        PlannedActionId.generate(),
                        ActionKind.PUSH_BRANCH,
                        ActionClaimMode.RECONCILE),
                ActionReconciliationOutcome.SUCCEEDED,
                Duration.ofMillis(25));
        observer.queueHealth(new ActionReconciliationHealth(
                1, 2, 3, 4, Optional.empty()));

        Meter attempts = registry.find(ActionReconciliationMetricsObserver.ATTEMPTS)
                .timer();
        assertEquals("push_branch", attempts.getId().getTag("kind"));
        assertEquals("reconcile", attempts.getId().getTag("mode"));
        assertEquals("succeeded", attempts.getId().getTag("outcome"));
        assertNull(attempts.getId().getTag("organizationId"));
        assertNull(attempts.getId().getTag("taskExecutionId"));
        assertNull(attempts.getId().getTag("reviewDecisionId"));
        assertNull(attempts.getId().getTag("actionId"));
        registry.find(ActionReconciliationMetricsObserver.QUEUE).meters()
                .forEach(meter -> assertEquals(
                        java.util.Set.of("state"),
                        meter.getId().getTags().stream()
                                .map(tag -> tag.getKey())
                                .collect(java.util.stream.Collectors.toSet())));
    }

    @Test
    void healthContainsOnlyAggregateCountsAndAge() {
        UtcTimestamp now = UtcTimestamp.parse("2026-08-24T10:00:00Z");
        ActionDispatchRepository dispatches = mock(ActionDispatchRepository.class);
        when(dispatches.reconciliationHealth()).thenReturn(new ActionReconciliationHealth(
                0,
                1,
                0,
                1,
                Optional.of(UtcTimestamp.from(now.value().minusSeconds(3600)))));
        TimeProvider time = () -> now;

        var health = new ActionReconciliationHealthIndicator(
                        dispatches, time, Duration.ofMinutes(30))
                .health();

        assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
        assertEquals(1L, health.getDetails().get("unknown"));
        assertEquals(1L, health.getDetails().get("manualReview"));
        assertEquals(3600L, health.getDetails().get("oldestUnresolvedAgeSeconds"));
        assertFalse(health.getDetails().keySet().stream().anyMatch(key ->
                key.toLowerCase(java.util.Locale.ROOT).contains("organization")
                        || key.toLowerCase(java.util.Locale.ROOT).contains("action")));
    }
}
