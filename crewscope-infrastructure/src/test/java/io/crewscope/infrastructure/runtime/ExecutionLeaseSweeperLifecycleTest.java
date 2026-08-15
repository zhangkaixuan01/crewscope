package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.LeaseSweepResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies bounded periodic Sweep execution and non-fatal failure reporting. */
class ExecutionLeaseSweeperLifecycleTest {

    @Test
    void schedulesBoundedSweepAndCapturesTransientFailure() {
        ExecutionLeaseSweeper sweeper = mock(ExecutionLeaseSweeper.class);
        when(sweeper.sweep(7))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new LeaseSweepResult(List.of()));
        ExecutionLeaseSweeperLifecycle lifecycle = new ExecutionLeaseSweeperLifecycle(
                sweeper, 7, Duration.ofMillis(10));
        try {
            lifecycle.start();
            verify(sweeper, timeout(500).atLeast(2)).sweep(7);
        } finally {
            lifecycle.close();
        }
        assertTrue(lifecycle.lastFailure().isEmpty());
    }
}
