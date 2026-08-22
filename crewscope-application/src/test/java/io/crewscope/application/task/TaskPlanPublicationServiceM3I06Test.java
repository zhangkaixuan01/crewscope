package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
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
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PlanChangeReason;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanStepType;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.ProposedPlan;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TodoStatus;
import io.crewscope.domain.task.TodoSummaryItem;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Application transaction tests for M3-I06 PlanVersion selection and Step materialization. */
class TaskPlanPublicationServiceM3I06Test {

    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-15T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-15T09:10:00Z"), ZoneOffset.UTC);

    @Test
    void reloadsCurrentFactsAndAtomicallySelectsPlanBeforeCreatingSteps() {
        Fixture fixture = new Fixture();
        AtomicInteger transactions = new AtomicInteger();
        TransactionExecutor transaction = new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                transactions.incrementAndGet();
                return operation.get();
            }
        };
        when(fixture.planRepository.create(any(PlanVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.executionRepository.update(any(TaskExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.stepRepository.create(any(StepExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TaskPlanPublicationService service = fixture.service(transaction);

        TaskPlanPublicationResult result = service.publish(fixture.command(fixture.profileId));

        assertEquals(1, transactions.get());
        assertEquals(1, result.planVersion().revision());
        assertEquals(3, result.stepExecutions().size());
        assertEquals(Optional.of(result.planVersion().id()),
                result.taskExecution().planningContext().orElseThrow().currentPlanVersionId());
        assertEquals(List.of(1, 2, 3), result.stepExecutions().stream()
                .map(StepExecution::sequence)
                .toList());
        InOrder order = inOrder(
                fixture.planRepository,
                fixture.executionRepository,
                fixture.stepRepository);
        order.verify(fixture.planRepository).create(any(PlanVersion.class));
        order.verify(fixture.executionRepository).update(any(TaskExecution.class));
        order.verify(fixture.stepRepository, org.mockito.Mockito.times(3))
                .create(any(StepExecution.class));
    }

    @Test
    void rejectsStaleProfileAndAnyProviderWriteToolBeforePublication() {
        Fixture fixture = new Fixture();
        TaskPlanPublicationService service = fixture.service(new DirectTransactionExecutor());

        assertThrows(DomainValidationException.class, () ->
                service.publish(fixture.command(AgentProfileId.generate())));
        verify(fixture.planRepository, never()).create(any());

        ProposedPlan unsafe = ProposedPlan.of(
                "# Unsafe",
                List.of(new PlanStep(
                        "unsafe",
                        1,
                        "Write Provider",
                        PlanStepType.VALIDATION,
                        Set.of(),
                        Set.of(ExecutionCapability.PLAN),
                        Set.of("github.write"),
                        true)));
        TaskPlanPublicationCommand command = fixture.command(fixture.profileId);
        TaskPlanPublicationCommand unsafeCommand = new TaskPlanPublicationCommand(
                command.organizationId(),
                command.taskId(),
                command.executionId(),
                command.expectedExecutionVersion(),
                command.expectedCurrentPlanVersionId(),
                command.policySnapshotId(),
                command.policySnapshotHash(),
                command.safetyOverlay(),
                command.agentProfileId(),
                command.agentProfileVersion(),
                command.changeReason(),
                unsafe,
                List.of(),
                command.maxStepRunAttempts());

        assertThrows(DomainValidationException.class, () -> service.publish(unsafeCommand));
        verify(fixture.planRepository, never()).create(any());
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {

        @Override
        public <T> T required(java.util.function.Supplier<T> operation) {
            return operation.get();
        }
    }

    private static final class Fixture {

        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        private final Principal owner = principal("Owner", PrincipalType.USER, Optional.empty());
        private final Principal executor = principal(
                "Executor", PrincipalType.TEAM_AGENT, Optional.of(owner.id()));
        private final WorkItem workItem = WorkItem.reconstitute(
                WorkItemId.generate(),
                scope,
                new WorkItemKey("M3I06-1"),
                "Controlled fixture",
                WorkItemStatus.READY,
                0,
                AuditMetadata.createdBy(owner.id(), CREATED_AT));
        private final ResponsibilityAssignment ownerAssignment = assignment(
                ResponsibilityRole.OWNER, owner);
        private final ResponsibilityAssignment executorAssignment = assignment(
                ResponsibilityRole.EXECUTOR, executor);
        private final Task createdTask = Task.create(
                TaskId.generate(),
                workItem,
                TaskSource.fromWorkItem(workItem),
                TaskResponsibilitySnapshot.capture(
                        workItem,
                        List.of(ownerAssignment, executorAssignment),
                        CREATED_AT),
                owner,
                CREATED_AT);
        private final TaskExecution rawExecution = TaskExecution.firstAttempt(
                TaskExecutionId.generate(),
                createdTask,
                3,
                TaskExecutionPriority.NORMAL,
                CREATED_AT,
                owner,
                CREATED_AT);
        private final Task task = createdTask.switchCurrentExecution(
                Optional.empty(), rawExecution.id(), 0, owner, CREATED_AT);
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final PolicySnapshot policy = PolicySnapshot.initial(
                PolicySnapshotId.generate(),
                task,
                rawExecution,
                executor,
                new PolicyPackReference(PolicyPackId.generate(), 1),
                profileId,
                7,
                Set.of(ExecutionCapability.PLAN, ExecutionCapability.STRUCTURED_OUTPUT),
                TaskPlanPublicationService.M3_CONTROLLED_TOOLS,
                Set.of(),
                new PolicyBudget(10_000, 20, 20, 60),
                owner,
                CREATED_AT);
        private final SafetyEnforcementOverlay overlay = SafetyEnforcementOverlay.unrestricted(
                SafetyEnforcementOverlayId.generate(), task, rawExecution, owner, CREATED_AT);
        private final TaskExecution execution = rawExecution.initializePlanningContext(
                policy, overlay, 0, owner, CREATED_AT);

        private final TaskRepository taskRepository = mock(TaskRepository.class);
        private final TaskExecutionRepository executionRepository =
                mock(TaskExecutionRepository.class);
        private final PlanVersionRepository planRepository = mock(PlanVersionRepository.class);
        private final StepExecutionRepository stepRepository = mock(StepExecutionRepository.class);
        private final PolicySnapshotRepository policyRepository = mock(PolicySnapshotRepository.class);
        private final SafetyEnforcementOverlayRepository safetyRepository =
                mock(SafetyEnforcementOverlayRepository.class);
        private final PrincipalRepository principalRepository = mock(PrincipalRepository.class);

        private Fixture() {
            when(taskRepository.findById(scope.organizationId(), task.id()))
                    .thenReturn(Optional.of(task));
            when(executionRepository.findById(scope.organizationId(), execution.id()))
                    .thenReturn(Optional.of(execution));
            when(policyRepository.findById(scope.organizationId(), policy.id()))
                    .thenReturn(Optional.of(policy));
            when(safetyRepository.findByIdAndVersion(
                            scope.organizationId(), overlay.id(), overlay.version()))
                    .thenReturn(Optional.of(overlay));
            when(principalRepository.findById(scope.organizationId(), executor.id()))
                    .thenReturn(Optional.of(executor));
        }

        private TaskPlanPublicationService service(TransactionExecutor transaction) {
            return new TaskPlanPublicationService(
                    taskRepository,
                    executionRepository,
                    planRepository,
                    stepRepository,
                    policyRepository,
                    safetyRepository,
                    principalRepository,
                    transaction,
                    CLOCK);
        }

        private TaskPlanPublicationCommand command(AgentProfileId candidateProfileId) {
            ProposedPlan candidate = ProposedPlan.of(
                    "# Controlled Task Plan",
                    List.of(
                            step("inspect", 1, PlanStepType.ANALYSIS, Set.of(), "fixture_inspect"),
                            step("execute", 2, PlanStepType.IMPLEMENTATION, Set.of("inspect"),
                                    "fixture_execute"),
                            step("validate", 3, PlanStepType.VALIDATION, Set.of("execute"),
                                    "fixture_validate")));
            return new TaskPlanPublicationCommand(
                    scope.organizationId(),
                    task.id(),
                    execution.id(),
                    execution.version(),
                    Optional.empty(),
                    policy.id(),
                    policy.snapshotHash(),
                    overlay.reference(),
                    candidateProfileId,
                    7,
                    PlanChangeReason.INITIAL_PLAN,
                    candidate,
                    List.of(new TodoSummaryItem(
                            "[inspect] Inspect input",
                            TodoStatus.IN_PROGRESS,
                            Optional.of("high"),
                            Optional.of("inspect"))),
                    2);
        }

        private PlanStep step(
                String key,
                int sequence,
                PlanStepType type,
                Set<String> dependencies,
                String tool) {
            return new PlanStep(
                    key,
                    sequence,
                    key,
                    type,
                    dependencies,
                    Set.of(type == PlanStepType.VALIDATION
                            ? ExecutionCapability.STRUCTURED_OUTPUT
                            : ExecutionCapability.PLAN),
                    Set.of(tool),
                    true);
        }

        private Principal principal(
                String name, PrincipalType type, Optional<PrincipalId> ownerId) {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    type,
                    ownerId,
                    name,
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    CREATED_AT);
        }

        private ResponsibilityAssignment assignment(
                ResponsibilityRole role, Principal principal) {
            return ResponsibilityAssignment.reconstitute(
                    ResponsibilityAssignmentId.generate(),
                    scope,
                    workItem.id(),
                    role,
                    principal.id(),
                    principal.type(),
                    principal.type() == PrincipalType.USER
                            ? Optional.of(TeamMemberId.generate())
                            : Optional.empty(),
                    ResponsibilityAssignmentStatus.ACTIVE,
                    principal.id(),
                    CREATED_AT,
                    CREATED_AT,
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(principal.id(), CREATED_AT));
        }
    }
}
