package io.crewscope.infrastructure.runtime;

import io.crewscope.application.task.ExecutionLeaseSweeper;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** JVM lifecycle that periodically drains bounded expired-Lease batches. */
public final class ExecutionLeaseSweeperLifecycle implements AutoCloseable {

    private final ExecutionLeaseSweeper sweeper;
    private final int batchSize;
    private final Duration interval;
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private ScheduledExecutorService executor;

    public ExecutionLeaseSweeperLifecycle(
            ExecutionLeaseSweeper sweeper, int batchSize, Duration interval) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "crewscope-execution-lease-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::sweepSafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public Optional<Throwable> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    private void sweepSafely() {
        try {
            sweeper.sweep(batchSize);
            lastFailure.set(null);
        } catch (RuntimeException failure) {
            lastFailure.set(failure);
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
