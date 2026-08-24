package io.crewscope.application.runtime;

import io.crewscope.application.action.TeamActionReconciliationHealthRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.ExecutionRuntimeStatus;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamPermission;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds member-safe and permission-gated operations views from current Runtime facts. */
public final class RuntimeObservationService {

    private static final RuntimeCapabilities NO_REQUIRED_CAPABILITIES =
            new RuntimeCapabilities(java.util.Set.of());

    private final WorkItemAccessPolicy accessPolicy;
    private final RuntimeObservationRepository repository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final Duration heartbeatTimeout;
    private final CodingRuntimeOperationsPort codingRuntime;
    private final TeamActionReconciliationHealthRepository actionDelivery;
    private final Duration actionStaleThreshold;

    public RuntimeObservationService(
            WorkItemAccessPolicy accessPolicy,
            RuntimeObservationRepository repository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            Duration heartbeatTimeout) {
        this(
                accessPolicy,
                repository,
                transactionExecutor,
                timeProvider,
                heartbeatTimeout,
                null,
                null,
                Duration.ofHours(1));
    }

    public RuntimeObservationService(
            WorkItemAccessPolicy accessPolicy,
            RuntimeObservationRepository repository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            Duration heartbeatTimeout,
            CodingRuntimeOperationsPort codingRuntime) {
        this(
                accessPolicy, repository, transactionExecutor, timeProvider,
                heartbeatTimeout, codingRuntime, null, Duration.ofHours(1));
    }

