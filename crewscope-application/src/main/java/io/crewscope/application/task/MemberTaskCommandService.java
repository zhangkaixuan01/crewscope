package io.crewscope.application.task;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.execution.AgentRunResumeCommand;
import io.crewscope.application.execution.DurableAgentRunResumeService;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.TaskApprovalInterruptTokens;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.task.event.MemberTaskCommandAccepted;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomically accepts member Task controls and preserves retry authorization evidence. */
public final class MemberTaskCommandService {

    private static final String TASK_AGGREGATE = "TASK";
    private static final String TASK_EXECUTION_AGGREGATE = "TASK_EXECUTION";
    private static final EnumSet<TaskExecutionStatus> IMMEDIATELY_CANCELLABLE = EnumSet.of(
            TaskExecutionStatus.CREATED,
            TaskExecutionStatus.READY,
            TaskExecutionStatus.WAITING,
            TaskExecutionStatus.PAUSED,
            TaskExecutionStatus.RECOVERING);

    private final WorkItemAccessPolicy accessPolicy;
    private final ResponsibilityAssignmentRepository assignmentRepository;
    private final PrincipalRepository principalRepository;
    private final AgentProfileRepository profileRepository;
    private final ProviderBindingResolver bindingResolver;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final PolicySnapshotRepository policyRepository;
    private final SafetyEnforcementOverlayRepository overlayRepository;
    private final AgentRunRepository runRepository;
    private final AgentInterruptRepository interruptRepository;
    private final DurableAgentRunResumeService resumeService;
    private final DomainEventStore eventStore;
    private final TaskEventRepository taskEventRepository;
    private final OutboxRepository outboxRepository;
    private final CommandReceiptStore receiptStore;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;

    public MemberTaskCommandService(
            WorkItemAccessPolicy accessPolicy,
            ResponsibilityAssignmentRepository assignmentRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository profileRepository,
            ProviderBindingResolver bindingResolver,
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            AgentRunRepository runRepository,
            AgentInterruptRepository interruptRepository,
            DurableAgentRunResumeService resumeService,
            DomainEventStore eventStore,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.assignmentRepository = Objects.requireNonNull(
                assignmentRepository, "assignmentRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.overlayRepository = Objects.requireNonNull(overlayRepository, "overlayRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.interruptRepository = Objects.requireNonNull(interruptRepository, "interruptRepository");
        this.resumeService = Objects.requireNonNull(resumeService, "resumeService");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.taskEventRepository = Objects.requireNonNull(
                taskEventRepository, "taskEventRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<MemberTaskCommandResult> pause(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            MemberTaskControlCommand command) {
        return control(context, teamId, taskId, executionId, MemberTaskCommandOperation.PAUSE, command);
    }

    public CommandExecution<MemberTaskCommandResult> cancel(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            MemberTaskControlCommand command) {
        return control(context, teamId, taskId, executionId, MemberTaskCommandOperation.CANCEL, command);
    }

    public CommandExecution<MemberTaskCommandResult> resume(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            RetryTaskCommand command) {
        return transactionExecutor.required(() -> execute(
                context,
                teamId,
                taskId,
                executionId,
                MemberTaskCommandOperation.RESUME,
                command.expectedExecutionVersion(),
                "",
                (state, commandId, occurredAt) -> resume(state, commandId, context, occurredAt)));
    }

    public CommandExecution<MemberTaskCommandResult> retry(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            RetryTaskCommand command) {
        return transactionExecutor.required(() -> execute(
                context,
                teamId,
                taskId,
                executionId,
                MemberTaskCommandOperation.RETRY,
                command.expectedExecutionVersion(),
                "",
                (state, commandId, occurredAt) -> retry(state, context.access().actor(), occurredAt)));
    }

    private CommandExecution<MemberTaskCommandResult> control(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            MemberTaskCommandOperation operation,
            MemberTaskControlCommand command) {
        MemberTaskControlCommand required = Objects.requireNonNull(command, "command");
        return transactionExecutor.required(() -> execute(
                context,
                teamId,
                taskId,
                executionId,
                operation,
                required.expectedExecutionVersion(),
                required.reason(),
                (state, commandId, occurredAt) -> operation == MemberTaskCommandOperation.PAUSE
                        ? pause(state, context.access().actor(), required.reason(), occurredAt)
                        : cancel(state, context.access().actor(), required.reason(), occurredAt)));
    }

    private CommandExecution<MemberTaskCommandResult> execute(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            MemberTaskCommandOperation operation,
            long expectedExecutionVersion,
            String reason,
            Mutation mutation) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        TaskId task = Objects.requireNonNull(taskId, "taskId");
        TaskExecutionId execution = Objects.requireNonNull(executionId, "executionId");
        MemberTaskCommandOperation requiredOperation = Objects.requireNonNull(operation, "operation");
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        UtcTimestamp occurredAt = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        String commandType = "MEMBER_TASK_" + requiredOperation.name();
        CommandRequestHash requestHash = CommandRequestHash.sha256(
                commandType,
                trusted.access().actor().id().toString(),
                team.toString(),
                task.toString(),
                execution.toString(),
                Long.toString(expectedExecutionVersion),
                reason,
                trusted.causationId().map(UUID::toString).orElse(""));
        CommandReservation reservation = receiptStore.reserve(new CommandReservationRequest(
                organizationId,
                trusted.idempotencyKey(),
                commandType,
                requestHash,
                commandId,
                trusted.correlationId(),
                occurredAt));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }

        State state = requireState(
                trusted, organizationId, team, task, execution, expectedExecutionVersion);
        MemberTaskCommandResult result = mutation.apply(state, commandId, occurredAt);
        long aggregateVersion = requiredOperation == MemberTaskCommandOperation.RETRY
                ? result.task().version()
                : result.targetExecution().version();
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<MemberTaskCommandAccepted> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(commandType + "_ACCEPTED"),
                SchemaVersion.V1,
                organizationId,
                Optional.of(result.task().scope().teamId()),
                Optional.of(result.task().scope().workspaceId()),
                AggregateReference.of(
                        requiredOperation == MemberTaskCommandOperation.RETRY
                                ? TASK_AGGREGATE
                                : TASK_EXECUTION_AGGREGATE,
                        requiredOperation == MemberTaskCommandOperation.RETRY
                                ? result.task().id()
                                : result.targetExecution().id()),
                aggregateVersion,
                EventActor.principal(EventActorType.USER, trusted.access().actor().id()),
                trusted.correlationId(),
                trusted.causationId(),
                Optional.of(trusted.idempotencyKey().value()),
                occurredAt,
                payload(result));
        eventStore.append(event);
        taskEventRepository.append(
                TaskEventContext.execution(result.task().id(), result.targetExecution().id()),
                event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, aggregateVersion, trusted.correlationId());
        receiptStore.complete(organizationId, trusted.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(result, receipt);
    }

