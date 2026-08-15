package io.crewscope.infrastructure.runtime;

import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.ExecutionRuntimeStatus;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;

/** Coordinates idempotent Runtime registration and the durable lifecycle of one JVM Worker. */
public final class RuntimeRegistryCoordinator {

    private static final int MAX_CONFLICT_ATTEMPTS = 4;

    private final ExecutionRuntimeRepository runtimeRepository;
    private final RuntimeWorkerRepository workerRepository;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;
    private final RuntimeWorkerRegistrationSpec spec;
    private final RuntimeWorkerLoadProvider loadProvider;

    public RuntimeRegistryCoordinator(
            ExecutionRuntimeRepository runtimeRepository,
            RuntimeWorkerRepository workerRepository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec spec,
            RuntimeWorkerLoadProvider loadProvider) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository");
        this.workerRepository = Objects.requireNonNull(workerRepository, "workerRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.loadProvider = Objects.requireNonNull(loadProvider, "loadProvider");
    }

    /** Registers or reconciles the configured Runtime and returns the persisted stable identity. */
    public RuntimeWorkerIdentity register() {
        return withConflictRetry(() -> transactionExecutor.required(this::registerOnce));
    }

    /** Publishes a heartbeat using the latest persisted versions and current JVM load. */
    public RuntimeWorkerIdentity heartbeat() {
        return withConflictRetry(() -> transactionExecutor.required(() -> {
            ExecutionRuntime runtime = requiredRuntime();
            RuntimeWorker worker = requiredWorker(runtime);
            requireProfile(worker);
            if (worker.status() == RuntimeWorkerStatus.DISABLED) {
                throw new IllegalStateException(
                        "Configured Runtime Worker is DISABLED: " + worker.id());
            }
            RuntimeWorker updated = worker.heartbeat(
                    runtime,
                    worker.version(),
                    spec.workerCapabilities(),
                    currentCapacity(),
                    spec.actor(),
                    timeProvider.now());
            return identity(workerRepository.update(updated));
        }));
    }

    /** Enters DRAINING idempotently so later schedulers cannot assign new executions. */
    public RuntimeWorkerIdentity beginDrain() {
        return withConflictRetry(() -> transactionExecutor.required(() -> {
            ExecutionRuntime runtime = requiredRuntime();
            RuntimeWorker worker = requiredWorker(runtime);
            requireProfile(worker);
            if (worker.status() == RuntimeWorkerStatus.DRAINING) {
                return identity(worker);
            }
            RuntimeWorker draining = worker.beginDrain(
                    worker.version(), spec.actor(), timeProvider.now());
            return identity(workerRepository.update(draining));
        }));
    }

    /** Reads explicit status and derives freshness/claimability at an authoritative instant. */
    public RuntimeWorkerHealth health() {
        return transactionExecutor.required(() -> {
            ExecutionRuntime runtime = requiredRuntime();
            RuntimeWorker worker = requiredWorker(runtime);
            UtcTimestamp now = timeProvider.now();
            boolean fresh = worker.isHeartbeatFresh(now, spec.heartbeatTimeout());
            boolean claimable = worker.canClaim(
                    runtime,
                    new RuntimeCapabilities(java.util.Set.of()),
                    now,
                    spec.heartbeatTimeout());
            return new RuntimeWorkerHealth(
                    identity(worker),
                    worker.status(),
                    fresh,
                    claimable,
                    worker.capacity().activeExecutions(),
                    worker.capacity().maxConcurrentExecutions(),
                    worker.heartbeatSequence(),
                    worker.lastHeartbeatAt());
        });
    }

    private RuntimeWorkerIdentity registerOnce() {
        UtcTimestamp now = timeProvider.now();
        ExecutionRuntime runtime = runtimeRepository
                .findByKey(spec.organizationId(), spec.environment(), spec.runtimeKey())
                .map(existing -> reconcileRuntime(existing, now))
                .orElseGet(() -> runtimeRepository.create(ExecutionRuntime.register(
                        deterministicRuntimeId(),
                        spec.organizationId(),
                        spec.environment(),
                        spec.runtimeKey(),
                        spec.runtimeDisplayName(),
                        spec.implementationVersion(),
                        spec.runtimeCapabilities(),
                        spec.actor(),
                        now)));

        RuntimeWorker worker = workerRepository
                .findByStableKey(
                        spec.organizationId(),
                        spec.environment(),
                        runtime.id(),
                        spec.workerStableKey())
                .map(existing -> reconcileWorker(runtime, existing, now))
                .orElseGet(() -> registerWorker(runtime, now));
        return identity(worker);
    }

