package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.application.task.LeaseCoordinatorOperation;
import io.crewscope.application.task.LeaseCoordinatorOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Verifies Lease metrics expose only fixed operation and outcome dimensions. */
class TaskExecutionLeaseMetricsTest {

    @Test
    void recordsPreRegisteredLowCardinalityCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskExecutionLeaseMetrics metrics = new TaskExecutionLeaseMetrics(registry);

        metrics.record(
                LeaseCoordinatorOperation.HEARTBEAT,
                LeaseCoordinatorOutcome.SUCCEEDED,
                2);

        assertEquals(
                LeaseCoordinatorOperation.values().length
                        * LeaseCoordinatorOutcome.values().length,
                registry.find(TaskExecutionLeaseMetrics.OPERATIONS).counters().size());
        assertEquals(
                2.0,
                registry.get(TaskExecutionLeaseMetrics.OPERATIONS)
                        .tag("operation", "heartbeat")
                        .tag("outcome", "succeeded")
                        .counter()
                        .count());
    }
}