    private State requireState(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            long expectedExecutionVersion) {
        Task visible = taskRepository.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        WorkItem workItem = accessPolicy.requireVisibleWorkItem(
                context.access(),
                organizationId,
                teamId,
                visible.scope().projectId(),
                visible.workItemId());
        Task task = taskRepository.findByIdForUpdate(organizationId, taskId)
                .filter(value -> value.scope().equals(workItem.scope()))
                .filter(value -> value.workItemId().equals(workItem.id()))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        TaskExecution execution = executionRepository
                .findByIdForUpdate(organizationId, executionId)
                .filter(value -> value.scope().equals(task.scope()))
                .filter(value -> value.taskId().equals(task.id()))
                .orElseThrow(() -> new AggregateNotFoundException("TaskExecution", executionId));
        if (execution.version() != expectedExecutionVersion) {
            throw new OptimisticLockConflictException(
                    "TaskExecution", execution.id(), expectedExecutionVersion, execution.version());
        }
        if (task.currentExecutionId().filter(execution.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "task.currentExecutionId", "must identify the current effective attempt");
        }
        List<ResponsibilityAssignment> assignments = List.copyOf(
                assignmentRepository.findActiveByWorkItem(organizationId, task.workItemId()));
        requireControlAuthority(context.access().actor(), assignments);
        return new State(task, execution, workItem, assignments);
    }

    private MemberTaskCommandResult pause(
            State state, Principal actor, String reason, UtcTimestamp occurredAt) {
        TaskExecution paused = executionRepository.update(state.execution().requestPause(
                reason, state.execution().version(), actor, occurredAt));
        return new MemberTaskCommandResult(
                MemberTaskCommandOperation.PAUSE, state.task(), paused, Optional.empty());
    }

    private MemberTaskCommandResult cancel(
            State state, Principal actor, String reason, UtcTimestamp occurredAt) {
        TaskExecution requested = executionRepository.update(state.execution().requestCancel(
                reason, state.execution().version(), actor, occurredAt));
        TaskExecution converged = IMMEDIATELY_CANCELLABLE.contains(state.execution().status())
                ? executionRepository.update(requested.acknowledgeCancelled(
                        requested.version(), actor, occurredAt))
                : requested;
        Task cancelled = taskRepository.update(state.task().cancel(
                reason, state.task().version(), actor, occurredAt));
        if (state.execution().status() == TaskExecutionStatus.PAUSED) {
            cancelPendingInterrupt(state, actor, occurredAt);
        }
        return new MemberTaskCommandResult(
                MemberTaskCommandOperation.CANCEL, cancelled, converged, Optional.empty());
    }