    private ExecutionRuntime reconcileRuntime(ExecutionRuntime runtime, UtcTimestamp now) {
        if (!runtime.displayName().equals(spec.runtimeDisplayName())) {
            throw new IllegalStateException(
                    "Runtime displayName differs for stable key " + spec.runtimeKey());
        }
        if (runtime.status() != ExecutionRuntimeStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Configured ExecutionRuntime is not ACTIVE: " + runtime.status());
        }
        if (runtime.implementationVersion().equals(spec.implementationVersion())
                && runtime.capabilities().equals(spec.runtimeCapabilities())) {
            return runtime;
        }
        ExecutionRuntime published = runtime.publishCapabilities(
                spec.runtimeCapabilities(),
                spec.implementationVersion(),
                runtime.version(),
                spec.actor(),
                now);
        return runtimeRepository.update(published);
    }

    private RuntimeWorker registerWorker(ExecutionRuntime runtime, UtcTimestamp now) {
        RuntimeWorker registered = workerRepository.create(RuntimeWorker.register(
                deterministicWorkerId(runtime.id()),
                runtime,
                spec.workerStableKey(),
                spec.workerProfile(),
                spec.workerCapabilities(),
                currentCapacity(),
                spec.actor(),
                now));
        RuntimeWorker active = registered.activate(
                registered.version(), spec.actor(), now);
        return workerRepository.update(active);
    }

    private RuntimeWorker reconcileWorker(
            ExecutionRuntime runtime, RuntimeWorker worker, UtcTimestamp now) {
        requireProfile(worker);
        if (worker.status() == RuntimeWorkerStatus.DISABLED) {
            throw new IllegalStateException(
                    "Configured Runtime Worker is DISABLED: " + worker.id());
        }
        RuntimeWorker next;
        if (worker.status() == RuntimeWorkerStatus.REGISTERED) {
            // A process may stop after the insert but before activation. Reconcile its complete
            // deployment snapshot before making the durable identity claimable.
            RuntimeWorker reconciled = worker.heartbeat(
                    runtime,
                    worker.version(),
                    spec.workerCapabilities(),
                    currentCapacity(),
                    spec.actor(),
                    now);
            RuntimeWorker committed = workerRepository.update(reconciled);
            next = committed.activate(committed.version(), spec.actor(), now);
        } else {
            next = worker.heartbeat(
                    runtime,
                    worker.version(),
                    spec.workerCapabilities(),
                    currentCapacity(),
                    spec.actor(),
                    now);
        }
        return workerRepository.update(next);
    }

    private ExecutionRuntime requiredRuntime() {
        return runtimeRepository
                .findByKey(spec.organizationId(), spec.environment(), spec.runtimeKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Configured ExecutionRuntime is not registered: " + spec.runtimeKey()));
    }

    private RuntimeWorker requiredWorker(ExecutionRuntime runtime) {
        return workerRepository
                .findByStableKey(
                        spec.organizationId(),
                        spec.environment(),
                        runtime.id(),
                        spec.workerStableKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Configured Runtime Worker is not registered: "
                                + spec.workerStableKey()));
    }

    private RuntimeWorkerCapacity currentCapacity() {
        return new RuntimeWorkerCapacity(
                spec.maxConcurrentExecutions(), loadProvider.activeExecutions());
    }

    private void requireProfile(RuntimeWorker worker) {
        if (worker.profile() != spec.workerProfile()) {
            throw new IllegalStateException(
                    "Worker Profile differs for stable key " + spec.workerStableKey());
        }
    }

    private RuntimeWorkerIdentity identity(RuntimeWorker worker) {
        return new RuntimeWorkerIdentity(
                worker.runtimeId(), worker.id(), worker.stableKey(), worker.profile());
    }

    private ExecutionRuntimeId deterministicRuntimeId() {
        return new ExecutionRuntimeId(deterministicUuid(
                "runtime|" + spec.organizationId() + '|' + spec.environment() + '|'
                        + spec.runtimeKey()));
    }

    private RuntimeWorkerId deterministicWorkerId(ExecutionRuntimeId runtimeId) {
        return new RuntimeWorkerId(deterministicUuid(
                "worker|" + runtimeId + '|' + spec.workerStableKey()));
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(
                ("crewscope:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private <T> T withConflictRetry(Supplier<T> operation) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_CONFLICT_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (OptimisticLockConflictException | DataIntegrityViolationException conflict) {
                last = conflict;
            }
        }
        throw Objects.requireNonNull(last, "lastConflict");
    }
}
