package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.application.task.ClaimSchedulerMetricOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Verifies Claim metrics expose only the fixed outcome dimension. */
class TaskClaimSchedulerMetricsTest {

    @Test
    void recordsFixedLowCardinalityOutcomeCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskClaimSchedulerMetrics metrics = new TaskClaimSchedulerMetrics(registry);

        metrics.record(ClaimSchedulerMetricOutcome.CLAIMED, 2);
        metrics.record(ClaimSchedulerMetricOutcome.TEAM_QUOTA, 1);

        assertEquals(
                ClaimSchedulerMetricOutcome.values().length,
                registry.find(TaskClaimSchedulerMetrics.CLAIMS).counters().size());
        assertEquals(
                2.0,
                registry.get(TaskClaimSchedulerMetrics.CLAIMS)
                        .tag("outcome", "claimed")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get(TaskClaimSchedulerMetrics.CLAIMS)
                        .tag("outcome", "team_quota")
                        .counter()
                        .count());
    }
}
