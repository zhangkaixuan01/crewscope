package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskClaimBatchResult;
import io.crewscope.application.task.TaskClaimScheduler;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ClaimToken;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.TaskExecutionId;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Process-loop contract for startup ordering, bounded capacity and graceful Drain. */
class TaskWorkerExecutionLoopM3I09Test {

    @Test
    void reconcilesBeforeFirstClaimAndPublishesAuthoritativeLocalLoad() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch executed = new CountDownLatch(1);
        ClaimReceipt receipt = receipt(1);
        AtomicInteger claims = new AtomicInteger();
        TaskClaimScheduler scheduler = limit -> {
            order.add("claim");
            return claims.getAndIncrement() == 0 ? batch(List.of(receipt)) : batch(List.of());
        };
        TaskWorkerExecutionHandler handler = value -> {
            order.add("execute");
            executed.countDown();
        };
        TaskWorkerStartupReconciler reconciler = () -> {
            order.add("reconcile");
            return 2;
        };
        RuntimeWorkerLifecycle lifecycle = lifecycle();
        TaskWorkerLoadTracker load = new TaskWorkerLoadTracker();
        TaskWorkerExecutionLoop loop = new TaskWorkerExecutionLoop(
                scheduler,
                handler,
                reconciler,
                lifecycle,
                load,
                new TaskWorkerLoopSpec(1, 1, Duration.ofMillis(50), Duration.ofSeconds(1)));
        try {
            loop.start();
            assertTrue(executed.await(2, TimeUnit.SECONDS));
            await(() -> loop.activeExecutions() == 0);
            assertEquals("reconcile", order.get(0));
            assertTrue(order.indexOf("claim") < order.indexOf("execute"));
            assertEquals(2, loop.health().reconciledExecutions());
            assertTrue(loop.health().acceptingClaims());
        } finally {
            loop.close();
        }
        verify(lifecycle).beginDrain();
        assertFalse(loop.health().started());
    }

    @Test
    void neverRunsPastCapacityAndDrainsBeforeShutdown() throws Exception {
        ArrayDeque<ClaimReceipt> pending = new ArrayDeque<>(
                List.of(receipt(1), receipt(2), receipt(3)));
        TaskClaimScheduler scheduler = limit -> {
            List<ClaimReceipt> claimed = new ArrayList<>();
            while (claimed.size() < limit && !pending.isEmpty()) {
                claimed.add(pending.removeFirst());
            }
            return batch(claimed);
        };
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(3);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        TaskWorkerExecutionHandler handler = value -> {
            int current = running.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            firstTwoStarted.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                running.decrementAndGet();
                allFinished.countDown();
            }
        };
        RuntimeWorkerLifecycle lifecycle = lifecycle();
        TaskWorkerExecutionLoop loop = new TaskWorkerExecutionLoop(
                scheduler,
                handler,
                () -> 0,
                lifecycle,
                new TaskWorkerLoadTracker(),
                new TaskWorkerLoopSpec(2, 2, Duration.ofMillis(50), Duration.ofSeconds(1)));
        try {
            loop.start();
            assertTrue(firstTwoStarted.await(2, TimeUnit.SECONDS));
            assertEquals(2, loop.activeExecutions());
            release.countDown();
            assertTrue(allFinished.await(2, TimeUnit.SECONDS));
            assertEquals(2, maximum.get());
        } finally {
            release.countDown();
            loop.close();
        }
        verify(lifecycle).beginDrain();
    }

    @Test
    void startupReconciliationFailurePreventsAnyClaim() {
        AtomicInteger claims = new AtomicInteger();
        TaskWorkerExecutionLoop loop = new TaskWorkerExecutionLoop(
                limit -> {
                    claims.incrementAndGet();
                    return batch(List.of());
                },
                receipt -> {},
                () -> {
                    throw new IllegalStateException("reconciliation failed");
                },
                lifecycle(),
                new TaskWorkerLoadTracker(),
                new TaskWorkerLoopSpec(1, 1, Duration.ofMillis(50), Duration.ofSeconds(1)));

        assertThrows(IllegalStateException.class, loop::start);
        assertEquals(0, claims.get());
        assertFalse(loop.health().started());
        assertFalse(loop.health().acceptingClaims());
    }

    private static RuntimeWorkerLifecycle lifecycle() {
        RuntimeWorkerLifecycle lifecycle = mock(RuntimeWorkerLifecycle.class);
        when(lifecycle.identity()).thenReturn(new RuntimeWorkerIdentity(
                new ExecutionRuntimeId(java.util.UUID.randomUUID()),
                new RuntimeWorkerId(java.util.UUID.randomUUID()),
                "worker-a",
                RuntimeProfile.WORKER));
        return lifecycle;
    }

    private static ClaimReceipt receipt(long fencing) {
        return new ClaimReceipt(
                ExecutionLeaseId.generate(),
                TaskExecutionId.generate(),
                1,
                new ExecutionRuntimeId(java.util.UUID.randomUUID()),
                new RuntimeWorkerId(java.util.UUID.randomUUID()),
                new ClaimToken("A".repeat(42) + fencing),
                new FencingToken(fencing),
                1,
                0,
                UtcTimestamp.parse("2026-08-15T06:00:30Z"));
    }

    private static TaskClaimBatchResult batch(List<ClaimReceipt> receipts) {
        return new TaskClaimBatchResult(receipts, receipts.size(), 0, 0, 0);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }
}
