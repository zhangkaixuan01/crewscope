package io.crewscope.infrastructure.runtime;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.LeaseCommandScope;
import io.crewscope.application.task.LeaseExecutionCommand;
import io.crewscope.application.task.LeaseMutationResult;
import io.crewscope.application.task.LeaseReleaseCommand;
import io.crewscope.application.task.LeaseTransitionCommand;
import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.task.TaskTokenIssueCommand;
import io.crewscope.application.task.TaskTokenIssueResult;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.task.TaskTokenRevokeCommand;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecution;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecutionLifecycle;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Resolves every server-owned fact needed before the AgentScope Task runtime is invoked. */
public final class DurableTaskWorkerExecutionFactory {

    private static final Set<String> CONTROLLED_TOOLS =
            io.crewscope.application.task.TaskPlanPublicationService.M3_CONTROLLED_TOOLS;

    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final PolicySnapshotRepository policyRepository;
    private final SafetyEnforcementOverlayRepository overlayRepository;
    private final PlanVersionRepository planRepository;
    private final TaskAgentRuntimeSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final PrincipalRepository principalRepository;
    private final AgentProfileRepository profileRepository;
    private final TaskExecutionLeaseCoordinator leaseCoordinator;
    private final TaskTokenService tokenService;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final RuntimeWorkerRegistrationSpec registration;
    private final RuntimeWorkerLifecycle workerLifecycle;
    private final Duration tokenLifetime;
    private final CodingWorkspaceExecutionLifecycle codingLifecycle;

    public DurableTaskWorkerExecutionFactory(
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            PlanVersionRepository planRepository,
            TaskAgentRuntimeSessionRepository sessionRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository profileRepository,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            TaskTokenService tokenService,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            RuntimeWorkerLifecycle workerLifecycle,
            Duration tokenLifetime) {
        this(
                taskRepository,
                executionRepository,
                leaseRepository,
                policyRepository,
                overlayRepository,
                planRepository,
                sessionRepository,
                runRepository,
                principalRepository,
                profileRepository,
                leaseCoordinator,
                tokenService,
                transactionExecutor,
                timeProvider,
                registration,
                workerLifecycle,
                tokenLifetime,
                CodingWorkspaceExecutionLifecycle.NOOP);
    }

    public DurableTaskWorkerExecutionFactory(
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            PlanVersionRepository planRepository,
            TaskAgentRuntimeSessionRepository sessionRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository profileRepository,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            TaskTokenService tokenService,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            RuntimeWorkerLifecycle workerLifecycle,
            Duration tokenLifetime,
            CodingWorkspaceExecutionLifecycle codingLifecycle) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.overlayRepository = Objects.requireNonNull(overlayRepository, "overlayRepository");
        this.planRepository = Objects.requireNonNull(planRepository, "planRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.leaseCoordinator = Objects.requireNonNull(leaseCoordinator, "leaseCoordinator");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.workerLifecycle = Objects.requireNonNull(workerLifecycle, "workerLifecycle");
        this.tokenLifetime = Objects.requireNonNull(tokenLifetime, "tokenLifetime");
        this.codingLifecycle = Objects.requireNonNull(codingLifecycle, "codingLifecycle");
    }

    /** Advances PREPARE and RUN under the one-time Claim Token before returning closed facts. */
    public TaskWorkerPreparedExecution prepare(ClaimReceipt receipt) {
        ClaimReceipt required = Objects.requireNonNull(receipt, "receipt");
        LeaseCommandScope scope = scope(required);
        TaskExecution preparing = leaseCoordinator.beginPreparing(
                new LeaseExecutionCommand(scope, required.taskExecutionVersion()));
        TaskTokenIssueResult token = null;
        Optional<CodingWorkspaceExecution> codingWorkspace = Optional.empty();
        try {
            PolicySnapshot policy = currentPolicy(preparing);
            Set<String> allowedTools = policy.allowedTools().stream()
                    .filter(CONTROLLED_TOOLS::contains)
                    .collect(Collectors.toUnmodifiableSet());
            token = tokenService.issue(new TaskTokenIssueCommand(
                    preparing.id(),
                    required.leaseId(),
                    allowedTools,
                    java.util.List.of(),
                    tokenLifetime));
            TaskTokenIssueResult issuedToken = token;
            InitializedRuntime initialized = transactionExecutor.required(
                    () -> initializeRuntime(preparing, required, issuedToken));
            ExecutionLease prepareLease = requiredLease(required.leaseId());
            codingWorkspace = codingLifecycle.prepare(preparing, prepareLease, policy);
            LeaseMutationResult running = leaseCoordinator.beginRun(new LeaseTransitionCommand(
                    scope, preparing.version(), prepareLease.version()));
            codingWorkspace.ifPresent(workspace -> codingLifecycle.activate(
                    workspace, running.execution(), running.lease()));
            TaskExecutionRuntimeFacts facts = transactionExecutor.required(() -> loadFacts(
                    running.execution(), running.lease(), initialized.session(), initialized.run(),
                    issuedToken));
            return new TaskWorkerPreparedExecution(
                    facts, scope, issuedToken, UUID.randomUUID(), codingWorkspace);
        } catch (RuntimeException failure) {
            codingWorkspace.ifPresent(codingLifecycle::abandon);
            cleanupFailedPreparation(scope, token, failure);
            throw failure;
        }
    }

