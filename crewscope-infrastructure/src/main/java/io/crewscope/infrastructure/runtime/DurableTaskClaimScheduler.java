package io.crewscope.infrastructure.runtime;

import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.task.ClaimQuotaRepository;
import io.crewscope.application.task.ClaimSchedulerMetricOutcome;
import io.crewscope.application.task.ClaimSchedulerMetrics;
import io.crewscope.application.task.ClaimTokenGenerator;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.TaskClaimBatchResult;
import io.crewscope.application.task.TaskClaimScheduler;
import io.crewscope.application.task.TaskExecutionQueueRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRuntimeCapabilityResolver;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ClaimToken;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskExecution;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL-backed Claim Scheduler for one stable Runtime Worker identity. */
public final class DurableTaskClaimScheduler implements TaskClaimScheduler {

    private final ExecutionRuntimeRepository runtimeRepository;
    private final RuntimeWorkerRepository workerRepository;
    private final TaskExecutionQueueRepository queueRepository;
    private final TaskExecutionRepository executionRepository;
    private final PolicySnapshotRepository policyRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final ClaimQuotaRepository quotaRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final ClaimTokenGenerator tokenGenerator;
    private final ClaimSchedulerMetrics metrics;
    private final TaskClaimSchedulerSpec spec;

    public DurableTaskClaimScheduler(
            ExecutionRuntimeRepository runtimeRepository,
            RuntimeWorkerRepository workerRepository,
            TaskExecutionQueueRepository queueRepository,
            TaskExecutionRepository executionRepository,
            PolicySnapshotRepository policyRepository,
            ExecutionLeaseRepository leaseRepository,
            ClaimQuotaRepository quotaRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            ClaimTokenGenerator tokenGenerator,
            ClaimSchedulerMetrics metrics,
            TaskClaimSchedulerSpec spec) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository");
        this.workerRepository = Objects.requireNonNull(workerRepository, "workerRepository");
        this.queueRepository = Objects.requireNonNull(queueRepository, "queueRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.quotaRepository = Objects.requireNonNull(quotaRepository, "quotaRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    public TaskClaimBatchResult claim(int requestedLimit) {
        if (requestedLimit < 1 || requestedLimit > spec.maximumBatchSize()) {
            throw new IllegalArgumentException(
                    "requestedLimit must be between 1 and " + spec.maximumBatchSize());
        }
        try {
            ClaimTransactionResult committed = transactionExecutor.required(
                    () -> claimTransaction(requestedLimit));
            recordMetrics(committed);
            return committed.result();
        } catch (RuntimeException failure) {
            safeRecord(ClaimSchedulerMetricOutcome.FAILED, 1);
            throw failure;
        }
    }

    private ClaimTransactionResult claimTransaction(int requestedLimit) {
        UtcTimestamp now = timeProvider.now();
        ExecutionRuntime runtime = requiredRuntime();
        RuntimeWorker worker = requiredWorker(runtime);
        int scanLimit = Math.min(spec.maximumScanSize(), Math.max(requestedLimit, requestedLimit * 4));
        List<TaskExecution> candidates = queueRepository.lockReadyBatch(
                new TaskExecutionQueueRepository.ReadyQuery(
                        spec.organizationId(), Optional.empty(), now, Optional.empty(), scanLimit));

        List<ClaimReceipt> receipts = new ArrayList<>();
        EnumMap<ClaimSchedulerMetricOutcome, Integer> outcomes =
                new EnumMap<>(ClaimSchedulerMetricOutcome.class);
        int scanned = 0;
        int waitingRuntime = 0;
        int capabilityDeferred = 0;
        int quotaDeferred = 0;

        for (TaskExecution candidate : candidates) {
            if (receipts.size() >= requestedLimit) {
                break;
            }
            scanned++;
            RuntimeCapabilities requiredCapabilities = requiredCapabilities(candidate);
            if (!worker.canClaim(
                    runtime, requiredCapabilities, now, spec.workerHeartbeatTimeout())) {
                if (!hasCapabilityCarrier(requiredCapabilities)) {
                    TaskExecution waiting = candidate.waitForRuntime(
                            candidate.version(), spec.actor(), now);
                    executionRepository.update(waiting);
                    waitingRuntime++;
                    increment(outcomes, ClaimSchedulerMetricOutcome.WAITING_RUNTIME);
                } else {
                    capabilityDeferred++;
                    increment(outcomes, ClaimSchedulerMetricOutcome.CAPABILITY_DEFERRED);
                }
                continue;
            }

            ClaimQuotaRepository.Decision quota = quotaRepository.check(
                    new ClaimQuotaRepository.QuotaQuery(
                            spec.organizationId(),
                            candidate.scope().teamId(),
                            spec.environment(),
                            runtime.id(),
                            worker.id(),
                            spec.teamConcurrentLimit(),
                            spec.runtimeConcurrentLimit(),
                            worker.capacity().maxConcurrentExecutions()));
            if (quota != ClaimQuotaRepository.Decision.AVAILABLE) {
                quotaDeferred++;
                ClaimSchedulerMetricOutcome metricOutcome = quotaOutcome(quota);
                increment(outcomes, metricOutcome);
                if (quota == ClaimQuotaRepository.Decision.RUNTIME_LIMIT
                        || quota == ClaimQuotaRepository.Decision.WORKER_LIMIT) {
                    break;
                }
                continue;
            }

            TaskExecution claimed = candidate.claim(candidate.version(), spec.actor(), now);
            ClaimToken token = tokenGenerator.generate();
            UtcTimestamp expiresAt = UtcTimestamp.from(
                    now.value().plus(spec.prepareLeaseDuration()));
            ExecutionLease lease = ExecutionLease.acquire(
                    ExecutionLeaseId.generate(),
                    claimed,
                    runtime,
                    worker,
                    requiredCapabilities,
                    spec.workerHeartbeatTimeout(),
                    token,
                    now,
                    expiresAt);
            ExecutionLease committedLease = leaseRepository.acquire(claimed, lease);
            receipts.add(new ClaimReceipt(
                    committedLease.id(),
                    claimed.id(),
                    claimed.attempt(),
                    runtime.id(),
                    worker.id(),
                    token,
                    claimed.lastFencingToken().orElseThrow(),
                    claimed.version(),
                    committedLease.version(),
                    committedLease.expiresAt()));
            increment(outcomes, ClaimSchedulerMetricOutcome.CLAIMED);
        }

        TaskClaimBatchResult result = new TaskClaimBatchResult(
                receipts, scanned, waitingRuntime, capabilityDeferred, quotaDeferred);
        if (scanned == 0) {
            increment(outcomes, ClaimSchedulerMetricOutcome.EMPTY);
        }
        return new ClaimTransactionResult(result, outcomes);
    }

    private RuntimeCapabilities requiredCapabilities(TaskExecution execution) {
        var planning = execution.planningContext().orElseThrow(() ->
                new DomainValidationException(
                        "taskExecution.planningContext", "must be initialized before Claim"));
        PolicySnapshot policy = policyRepository.findById(
                        spec.organizationId(), planning.policySnapshotId())
                .orElseThrow(() -> new DomainValidationException(
                        "taskExecution.planningContext.policySnapshotId",
                        "must reference an existing PolicySnapshot"));
        if (!policy.executionId().equals(execution.id())
                || !policy.taskId().equals(execution.taskId())
                || !policy.scope().equals(execution.scope())
                || !policy.snapshotHash().equals(planning.policySnapshotHash())) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.policySnapshotId",
                    "must reference the pinned PolicySnapshot for this execution");
        }
        return TaskRuntimeCapabilityResolver.resolve(policy);
    }

    private boolean hasCapabilityCarrier(RuntimeCapabilities requiredCapabilities) {
        return runtimeRepository.findByEnvironment(spec.organizationId(), spec.environment()).stream()
                .filter(runtime -> runtime.supports(requiredCapabilities))
                .anyMatch(runtime -> workerRepository.findByRuntime(
                                spec.organizationId(), spec.environment(), runtime.id()).stream()
                        .anyMatch(worker -> worker.capabilities().supports(requiredCapabilities)));
    }

    private ExecutionRuntime requiredRuntime() {
        return runtimeRepository.findByKey(
                        spec.organizationId(), spec.environment(), spec.runtimeKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Configured ExecutionRuntime is not registered: " + spec.runtimeKey()));
    }

    private RuntimeWorker requiredWorker(ExecutionRuntime runtime) {
        return workerRepository.findByStableKey(
                        spec.organizationId(),
                        spec.environment(),
                        runtime.id(),
                        spec.workerStableKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Configured RuntimeWorker is not registered: " + spec.workerStableKey()));
    }

    private void recordMetrics(ClaimTransactionResult result) {
        result.outcomes().forEach((outcome, amount) -> safeRecord(outcome, amount));
    }

    private void safeRecord(ClaimSchedulerMetricOutcome outcome, long amount) {
        try {
            metrics.record(outcome, amount);
        } catch (RuntimeException ignored) {
            // Metrics are deliberately unable to turn a committed Claim into an apparent failure.
        }
    }

    private static ClaimSchedulerMetricOutcome quotaOutcome(ClaimQuotaRepository.Decision decision) {
        return switch (decision) {
            case TEAM_LIMIT -> ClaimSchedulerMetricOutcome.TEAM_QUOTA;
            case RUNTIME_LIMIT -> ClaimSchedulerMetricOutcome.RUNTIME_QUOTA;
            case WORKER_LIMIT -> ClaimSchedulerMetricOutcome.WORKER_QUOTA;
            case AVAILABLE -> throw new IllegalArgumentException("AVAILABLE is not a quota rejection");
        };
    }

    private static void increment(
            Map<ClaimSchedulerMetricOutcome, Integer> outcomes,
            ClaimSchedulerMetricOutcome outcome) {
        outcomes.merge(outcome, 1, Integer::sum);
    }

    private record ClaimTransactionResult(
            TaskClaimBatchResult result,
            Map<ClaimSchedulerMetricOutcome, Integer> outcomes) {
        private ClaimTransactionResult {
            outcomes = Map.copyOf(outcomes);
        }
    }
}