    public RuntimeObservationService(
            WorkItemAccessPolicy accessPolicy,
            RuntimeObservationRepository repository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            Duration heartbeatTimeout,
            CodingRuntimeOperationsPort codingRuntime,
            TeamActionReconciliationHealthRepository actionDelivery,
            Duration actionStaleThreshold) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        Duration requiredTimeout = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        if (requiredTimeout.compareTo(RuntimeWorker.MIN_HEARTBEAT_TIMEOUT) < 0
                || requiredTimeout.compareTo(RuntimeWorker.MAX_HEARTBEAT_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "heartbeatTimeout must be between 5 seconds and 10 minutes");
        }
        this.heartbeatTimeout = requiredTimeout;
        this.codingRuntime = codingRuntime;
        this.actionDelivery = actionDelivery;
        this.actionStaleThreshold = Objects.requireNonNull(
                actionStaleThreshold, "actionStaleThreshold");
    }

    /** Returns aggregate health and capacity without Runtime or Worker identities. */
    public RuntimeFleetSummary summary(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            RuntimeEnvironment environment) {
        return transactionExecutor.required(() -> {
            accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            UtcTimestamp now = timeProvider.now();
            DerivedObservation derived = derive(
                    new RuntimeObservationQuery(organizationId, teamId, environment), now);
            return derived.summary();
        });
    }

    /** Returns infrastructure identities and capabilities only to authorized operators. */
    public RuntimeOperationsView operations(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            RuntimeEnvironment environment) {
        return transactionExecutor.required(() -> {
            UtcTimestamp now = timeProvider.now();
            accessPolicy.requireTeamPermission(
                    context,
                    organizationId,
                    teamId,
                    TeamPermission.TEAM_OBSERVE,
                    now,
                    "observe Runtime operations details");
            DerivedObservation derived = derive(
                    new RuntimeObservationQuery(organizationId, teamId, environment), now);
            return new RuntimeOperationsView(
                    derived.summary(),
                    derived.snapshot().runtimes(),
                    derived.workers(),
                    derived.waiting(),
                    derived.codingRuntime());
        });
    }

    private DerivedObservation derive(RuntimeObservationQuery query, UtcTimestamp now) {
        RuntimeObservationSnapshot snapshot = repository.observe(query);
        Map<ExecutionRuntimeId, ExecutionRuntime> runtimes = requireSnapshot(query, snapshot);
        List<RuntimeWorkerObservation> workers = snapshot.workers().stream()
                .map(worker -> observeWorker(worker, runtimes.get(worker.runtimeId()), now))
                .toList();
        List<RuntimeWaitingDiagnostic> waiting = snapshot.waitingExecutions().stream()
                .map(value -> new RuntimeWaitingDiagnostic(
                        value, diagnose(value.requiredCapabilities(), snapshot, runtimes, now)))
                .toList();

        Optional<CodingRuntimeSnapshot> coding = codingRuntime == null
                ? Optional.empty()
                : codingRuntime.observe(query.organizationId(), query.environment());
        coding.ifPresent(value -> {
            if (!value.organizationId().equals(query.organizationId())
                    || !value.environment().equals(query.environment())) {
                throw invalidRepositoryResult();
            }
        });
        RuntimeFleetSummary summary = summary(
                query, now, snapshot, runtimes, workers, waiting, coding);
        return new DerivedObservation(summary, snapshot, workers, waiting, coding);
    }

    private Map<ExecutionRuntimeId, ExecutionRuntime> requireSnapshot(
            RuntimeObservationQuery query, RuntimeObservationSnapshot snapshot) {
        RuntimeObservationSnapshot required = Objects.requireNonNull(snapshot, "snapshot");
        Map<ExecutionRuntimeId, ExecutionRuntime> runtimes;
        try {
            runtimes = required.runtimes().stream().collect(Collectors.toMap(
                    ExecutionRuntime::id, Function.identity()));
        } catch (IllegalStateException duplicate) {
            throw invalidRepositoryResult();
        }
        if (required.runtimes().stream().anyMatch(runtime ->
                        !runtime.organizationId().equals(query.organizationId())
                                || !runtime.environment().equals(query.environment()))
                || required.workers().stream().anyMatch(worker -> {
                    ExecutionRuntime runtime = runtimes.get(worker.runtimeId());
                    return !worker.organizationId().equals(query.organizationId())
                            || !worker.environment().equals(query.environment())
                            || runtime == null
                            || !worker.capabilities().isSubsetOf(runtime.capabilities());
                })
                || required.waitingExecutions().stream().anyMatch(value ->
                        !value.execution().scope().organizationId().equals(query.organizationId())
                                || !value.execution().scope().teamId().equals(query.teamId()))) {
            throw invalidRepositoryResult();
        }
        return runtimes;
    }

    private RuntimeWorkerObservation observeWorker(
            RuntimeWorker worker, ExecutionRuntime runtime, UtcTimestamp now) {
        boolean fresh = worker.isHeartbeatFresh(now, heartbeatTimeout);
        boolean claimable = worker.canClaim(
                runtime, NO_REQUIRED_CAPABILITIES, now, heartbeatTimeout);
        return new RuntimeWorkerObservation(
                worker,
                runtime.status() == ExecutionRuntimeStatus.ACTIVE,
                fresh,
                claimable);
    }

    private RuntimeFleetSummary summary(
            RuntimeObservationQuery query,
            UtcTimestamp now,
            RuntimeObservationSnapshot snapshot,
            Map<ExecutionRuntimeId, ExecutionRuntime> runtimes,
            List<RuntimeWorkerObservation> workers,
            List<RuntimeWaitingDiagnostic> waiting,
            Optional<CodingRuntimeSnapshot> codingRuntime) {
        List<RuntimeWorkerObservation> freshActive = workers.stream()
                .filter(value -> value.worker().status() == RuntimeWorkerStatus.ACTIVE)
                .filter(RuntimeWorkerObservation::heartbeatFresh)
                // A fresh Worker under a disabled or archived Runtime cannot serve new work.
                .filter(value -> runtimes.get(value.worker().runtimeId()).status()
                        == ExecutionRuntimeStatus.ACTIVE)
                .toList();
        int maximum = freshActive.stream()
                .mapToInt(value -> value.worker().capacity().maxConcurrentExecutions())
                .sum();
        int active = freshActive.stream()
                .mapToInt(value -> value.worker().capacity().activeExecutions())
                .sum();
        int stale = (int) workers.stream()
                .filter(value -> value.worker().status() == RuntimeWorkerStatus.ACTIVE)
                .filter(value -> !value.heartbeatFresh())
                .count();
        int draining = (int) workers.stream()
                .filter(value -> value.worker().status() == RuntimeWorkerStatus.DRAINING)
                .count();
        int activeRuntimes = (int) snapshot.runtimes().stream()
                .filter(value -> value.status() == ExecutionRuntimeStatus.ACTIVE)
                .count();
        RuntimeCapacitySummary capacity = new RuntimeCapacitySummary(
                maximum, active, maximum - active);
        RuntimeFleetHealth health = fleetHealth(
                activeRuntimes, freshActive.size(), capacity.available(), stale, draining, waiting.size());
        EnumMap<RuntimeWaitCause, Long> causes = new EnumMap<>(RuntimeWaitCause.class);
        waiting.forEach(value -> causes.merge(value.cause(), 1L, Long::sum));
        return new RuntimeFleetSummary(
                query.environment(),
                now,
                health,
                snapshot.runtimes().size(),
                workers.size(),
                freshActive.size(),
                stale,
                draining,
                capacity,
                waiting.size(),
                causes,
                codingRuntime.map(CodingWorkspaceFleetSummary::from),
                actionDelivery == null
                        ? Optional.empty()
                        : Optional.of(ActionDeliveryFleetSummary.from(
                                actionDelivery.reconciliationHealth(
                                        query.organizationId(), query.teamId()),
                                now,
                                actionStaleThreshold)));
    }

    private static RuntimeFleetHealth fleetHealth(
            int activeRuntimes,
            int freshActiveWorkers,
            int availableCapacity,
            int staleWorkers,
            int drainingWorkers,
            int waitingExecutions) {
        if (activeRuntimes == 0 || freshActiveWorkers == 0) {
            return RuntimeFleetHealth.UNAVAILABLE;
        }
        if (availableCapacity == 0
                || staleWorkers > 0
                || drainingWorkers > 0
                || waitingExecutions > 0) {
            return RuntimeFleetHealth.DEGRADED;
        }
        return RuntimeFleetHealth.HEALTHY;
    }

    private RuntimeWaitCause diagnose(
            RuntimeCapabilities required,
            RuntimeObservationSnapshot snapshot,
            Map<ExecutionRuntimeId, ExecutionRuntime> runtimes,
            UtcTimestamp now) {
        List<ExecutionRuntime> capableRuntimes = snapshot.runtimes().stream()
                .filter(runtime -> runtime.supports(required))
                .toList();
        if (capableRuntimes.isEmpty()) {
            return RuntimeWaitCause.CAPABILITY_UNAVAILABLE;
        }
        Map<ExecutionRuntimeId, ExecutionRuntime> capableById = capableRuntimes.stream()
                .collect(Collectors.toMap(ExecutionRuntime::id, Function.identity()));
        List<RuntimeWorker> capableWorkers = snapshot.workers().stream()
                .filter(worker -> capableById.containsKey(worker.runtimeId()))
                .filter(worker -> worker.capabilities().supports(required))
                .toList();
        if (capableWorkers.isEmpty()) {
            return RuntimeWaitCause.CAPABILITY_UNAVAILABLE;
        }
        List<RuntimeWorker> active = capableWorkers.stream()
                .filter(worker -> worker.status() == RuntimeWorkerStatus.ACTIVE)
                .toList();
        if (active.isEmpty()) {
            boolean draining = capableWorkers.stream()
                    .anyMatch(worker -> worker.status() == RuntimeWorkerStatus.DRAINING);
            return draining ? RuntimeWaitCause.DRAINING : RuntimeWaitCause.NO_ACTIVE_WORKER;
        }
        List<RuntimeWorker> fresh = active.stream()
                .filter(worker -> worker.isHeartbeatFresh(now, heartbeatTimeout))
                .toList();
        if (fresh.isEmpty()) {
            return RuntimeWaitCause.HEARTBEAT_STALE;
        }
        boolean available = fresh.stream().anyMatch(worker -> worker.canClaim(
                runtimes.get(worker.runtimeId()), required, now, heartbeatTimeout));
        return available
                ? RuntimeWaitCause.REQUEUE_PENDING
                : RuntimeWaitCause.CAPACITY_EXHAUSTED;
    }

    private static DomainValidationException invalidRepositoryResult() {
        return new DomainValidationException(
                "runtimeObservation.repositoryResult",
                "must remain inside the requested Organization, Team and environment");
    }

    private record DerivedObservation(
            RuntimeFleetSummary summary,
            RuntimeObservationSnapshot snapshot,
            List<RuntimeWorkerObservation> workers,
            List<RuntimeWaitingDiagnostic> waiting,
            Optional<CodingRuntimeSnapshot> codingRuntime) {}
}