    private InitializedRuntime initializeRuntime(
            TaskExecution preparing, ClaimReceipt receipt, TaskTokenIssueResult token) {
        Task task = requiredTask(preparing);
        PolicySnapshot policy = currentPolicy(preparing);
        Principal agent = principalRepository.findById(
                        registration.organizationId(), policy.executionPrincipal().principalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", policy.executionPrincipal().principalId()));
        AgentProfile profile = profileRepository.findById(
                        registration.organizationId(), policy.agentProfileId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentProfile", policy.agentProfileId()));
        if (profile.version() != policy.agentProfileVersion()) {
            throw new IllegalStateException(
                    "Current AgentProfile no longer matches the pinned policy version");
        }
        TaskAgentRuntimeSession session = sessionRepository.initializeIfAbsent(
                TaskAgentRuntimeSession.initializeTask(
                        task, preparing, profile, agent, timeProvider.now()));
        AgentRun run = runRepository.findActiveBySession(
                        registration.organizationId(), session.id())
                .orElseGet(() -> {
                    long nextSequence = runRepository.findByExecution(
                                    registration.organizationId(), preparing.id())
                            .stream()
                            .map(AgentRun::runSequence)
                            .max(Comparator.naturalOrder())
                            .orElse(0L) + 1L;
                    return runRepository.createNext(AgentRun.start(
                            AgentRunId.generate(), session, nextSequence, agent, timeProvider.now()));
                });
        // Constructing PREPARING facts here closes the Token and newly initialized runtime graph
        // before the RUN Lease transition can make the attempt externally observable as running.
        loadFacts(preparing, requiredLease(receipt.leaseId()), session, run, token);
        return new InitializedRuntime(session, run);
    }

    private TaskExecutionRuntimeFacts loadFacts(
            TaskExecution execution,
            ExecutionLease lease,
            TaskAgentRuntimeSession session,
            AgentRun run,
            TaskTokenIssueResult token) {
        Task task = requiredTask(execution);
        PolicySnapshot policy = currentPolicy(execution);
        var planning = execution.planningContext().orElseThrow();
        SafetyEnforcementOverlay overlay = overlayRepository.findByIdAndVersion(
                        registration.organizationId(),
                        planning.safetyOverlay().id(),
                        planning.safetyOverlay().version())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "SafetyEnforcementOverlay", planning.safetyOverlay().id()));
        Optional<PlanVersion> plan = planning.currentPlanVersionId().map(id ->
                planRepository.findById(registration.organizationId(), id)
                        .orElseThrow(() -> new AggregateNotFoundException("PlanVersion", id)));
        return new TaskExecutionRuntimeFacts(
                task,
                execution,
                Optional.empty(),
                lease,
                session,
                run,
                policy,
                overlay,
                plan,
                token.context());
    }

    private Task requiredTask(TaskExecution execution) {
        return taskRepository.findById(registration.organizationId(), execution.taskId())
                .orElseThrow(() -> new AggregateNotFoundException("Task", execution.taskId()));
    }

    private PolicySnapshot currentPolicy(TaskExecution execution) {
        var planning = execution.planningContext().orElseThrow(() -> new IllegalStateException(
                "Claimed TaskExecution has no planning context"));
        return policyRepository.findById(registration.organizationId(), planning.policySnapshotId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "PolicySnapshot", planning.policySnapshotId()));
    }

    private ExecutionLease requiredLease(io.crewscope.domain.task.ExecutionLeaseId leaseId) {
        return leaseRepository.findById(
                        registration.organizationId(), registration.environment(), leaseId)
                .orElseThrow(() -> new AggregateNotFoundException("ExecutionLease", leaseId));
    }

    private void cleanupFailedPreparation(
            LeaseCommandScope scope,
            TaskTokenIssueResult token,
            RuntimeException originalFailure) {
        if (token != null) {
            try {
                tokenService.revoke(new TaskTokenRevokeCommand(
                        token.token(), token.grant().version(), "TASK_PREPARATION_FAILED"));
            } catch (RuntimeException cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
        try {
            TaskExecution execution = executionRepository.findById(
                            registration.organizationId(),
                            requiredLease(scope.leaseId()).taskExecutionId())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TaskExecution", scope.leaseId()));
            ExecutionLease lease = requiredLease(scope.leaseId());
            if (lease.release().isEmpty()
                    && lease.owns(scope.ownership(), timeProvider.now())) {
                leaseCoordinator.release(LeaseReleaseCommand.simple(
                        new LeaseTransitionCommand(
                                scope, execution.version(), lease.version()),
                        ExecutionLeaseReleaseReason.WORKER_SHUTDOWN));
            }
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    CodingWorkspaceExecutionLifecycle codingLifecycle() {
        return codingLifecycle;
    }

    private LeaseCommandScope scope(ClaimReceipt receipt) {
        RuntimeWorkerIdentity identity = workerLifecycle.identity();
        if (!receipt.runtimeId().equals(identity.runtimeId())
                || !receipt.workerId().equals(identity.workerId())) {
            throw new IllegalArgumentException(
                    "Claim receipt does not belong to the configured Runtime Worker");
        }
        return new LeaseCommandScope(
                registration.organizationId(),
                registration.environment(),
                receipt.leaseId(),
                receipt.ownership());
    }

    private record InitializedRuntime(TaskAgentRuntimeSession session, AgentRun run) {}
}
