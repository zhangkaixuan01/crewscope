package io.crewscope.infrastructure.runtime;

import io.crewscope.application.task.TaskClaimBatchResult;
import io.crewscope.application.task.TaskClaimScheduler;
import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ExecutionLeaseId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-level Claim loop for both {@code all} and independent {@code worker} deployments.
 *
 * <p>PostgreSQL Lease rows remain the ownership authority. The local map is deliberately limited
 * to capacity publication, graceful shutdown and duplicate dispatch protection inside one JVM.
 */
public final class TaskWorkerExecutionLoop implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskWorkerExecutionLoop.class);

    private final TaskClaimScheduler claimScheduler;
    private final TaskWorkerExecutionHandler executionHandler;
    private final TaskWorkerStartupReconciler startupReconciler;
    private final RuntimeWorkerLifecycle workerLifecycle;
    private final TaskWorkerLoadTracker loadTracker;
    private final TaskWorkerLoopSpec spec;
    private final ScheduledExecutorService poller;
    private final ExecutorService executions;
    private final ConcurrentMap<ExecutionLeaseId, ClaimReceipt> active = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean acceptingClaims = new AtomicBoolean();
    private final AtomicInteger reconciledExecutions = new AtomicInteger();
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();

    public TaskWorkerExecutionLoop(
            TaskClaimScheduler claimScheduler,
            TaskWorkerExecutionHandler executionHandler,
            TaskWorkerStartupReconciler startupReconciler,
            RuntimeWorkerLifecycle workerLifecycle,
            TaskWorkerLoadTracker loadTracker,
            TaskWorkerLoopSpec spec) {
        this.claimScheduler = Objects.requireNonNull(claimScheduler, "claimScheduler");
        this.executionHandler = Objects.requireNonNull(executionHandler, "executionHandler");
        this.startupReconciler = Objects.requireNonNull(startupReconciler, "startupReconciler");
        this.workerLifecycle = Objects.requireNonNull(workerLifecycle, "workerLifecycle");
        this.loadTracker = Objects.requireNonNull(loadTracker, "loadTracker");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "crewscope-task-worker-claim");
            thread.setDaemon(true);
            return thread;
        });
        this.executions = Executors.newFixedThreadPool(
                spec.maximumConcurrentExecutions(), runnable -> {
                    Thread thread = new Thread(runnable, "crewscope-task-worker-execution");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Performs recovery synchronously so startup fails before this process can claim new work. */
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            // Forces registration ordering and ensures the stable Worker identity already exists.
            workerLifecycle.identity();
            reconciledExecutions.set(startupReconciler.reconcile());
            acceptingClaims.set(true);
            long interval = spec.pollInterval().toMillis();
            poller.scheduleWithFixedDelay(this::pollSafely, 0, interval, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            lastFailure.set(failure);
            started.set(false);
            throw failure;
        }
    }

    public int activeExecutions() {
        return loadTracker.activeExecutions();
    }

    public TaskWorkerLoopHealth health() {
        Throwable failure = lastFailure.get();
        return new TaskWorkerLoopHealth(
                started.get(),
                acceptingClaims.get(),
                active.size(),
                reconciledExecutions.get(),
                Optional.ofNullable(failure).map(value -> value.getClass().getSimpleName()));
    }

    private synchronized void pollSafely() {
        if (!acceptingClaims.get()) {
            return;
        }
        try {
            int capacity = spec.maximumConcurrentExecutions() - active.size();
            if (capacity <= 0) {
                return;
            }
            int requested = Math.min(capacity, spec.claimBatchSize());
            TaskClaimBatchResult batch = claimScheduler.claim(requested);
            batch.receipts().forEach(this::dispatch);
            lastFailure.set(null);
        } catch (RuntimeException failure) {
            lastFailure.set(failure);
            LOGGER.error("Task Worker Claim polling failed", failure);
        }
    }

    private void dispatch(ClaimReceipt receipt) {
        ClaimReceipt required = Objects.requireNonNull(receipt, "receipt");
        if (active.putIfAbsent(required.leaseId(), required) != null) {
            LOGGER.warn("Task Worker ignored a duplicate in-process Lease dispatch");
            return;
        }
        loadTracker.executionStarted();
        try {
            executions.execute(() -> {
                try {
                    executionHandler.execute(required);
                } catch (RuntimeException failure) {
                    lastFailure.set(failure);
                    // Claim tokens and Task identities must never enter process logs.
                    LOGGER.error("Task Worker execution failed with {}",
                            failure.getClass().getSimpleName());
                } finally {
                    active.remove(required.leaseId(), required);
                    loadTracker.executionFinished();
                }
            });
        } catch (RuntimeException rejected) {
            active.remove(required.leaseId(), required);
            loadTracker.executionFinished();
            throw rejected;
        }
    }

    @Override
    public synchronized void close() {
        if (!started.getAndSet(false)) {
            return;
        }
        acceptingClaims.set(false);
        poller.shutdownNow();
        try {
            workerLifecycle.beginDrain();
        } catch (RuntimeException failure) {
            lastFailure.set(failure);
            LOGGER.error("Task Worker failed to enter DRAINING", failure);
        }
        awaitActive(spec.gracefulShutdownTimeout());
        active.keySet().forEach(executionHandler::requestStop);
        executions.shutdown();
        awaitTermination(executions, spec.gracefulShutdownTimeout());
        if (!executions.isTerminated()) {
            // Remaining owners are not forged as completed; Lease expiry and startup reconciliation
            // are the durable fallback after the process can no longer finish the finite segment.
            executions.shutdownNow();
        }
    }

    private void awaitActive(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!active.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(Math.min(25L, Math.max(1L, timeout.toMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void awaitTermination(ExecutorService executor, Duration timeout) {
        try {
            executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
