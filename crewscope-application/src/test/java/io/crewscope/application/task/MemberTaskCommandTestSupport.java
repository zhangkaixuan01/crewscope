package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.execution.AgentRunResumeCommand;
import io.crewscope.application.execution.DurableAgentRunResumeService;
import io.crewscope.application.execution.TaskApprovalInterruptTokens;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.task.event.MemberTaskCommandAccepted;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;


/**
 * The durable Task control fixture shared by the M3-A04 command contract and the M9 availability
 * reconciliation.
 *
 * <p>Both tests need the same story: one WorkItem with an OWNER and an EXECUTOR responsibility, one
 * Task with a planning context, and a TaskExecution the test can walk to any status through the
 * aggregate's own transitions. Sharing it keeps the command's preconditions and the projection's
 * verdicts anchored to identical facts, which is the only way a disagreement between them can be
 * read as a real one.
 */
class MemberTaskCommandTestSupport {
static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T10:00:00Z");
static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-15T10:01:00Z");

final OrganizationId organizationId = OrganizationId.generate();
final TeamId teamId = TeamId.generate();
final WorkspaceId workspaceId = WorkspaceId.generate();
final WorkProjectId projectId = WorkProjectId.generate();
final WorkItemScope scope = new WorkItemScope(
        organizationId, teamId, workspaceId, projectId);
final Principal owner = user("Owner");
final Principal executor = personalAgent(owner);
final Principal observer = user("Observer");
final WorkItem workItem = WorkItem.reconstitute(
        WorkItemId.generate(),
        scope,
        new WorkItemKey("CRW-304"),
        "Durable member controls",
        WorkItemStatus.READY,
        4,
        AuditMetadata.createdBy(owner.id(), NOW));
final ResponsibilityAssignment ownerAssignment = assignment(
        ResponsibilityRole.OWNER, owner, Optional.of(TeamMemberId.generate()));
final ResponsibilityAssignment executorAssignment = assignment(
        ResponsibilityRole.EXECUTOR, executor, Optional.empty());
final AgentProfile profile = AgentProfile.reconstitute(
        AgentProfileId.generate(),
        WorkspaceScope.team(organizationId, teamId),
        workspaceId,
        executor.id(),
        Optional.of(TeamMemberId.generate()),
        AgentProfileType.PERSONAL,
        true,
        AgentProfileStatus.ACTIVE,
        2,
        AuditMetadata.createdBy(owner.id(), NOW));

final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
final ResponsibilityAssignmentRepository assignments =
        mock(ResponsibilityAssignmentRepository.class);
final PrincipalRepository principals = mock(PrincipalRepository.class);
final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
final ProviderBindingResolver bindings = mock(ProviderBindingResolver.class);
final TaskRepository tasks = mock(TaskRepository.class);
final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
final SafetyEnforcementOverlayRepository overlays =
        mock(SafetyEnforcementOverlayRepository.class);
final AgentRunRepository runs = mock(AgentRunRepository.class);
final AgentInterruptRepository interrupts = mock(AgentInterruptRepository.class);
final DurableAgentRunResumeService resumeService =
        mock(DurableAgentRunResumeService.class);
final DomainEventStore events = mock(DomainEventStore.class);
final TaskEventRepository taskEvents = mock(TaskEventRepository.class);
final OutboxRepository outbox = mock(OutboxRepository.class);
final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
final AtomicReference<Task> taskState = new AtomicReference<>();
final AtomicReference<TaskExecution> executionState = new AtomicReference<>();
final AtomicReference<PolicySnapshot> policyState = new AtomicReference<>();
MemberTaskCommandService service;

@BeforeEach
void setUp() {
    reset();
    when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
    when(accessPolicy.requireVisibleWorkItem(
                    any(), any(), any(), any(), any()))
            .thenReturn(workItem);
    when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
            .thenReturn(List.of(ownerAssignment, executorAssignment));
    when(tasks.findById(any(), any()))
            .thenAnswer(ignored -> Optional.of(taskState.get()));
    when(tasks.findByIdForUpdate(any(), any()))
            .thenAnswer(ignored -> Optional.of(taskState.get()));
    when(tasks.update(any())).thenAnswer(invocation -> {
        Task value = invocation.getArgument(0);
        taskState.set(value);
        return value;
    });
    when(executions.findById(any(), any()))
            .thenAnswer(ignored -> Optional.of(executionState.get()));
    when(executions.findByIdForUpdate(any(), any()))
            .thenAnswer(ignored -> Optional.of(executionState.get()));
    when(executions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(executions.update(any())).thenAnswer(invocation -> {
        TaskExecution value = invocation.getArgument(0);
        executionState.set(value);
        return value;
    });
    when(policies.findById(any(), any()))
            .thenAnswer(ignored -> Optional.of(policyState.get()));
    when(policies.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(overlays.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(principals.findById(organizationId, executor.id())).thenReturn(Optional.of(executor));
    when(profiles.findById(organizationId, profile.id())).thenReturn(Optional.of(profile));
    startService();
}

/**
 * Rebuilds the Task, its first attempt and its policy, and points every mock at the new aggregates.
 *
 * <p>A matrix cell must be judged from fresh facts: the commands under test mutate the Task and the
 * attempt, so reusing one fixture across cells would let an earlier cell decide a later one's verdict.
 */
Fixture reset() {
    Fixture fixture = fixture(3);
    taskState.set(fixture.task());
    executionState.set(fixture.execution());
    policyState.set(fixture.policy());
    return fixture;
}

private void startService() {
    service = new MemberTaskCommandService(
            accessPolicy,
            assignments,
            principals,
            profiles,
            bindings,
            tasks,
            executions,
            policies,
            overlays,
            runs,
            interrupts,
            resumeService,
            events,
            taskEvents,
            outbox,
            receipts,
            new DirectTransactions(),
            () -> LATER);
}

Fixture fixture(int maxAttempts) {
    Task task = Task.create(
            TaskId.generate(),
            workItem,
            TaskSource.fromWorkItem(workItem),
            new TaskBrief("Control a durable Task", List.of("Pause and resume safely")),
            TaskResponsibilitySnapshot.capture(
                    workItem, List.of(ownerAssignment, executorAssignment), NOW),
            owner,
            NOW);
    TaskExecution created = TaskExecution.firstAttempt(
            TaskExecutionId.generate(),
            task,
            maxAttempts,
            TaskExecutionPriority.NORMAL,
            NOW,
            owner,
            NOW);
    PolicySnapshot policy = PolicySnapshot.initial(
            PolicySnapshotId.generate(),
            task,
            created,
            executor,
            new PolicyPackReference(PolicyPackId.generate(), 1),
            profile.id(),
            profile.version(),
            Set.of(ExecutionCapability.PLAN),
            Set.of("fixture_execute"),
            Set.of(),
            new PolicyBudget(10_000, 8, 16, 300),
            owner,
            NOW);
    SafetyEnforcementOverlay overlay = SafetyEnforcementOverlay.unrestricted(
            SafetyEnforcementOverlayId.generate(), task, created, owner, NOW);
    TaskExecution planned = created.initializePlanningContext(
            policy, overlay, created.version(), owner, NOW);
    TaskExecution ready = planned.markReady(planned.version(), owner, NOW);
    Task active = task.switchCurrentExecution(
            Optional.empty(), ready.id(), task.version(), owner, NOW);
    return new Fixture(active, created, ready, policy);
}

TaskExecution running(TaskExecution ready) {
    TaskExecution claimed = ready.claim(ready.version(), executor, LATER);
    TaskExecution preparing = claimed.beginPreparing(claimed.version(), executor, LATER);
    return preparing.beginRunning(preparing.version(), executor, LATER);
}

TaskExecution failed(TaskExecution ready, boolean retryable) {
    TaskExecution running = running(ready);
    return running.fail(
            new TaskExecutionFailure(
                    retryable
                            ? TaskExecutionFailureClass.TRANSIENT
                            : TaskExecutionFailureClass.VALIDATION,
                    retryable ? "TRANSIENT_FAILURE" : "VALIDATION_FAILURE"),
            running.version(),
            executor,
            LATER);
}

TeamCommandContext context(Principal actor, String key) {
    return new TeamCommandContext(
            new TeamAccessContext(actor, false),
            IdempotencyKey.from("m3-a04/" + key),
            UUID.randomUUID(),
            Optional.empty());
}

Principal user(String name) {
    return Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            name,
            Optional.empty(),
            PrincipalVisibility.TEAM,
            NOW);
}

Principal personalAgent(Principal ownerPrincipal) {
    return Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.PERSONAL_AGENT,
            Optional.of(ownerPrincipal.id()),
            "Owner Agent",
            Optional.empty(),
            PrincipalVisibility.PRIVATE,
            NOW);
}

ResponsibilityAssignment assignment(
        ResponsibilityRole role, Principal actor, Optional<TeamMemberId> memberId) {
    return ResponsibilityAssignment.reconstitute(
            ResponsibilityAssignmentId.generate(),
            scope,
            workItem.id(),
            role,
            actor.id(),
            actor.type(),
            memberId,
            ResponsibilityAssignmentStatus.ACTIVE,
            owner.id(),
            NOW,
            NOW,
            Optional.empty(),
            Optional.empty(),
            0,
            AuditMetadata.createdBy(owner.id(), NOW));
}

@SuppressWarnings({"rawtypes", "unchecked"})
static ArgumentCaptor<DomainEventEnvelope<?>> eventCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(DomainEventEnvelope.class);
}

record Fixture(
        Task task, TaskExecution created, TaskExecution execution, PolicySnapshot policy) {}

static final class DirectTransactions implements TransactionExecutor {
    @Override
    public <T> T required(Supplier<T> operation) {
        return operation.get();
    }
}
}