    private void cancelPendingInterrupt(State state, Principal actor, UtcTimestamp occurredAt) {
        currentInterruptedRun(state).flatMap(run -> interruptRepository.findPendingByRun(
                        state.task().scope().organizationId(), run.id()))
                .ifPresent(interrupt -> {
                    if (interrupt.kind() == AgentInterruptKind.PAUSE) {
                        interruptRepository.update(interrupt.cancel(
                                interrupt.version(), actor, occurredAt));
                    }
                });
    }

    private MemberTaskCommandResult resume(
            State state,
            UUID commandId,
            TeamCommandContext context,
            UtcTimestamp occurredAt) {
        boolean paused = state.execution().status() == TaskExecutionStatus.PAUSED;
        boolean approvalWaiting = state.execution().status() == TaskExecutionStatus.WAITING
                && state.execution().waiting()
                        .map(io.crewscope.domain.task.TaskExecutionWaiting::reason)
                        .filter(io.crewscope.domain.task.TaskExecutionWaitReason.CONFIRMATION::equals)
                        .isPresent();
        if (!paused && !approvalWaiting) {
            throw new InvalidStateTransitionException(
                    "TaskExecution",
                    state.execution().id(),
                    state.execution().status(),
                    TaskExecutionStatus.READY);
        }
        AgentRun run = currentInterruptedRun(state).orElseThrow(() -> new DomainValidationException(
                "taskResume.agentRun", "must reference the current interrupted AgentRun"));
        AgentInterruptKind expectedKind = paused
                ? AgentInterruptKind.PAUSE
                : AgentInterruptKind.APPROVAL;
        AgentInterrupt interrupt = interruptRepository.findPendingByRun(
                        state.task().scope().organizationId(), run.id())
                .filter(value -> value.kind() == expectedKind)
                .orElseThrow(() -> new DomainValidationException(
                        "taskResume.agentInterrupt",
                        "must reference the pending Pause or plan Approval interrupt"));
        ExecutionInterruptToken token = paused
                ? new ExecutionInterruptToken(TaskControlRequestIds.from(
                        state.execution().id(), state.execution().controlRequest().orElseThrow())
                        .toString())
                : TaskApprovalInterruptTokens.from(
                        state.execution().id(), run.id(), interrupt.segmentSequence());
        resumeService.resume(new AgentRunResumeCommand(
                state.task().scope().organizationId(),
                run.id(),
                interrupt.id(),
                commandId,
                token,
                RuntimeContentHash.sha256("MEMBER_TASK_RESUME|" + expectedKind + "|"
                        + state.task().id() + "|"
                        + state.execution().id() + "|" + context.access().actor().id()),
                context.access().actor().id(),
                context.correlationId(),
                context.causationId()));
        TaskExecution ready = executionRepository.update(state.execution().requeue(
                occurredAt,
                state.execution().version(),
                context.access().actor(),
                occurredAt));
        return new MemberTaskCommandResult(
                MemberTaskCommandOperation.RESUME, state.task(), ready, Optional.empty());
    }

    private MemberTaskCommandResult retry(
            State state, Principal actor, UtcTimestamp occurredAt) {
        TaskExecution parent = state.execution();
        if (!parent.canRetry()) {
            throw new InvalidStateTransitionException(
                    "TaskExecution", parent.id(), parent.status(), TaskExecutionStatus.CREATED);
        }
        PolicySnapshot parentPolicy = parent.planningContext()
                .flatMap(context -> policyRepository.findById(
                        state.task().scope().organizationId(), context.policySnapshotId()))
                .orElseThrow(() -> new DomainValidationException(
                        "taskRetry.policySnapshot", "must resolve the failed attempt current policy"));
        Principal executor = requireRetryAuthorization(state, parentPolicy);
        Task failedTask = state.task().status() == TaskStatus.FAILED
                ? state.task()
                : taskRepository.update(state.task().synchronizeStatus(
                        parent.id(), TaskStatus.FAILED, state.task().version(), actor, occurredAt));
        TaskExecution created = executionRepository.create(TaskExecution.retry(
                TaskExecutionId.generate(),
                failedTask,
                parent,
                parent.priority(),
                occurredAt,
                actor,
                occurredAt));
        PolicySnapshot policy = policyRepository.create(PolicySnapshot.initial(
                PolicySnapshotId.generate(),
                failedTask,
                created,
                executor,
                parentPolicy.policyPack(),
                parentPolicy.agentProfileId(),
                parentPolicy.agentProfileVersion(),
                parentPolicy.capabilities(),
                parentPolicy.allowedTools(),
                parentPolicy.providerBindingIds(),
                parentPolicy.budget(),
                actor,
                occurredAt));
        SafetyEnforcementOverlay overlay = overlayRepository.create(
                SafetyEnforcementOverlay.unrestricted(
                        SafetyEnforcementOverlayId.generate(),
                        failedTask,
                        created,
                        actor,
                        occurredAt));
        TaskExecution planned = executionRepository.update(created.initializePlanningContext(
                policy, overlay, created.version(), actor, occurredAt));
        TaskExecution ready = executionRepository.update(
                planned.markReady(planned.version(), actor, occurredAt));
        Task active = taskRepository.update(failedTask.switchCurrentExecution(
                Optional.of(parent.id()), ready.id(), failedTask.version(), actor, occurredAt));
        return new MemberTaskCommandResult(
                MemberTaskCommandOperation.RETRY, active, parent, Optional.of(ready));
    }

