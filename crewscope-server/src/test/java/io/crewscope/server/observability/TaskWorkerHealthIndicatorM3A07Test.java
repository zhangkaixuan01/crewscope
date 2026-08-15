package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.runtime.RuntimeRegistryCoordinator;
import io.crewscope.infrastructure.runtime.RuntimeWorkerHealth;
import io.crewscope.infrastructure.runtime.RuntimeWorkerIdentity;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionLoop;
import io.crewscope.infrastructure.runtime.TaskWorkerLoopHealth;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

/** Actuator proof for durable heartbeat, Drain and capacity details without stable identities. */
class TaskWorkerHealthIndicatorM3A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T12:00:00Z");

    @Test
    void reportsDownForAStaleDurableWorker() {
        Health health = indicator(RuntimeWorkerStatus.ACTIVE, false, false, 1, 4).health();

        assertEquals("DOWN", health.getStatus().getCode());
        assertEquals(false, health.getDetails().get("heartbeatFresh"));
        assertFalse(health.getDetails().containsKey("workerId"));
        assertFalse(health.getDetails().containsKey("stableKey"));
    }

    @Test
    void reportsOutOfServiceWhileDraining() {
        Health health = indicator(RuntimeWorkerStatus.DRAINING, true, false, 1, 4).health();

        assertEquals("OUT_OF_SERVICE", health.getStatus().getCode());
        assertEquals("DRAINING", health.getDetails().get("workerStatus"));
    }

    @Test
    void keepsAHealthyButFullWorkerUpAndPublishesItsCapacity() {
        Health health = indicator(RuntimeWorkerStatus.ACTIVE, true, false, 4, 4).health();

        assertEquals("UP", health.getStatus().getCode());
        assertEquals(false, health.getDetails().get("claimable"));
        assertEquals(4, health.getDetails().get("reportedActiveExecutions"));
        assertEquals(4, health.getDetails().get("maxConcurrentExecutions"));
        assertTrue(health.getDetails().containsKey("lastFailureType"));
    }

    private TaskWorkerHealthIndicator indicator(
            RuntimeWorkerStatus status,
            boolean fresh,
            boolean claimable,
            int active,
            int maximum) {
        TaskWorkerExecutionLoop loop = mock(TaskWorkerExecutionLoop.class);
        when(loop.health()).thenReturn(new TaskWorkerLoopHealth(
                true, status == RuntimeWorkerStatus.ACTIVE, active, 0, Optional.empty()));
        RuntimeRegistryCoordinator coordinator = mock(RuntimeRegistryCoordinator.class);
        RuntimeWorkerIdentity identity = new RuntimeWorkerIdentity(
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                "worker-secret-identity",
                RuntimeProfile.WORKER);
        when(coordinator.health()).thenReturn(new RuntimeWorkerHealth(
                identity, status, fresh, claimable, active, maximum, 8, NOW));
        return new TaskWorkerHealthIndicator(loop, coordinator);
    }
}
