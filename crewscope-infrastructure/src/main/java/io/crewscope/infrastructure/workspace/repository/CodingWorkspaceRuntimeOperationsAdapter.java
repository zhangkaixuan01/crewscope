package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.runtime.CodingCleanupSummary;
import io.crewscope.application.runtime.CodingRuntimeComponentHealth;
import io.crewscope.application.runtime.CodingRuntimeComponentSummary;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOperation;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOutcome;
import io.crewscope.application.runtime.CodingRuntimeOperationsPort;
import io.crewscope.application.runtime.CodingRuntimeOperationsUnavailableException;
import io.crewscope.application.runtime.CodingRuntimeSnapshot;
import io.crewscope.application.runtime.RuntimeCapacitySummary;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Adapts Worker-local Workspace handles and the I10 reconciler to safe application facts. */
public final class CodingWorkspaceRuntimeOperationsAdapter
        implements CodingRuntimeOperationsPort {

    private static final Duration OBSERVATION_TTL = Duration.ofSeconds(1);

    private final CodingWorkspaceRuntimeRegistry registry;
    private final CodingWorkspaceStartupReconciler reconciler;
    private final RuntimeWorkerRegistrationSpec registration;
    private final AuthoritativeTimeProvider timeProvider;
    private final TransactionExecutor transactions;
    private final AtomicReference<CodingRuntimeSnapshot> cached = new AtomicReference<>();

    public CodingWorkspaceRuntimeOperationsAdapter(
            CodingWorkspaceRuntimeRegistry registry,
            CodingWorkspaceStartupReconciler reconciler,
            RuntimeWorkerRegistrationSpec registration,
            AuthoritativeTimeProvider timeProvider,
            TransactionExecutor transactions) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Optional<CodingRuntimeSnapshot> observe(
            OrganizationId organizationId, RuntimeEnvironment environment) {
        if (!owns(organizationId, environment)) {
            return Optional.empty();
        }
        return Optional.of(snapshot(false));
    }

    @Override
    public CodingRuntimeMaintenanceOutcome maintain(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            CodingRuntimeMaintenanceOperation operation) {
        if (!owns(organizationId, environment)) {
            throw new CodingRuntimeOperationsUnavailableException();
        }
        CodingRuntimeMaintenanceOperation required = Objects.requireNonNull(operation, "operation");
        switch (required) {
            case RECONCILE -> reconciler.reconcileWorkspaceResources();
            case ARCHIVE -> reconciler.archiveWorkspaceResources();
        }
        return new CodingRuntimeMaintenanceOutcome(required, snapshot(true));
    }

    private CodingRuntimeSnapshot snapshot(boolean force) {
        var now = transactions.required(timeProvider::now);
        CodingRuntimeSnapshot previous = cached.get();
        if (!force && fresh(previous, now)) {
            return previous;
        }
        synchronized (cached) {
            previous = cached.get();
            if (!force && fresh(previous, now)) {
                return previous;
            }
            CodingRuntimeSnapshot observed = observeNow(now);
            cached.set(observed);
            return observed;
        }
    }

    private CodingRuntimeSnapshot observeNow(UtcTimestamp now) {
        List<CodingWorkspaceExecution> executions = registry.snapshot();
        int maximum = registration.maxConcurrentExecutions();
        int active = executions.size();
        if (active > maximum) {
            throw new IllegalStateException("Active Coding Workspaces exceed Worker capacity");
        }

        int runningSandboxes = 0;
        int healthyWatchers = 0;
        for (CodingWorkspaceExecution execution : executions) {
            try {
                if (execution.sandbox().isRunning()) {
                    runningSandboxes++;
                }
            } catch (RuntimeException ignored) {
                // Component health records the failure without exposing Docker details.
            }
            if (execution.diffMonitor()
                    .filter(monitor -> monitor.lastFailure().isEmpty())
                    .isPresent()) {
                healthyWatchers++;
            }
        }
        CodingRuntimeComponentSummary sandboxes = component(executions.size(), runningSandboxes);
        CodingRuntimeComponentSummary watchers = component(executions.size(), healthyWatchers);
        CodingCleanupSummary cleanup = cleanup(reconciler.health());
        CodingRuntimeComponentHealth health = overall(
                maximum - active, sandboxes.health(), watchers.health(), cleanup.health());
        return new CodingRuntimeSnapshot(
                registration.organizationId(),
                registration.environment(),
                now,
                health,
                new RuntimeCapacitySummary(maximum, active, maximum - active),
                sandboxes,
                watchers,
                cleanup);
    }

    /** Returns the current local snapshot for Actuator without accepting request scope. */
    public CodingRuntimeSnapshot localSnapshot() {
        return snapshot(false);
    }

    private static boolean fresh(
            CodingRuntimeSnapshot snapshot, UtcTimestamp now) {
        return snapshot != null
                && !now.value().isBefore(snapshot.observedAt().value())
                && Duration.between(snapshot.observedAt().value(), now.value())
                                .compareTo(OBSERVATION_TTL)
                        < 0;
    }

    private boolean owns(OrganizationId organizationId, RuntimeEnvironment environment) {
        return registration.organizationId()
                        .equals(Objects.requireNonNull(organizationId, "organizationId"))
                && registration.environment()
                        .equals(Objects.requireNonNull(environment, "environment"));
    }

    private static CodingRuntimeComponentSummary component(int total, int healthy) {
        int failed = total - healthy;
        return new CodingRuntimeComponentSummary(
                failed == 0
                        ? CodingRuntimeComponentHealth.HEALTHY
                        : CodingRuntimeComponentHealth.DEGRADED,
                total,
                healthy,
                failed);
    }

    private static CodingCleanupSummary cleanup(CodingWorkspaceStartupHealth state) {
        CodingRuntimeComponentHealth health;
        if (!state.completed()) {
            health = CodingRuntimeComponentHealth.UNAVAILABLE;
        } else if (state.failedWorkspaces() > 0
                || state.archiveFailures() > 0
                || state.capacityLimited()
                || state.lastFailureType().isPresent()) {
            health = CodingRuntimeComponentHealth.DEGRADED;
        } else {
            health = CodingRuntimeComponentHealth.HEALTHY;
        }
        return new CodingCleanupSummary(
                health,
                state.completed(),
                state.recoveredWorkspaces(),
                state.failedWorkspaces(),
                state.archivedWorkspaces(),
                state.archiveFailures(),
                state.removedSandboxOrphans(),
                state.purgedArtifacts(),
                state.capacityLimited(),
                state.lastFailureType());
    }

    private static CodingRuntimeComponentHealth overall(
            int available,
            CodingRuntimeComponentHealth sandboxes,
            CodingRuntimeComponentHealth watchers,
            CodingRuntimeComponentHealth cleanup) {
        if (cleanup == CodingRuntimeComponentHealth.UNAVAILABLE) {
            return CodingRuntimeComponentHealth.UNAVAILABLE;
        }
        if (available == 0
                || sandboxes == CodingRuntimeComponentHealth.DEGRADED
                || watchers == CodingRuntimeComponentHealth.DEGRADED
                || cleanup == CodingRuntimeComponentHealth.DEGRADED) {
            return CodingRuntimeComponentHealth.DEGRADED;
        }
        return CodingRuntimeComponentHealth.HEALTHY;
    }
}
