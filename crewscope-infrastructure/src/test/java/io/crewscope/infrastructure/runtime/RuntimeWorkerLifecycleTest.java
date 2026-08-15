package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Verifies that the JVM lifecycle registers synchronously and maintains periodic Heartbeats. */
class RuntimeWorkerLifecycleTest {

    @Test
    void startsWithStableIdentityAndSchedulesHeartbeat() {
        RuntimeRegistryCoordinator coordinator = mock(RuntimeRegistryCoordinator.class);
        RuntimeWorkerIdentity identity = new RuntimeWorkerIdentity(
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                "worker-a",
                RuntimeProfile.WORKER);
        when(coordinator.register()).thenReturn(identity);
        when(coordinator.heartbeat()).thenReturn(identity);

        RuntimeWorkerLifecycle lifecycle = new RuntimeWorkerLifecycle(
                coordinator, Duration.ofMillis(10));
        try {
            lifecycle.start();

            assertEquals(identity, lifecycle.identity());
            verify(coordinator, timeout(500).atLeastOnce()).heartbeat();
        } finally {
            lifecycle.close();
        }
        verify(coordinator, atLeastOnce()).register();
    }
}