    private Principal requireRetryAuthorization(State state, PolicySnapshot policy) {
        var expected = policy.executionPrincipal();
        ResponsibilityAssignment currentAssignment = state.assignments().stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.EXECUTOR)
                .filter(value -> value.id().equals(expected.assignmentId()))
                .filter(value -> value.version() == expected.assignmentVersion())
                .filter(value -> value.actorPrincipalId().equals(expected.principalId()))
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException(
                        "retry a Task after its Executor responsibility changed"));
        Principal executor = principalRepository.findById(
                        state.task().scope().organizationId(), currentAssignment.actorPrincipalId())
                .filter(Principal::canAct)
                .orElseThrow(() -> new PolicyDeniedException(
                        "retry a Task with an inactive Executor"));
        AgentProfile profile = profileRepository.findById(
                        state.task().scope().organizationId(), policy.agentProfileId())
                .filter(value -> value.status() == AgentProfileStatus.ACTIVE)
                .filter(value -> value.version() == policy.agentProfileVersion())
                .filter(value -> value.agentPrincipalId().equals(executor.id()))
                .orElseThrow(() -> new PolicyDeniedException(
                        "retry a Task with a changed Agent Profile"));
        if (!profile.workspaceId().equals(state.task().scope().workspaceId())) {
            throw new PolicyDeniedException("retry a Task outside the Agent Workspace");
        }
        policy.providerBindingIds().stream()
                .sorted(Comparator.comparing(ProviderBindingId::toString))
                .forEach(id -> requireCurrentBinding(state, executor, id));
        return executor;
    }

    private void requireCurrentBinding(
            State state, Principal executor, ProviderBindingId bindingId) {
        ProviderBindingCandidate candidate = bindingResolver.resolveCurrent(
                        state.task().scope().organizationId(), bindingId)
                .orElseThrow(() -> new PolicyDeniedException(
                        "retry a Task with a revoked Provider Binding"));
        ProviderBinding binding = candidate.binding();
        boolean targetMatches = binding.target().teamId().equals(state.task().scope().teamId())
                && binding.target().workspaceId().equals(state.task().scope().workspaceId())
                && (binding.target().type() == ProviderBindingTargetType.WORKSPACE
                        || binding.target().workProjectId()
                                .filter(state.task().scope().projectId()::equals)
                                .isPresent());
        boolean ownerMatches = binding.owner().type() != ProviderOwnerType.USER
                || executor.ownerPrincipalId().equals(binding.owner().userPrincipalId());
        if (!targetMatches || !ownerMatches) {
            throw new PolicyDeniedException("retry a Task with an unauthorized Provider Binding");
        }
    }

    private Optional<AgentRun> currentInterruptedRun(State state) {
        return runRepository.findByExecution(
                        state.task().scope().organizationId(), state.execution().id())
                .stream()
                .filter(value -> value.status() == AgentRunStatus.INTERRUPTED)
                .max(Comparator.comparingLong(AgentRun::runSequence));
    }

    private static void requireControlAuthority(
            Principal actor, List<ResponsibilityAssignment> assignments) {
        boolean authorized = assignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.OWNER
                        || value.role() == ResponsibilityRole.EXECUTOR)
                .anyMatch(value -> value.actorPrincipalId().equals(actor.id()));
        if (!authorized) {
            throw new PolicyDeniedException("control this Task");
        }
    }

    private static MemberTaskCommandAccepted payload(MemberTaskCommandResult result) {
        Optional<TaskExecution> successor = result.successorExecution();
        TaskExecution visibleExecution = successor.orElse(result.targetExecution());
        return new MemberTaskCommandAccepted(
                result.task().id().value(),
                result.targetExecution().id().value(),
                result.targetExecution().attempt(),
                result.operation().name(),
                result.task().status().name(),
                visibleExecution.status().name(),
                successor.map(value -> value.id().value()),
                successor.map(TaskExecution::attempt));
    }

    @FunctionalInterface
    private interface Mutation {
        MemberTaskCommandResult apply(State state, UUID commandId, UtcTimestamp occurredAt);
    }

    private record State(
            Task task,
            TaskExecution execution,
            WorkItem workItem,
            List<ResponsibilityAssignment> assignments) {}
}
