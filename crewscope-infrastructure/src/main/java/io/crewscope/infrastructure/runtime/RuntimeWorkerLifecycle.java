package io.crewscope.infrastructure.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts one JVM Worker synchronously and maintains its durable Heartbeat until shutdown. */
public final class RuntimeWorkerLifecycle implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeWorkerLifecycle.class);

    private final RuntimeRegistryCoordinator coordinator;
    private final long heartbeatIntervalMillis;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<RuntimeWorkerIdentity> identity = new AtomicReference<>();
    private final AtomicReference<Throwable> lastHeartbeatFailure = new AtomicReference<>();

    public RuntimeWorkerLifecycle(
            RuntimeRegistryCoordinator coordinator, java.time.Duration heartbeatInterval) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        java.time.Duration interval = Objects.requireNonNull(
                heartbeatInterval, "heartbeatInterval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        this.heartbeatIntervalMillis = interval.toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "crewscope-runtime-worker-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Fails application startup when stable registration cannot be completed. */
    public synchronized void start() {
        if (identity.get() != null) {
            return;
        }
        identity.set(coordinator.register());
        scheduler.scheduleWithFixedDelay(
                this::heartbeatSafely,
                heartbeatIntervalMillis,
                heartbeatIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /** Exposes explicit Drain for shutdown orchestration and future operations endpoints. */
    public RuntimeWorkerIdentity beginDrain() {
        RuntimeWorkerIdentity draining = coordinator.beginDrain();
        identity.set(draining);
        return draining;
    }

    public RuntimeWorkerIdentity identity() {
        RuntimeWorkerIdentity current = identity.get();
        if (current == null) {
            throw new IllegalStateException("Runtime Worker lifecycle has not started");
        }
        return current;
    }

    public RuntimeWorkerHealth health() {
        return coordinator.health();
    }

    public Optional<Throwable> lastHeartbeatFailure() {
        return Optional.ofNullable(lastHeartbeatFailure.get());
    }

    private void heartbeatSafely() {
        try {
            identity.set(coordinator.heartbeat());
            lastHeartbeatFailure.set(null);
        } catch (RuntimeException failure) {
            lastHeartbeatFailure.set(failure);
            LOGGER.error(
                    "Runtime Worker heartbeat failed for stable identity {}",
                    identity.get(),
                    failure);
        }
    }

    @Override
    public synchronized void close() {
        scheduler.shutdownNow();
    }
}
