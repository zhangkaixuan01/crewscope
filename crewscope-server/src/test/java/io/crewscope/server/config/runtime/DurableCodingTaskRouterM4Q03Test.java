package io.crewscope.server.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.agentscope.coding.CodingSpecialistStepResult;
import io.crewscope.agentscope.coding.CodingSpecialistStepRuntime;
import io.crewscope.agentscope.coding.CodingSpecialistStepStatus;
import io.crewscope.agentscope.coding.CodingSpecialistControlAction;
import io.crewscope.agentscope.coding.CodingSpecialistControlResult;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlResult;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.execution.TaskExecutionTerminalStatus;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.application.task.TaskTokenIssueResult;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentStatus;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanStepType;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.runtime.TaskWorkerPreparedExecution;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecution;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Q03 regression proof for the Task Agent to Coding Specialist routing boundary. */
class DurableCodingTaskRouterM4Q03Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-21T13:00:00Z");

    @Test
    void keepsCriticalValidationPendingUntilSuccessfulEvidenceThenCompletesIt() {
        Fixture fixture = new Fixture();
        TestEvidence evidence = mock(TestEvidence.class);
        when(evidence.succeeded()).thenReturn(true);
        when(fixture.testEvidenceRepository.findByWorkspace(
                        fixture.scope.organizationId(),
                        fixture.scope.teamId(),
                        fixture.scope.projectId(),
                        fixture.workspaceId))
                .thenReturn(List.of(evidence));
        when(fixture.runtime.execute(any())).thenAnswer(invocation -> {
            // The durable Specialist store owns the Implementation transition.
            fixture.implementationAfterRuntime = fixture.implementationSucceeded;
            return Mono.just(successResult());
        });

        TaskExecutionEvent result = fixture.router().executeAfterTaskAgent(
                fixture.prepared(true), fixture.completedTerminal);

        assertThat(result).isSameAs(fixture.completedTerminal);
        verify(fixture.validationPending, never()).skip(any(Long.class), any(), any());
        verify(fixture.validationPending).markReady(0L, fixture.executor, NOW);
        verify(fixture.validationReady).beginRunning(1L, fixture.executor, NOW);
        verify(fixture.validationRunning).succeed(2L, fixture.executor, NOW);
    }

    @Test
    void specialistFailureDoesNotFabricateValidationSuccess() {
        Fixture fixture = new Fixture();
        when(fixture.runtime.execute(any())).thenReturn(Mono.just(new CodingSpecialistStepResult(
                CodingSpecialistStepStatus.FAILED,
                1,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of("SPECIALIST_FAILED"))));

        TaskExecutionEvent result = fixture.router().executeAfterTaskAgent(
                fixture.prepared(true), fixture.completedTerminal);

        assertThat(result.payload().terminalStatus()).contains(TaskExecutionTerminalStatus.FAILED);
        verify(fixture.validationPending, never()).skip(any(Long.class), any(), any());
        verify(fixture.validationPending, never()).markReady(any(Long.class), any(), any());
        verify(fixture.testEvidenceRepository, never())
                .findByWorkspace(any(), any(), any(), any());
    }

    @Test
    void nonCodingTaskReturnsOriginalTerminalWithoutRouting() {
        Fixture fixture = new Fixture();

        TaskExecutionEvent result = fixture.router().executeAfterTaskAgent(
                fixture.prepared(false), fixture.completedTerminal);

        assertThat(result).isSameAs(fixture.completedTerminal);
        verify(fixture.runtime, never()).execute(any());
    }

    @Test
    void codingTaskWithoutWorkspaceFailsClosed() {
        Fixture fixture = new Fixture();

        TaskExecutionEvent result = fixture.router().executeAfterTaskAgent(
                fixture.preparedWithoutWorkspace(), fixture.completedTerminal);

        assertThat(result.payload().terminalStatus()).contains(TaskExecutionTerminalStatus.FAILED);
        assertThat(((TaskExecutionEventPayload.Failed) result.payload()).failure().runtimeCode())
                .contains("CODING_WORKSPACE_MISSING");
        verify(fixture.runtime, never()).execute(any());
    }

    @Test
    void missingPublishedPlanFailsClosed() {
        Fixture fixture = new Fixture();
        when(fixture.planRepository.findById(fixture.organizationId, fixture.planId))
                .thenReturn(Optional.empty());

        TaskExecutionEvent result = fixture.router().executeAfterTaskAgent(
                fixture.prepared(true), fixture.completedTerminal);

        assertThat(result.payload().terminalStatus()).contains(TaskExecutionTerminalStatus.FAILED);
        assertThat(((TaskExecutionEventPayload.Failed) result.payload()).failure().runtimeCode())
                .contains("CODING_PLAN_MISSING");
        verify(fixture.runtime, never()).execute(any());
    }

    @Test
    void missingImplementationStepExecutionFailsClosed() {
        Fixture fixture = new Fixture();
        when(fixture.stepRepository.findByExecution(
                        fixture.organizationId, fixture.executionId))
                .thenReturn(List.of(fixture.validationPending));

        TaskExecutionEvent result = fixture.router().executeAfterTaskAgent(
                fixture.prepared(true), fixture.completedTerminal);

        assertThat(result.payload().terminalStatus()).contains(TaskExecutionTerminalStatus.FAILED);
        assertThat(((TaskExecutionEventPayload.Failed) result.payload()).failure().runtimeCode())
                .contains("CODING_IMPLEMENTATION_STEP_MISSING");
        verify(fixture.runtime, never()).execute(any());
    }

    @Test
    void routesDurablePauseToTheActiveSpecialistAndReturnsARecoverableTerminal()
            throws Exception {
        Fixture fixture = new Fixture();
        DurableCodingTaskRouter router = fixture.router();
        TaskWorkerPreparedExecution prepared = fixture.prepared(true);
        CountDownLatch started = new CountDownLatch(1);
        Sinks.One<CodingSpecialistStepResult> resultSink = Sinks.one();
        when(fixture.runtime.execute(any())).thenAnswer(ignored -> {
            started.countDown();
            return resultSink.asMono();
        });
        when(fixture.runtime.control(any(), any(), any(), any()))
                .thenReturn(new CodingSpecialistControlResult(true, true));

        CompletableFuture<TaskExecutionEvent> routed = CompletableFuture.supplyAsync(
                () -> router.executeAfterTaskAgent(prepared, fixture.completedTerminal));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        UUID controlRequestId = UUID.randomUUID();

        Optional<TaskExecutionControlResult> first = router.controlTask(
                prepared,
                TaskExecutionControlAction.PAUSE,
                controlRequestId,
                "Paused by member");
        Optional<TaskExecutionControlResult> replay = router.controlTask(
                prepared,
                TaskExecutionControlAction.PAUSE,
                controlRequestId,
                "Paused by member");
        resultSink.tryEmitValue(new CodingSpecialistStepResult(
                CodingSpecialistStepStatus.PAUSED,
                2,
                0,
                Optional.empty(),
                Optional.of(CodingCheckpointId.generate()),
                Optional.empty()));
        TaskExecutionEvent terminal = routed.get(2, TimeUnit.SECONDS);

        assertThat(first).contains(TaskExecutionControlResult.ACCEPTED);
        assertThat(replay).contains(TaskExecutionControlResult.ALREADY_APPLIED);
        assertThat(terminal.payload().terminalStatus())
                .contains(TaskExecutionTerminalStatus.PAUSED);
        TaskExecutionEventPayload.Paused paused =
                (TaskExecutionEventPayload.Paused) terminal.payload();
        assertThat(paused.token().value()).isEqualTo(controlRequestId.toString());
        verify(fixture.runtime).control(
                any(),
                org.mockito.ArgumentMatchers.eq(CodingSpecialistControlAction.PAUSE),
                org.mockito.ArgumentMatchers.argThat(token -> token
                        .map(ExecutionInterruptToken::value)
                        .filter(controlRequestId.toString()::equals)
                        .isPresent()),
                org.mockito.ArgumentMatchers.eq("Paused by member"));
    }

    private static CodingSpecialistStepResult successResult() {
        return new CodingSpecialistStepResult(
                CodingSpecialistStepStatus.SUCCEEDED,
                1,
                0,
                Optional.of(mock(io.crewscope.application.coding.output.CodeChangeResultV1.class)),
                Optional.empty(),
                Optional.empty());
    }

    private static final class Fixture {
        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId,
                teamId,
                WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final PlanVersionId planId = PlanVersionId.generate();
        private final PolicySnapshotId policyId = PolicySnapshotId.generate();
        private final TaskFactHash policyHash = TaskFactHash.sha256("policy");
        private final TaskFactHash planHash = TaskFactHash.sha256("plan");
        private final SafetyEnforcementOverlayReference safetyReference =
                new SafetyEnforcementOverlayReference(
                        io.crewscope.domain.task.SafetyEnforcementOverlayId.generate(),
                        1L,
                        TaskFactHash.sha256("safety"));
        private final ExecutionLeaseId leaseId = ExecutionLeaseId.generate();
        private final FencingToken fencingToken = new FencingToken(1L);
        private final RuntimeEnvironment environment = new RuntimeEnvironment("test");
        private final ExecutionRuntimeId runtimeId = new ExecutionRuntimeId(UUID.randomUUID());
        private final RuntimeWorkerId workerId = new RuntimeWorkerId(UUID.randomUUID());
        private final ClaimTokenHash claimTokenHash = new ClaimTokenHash("a".repeat(64));
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final StepExecutionId implementationId = StepExecutionId.generate();
        private final StepExecutionId validationId = StepExecutionId.generate();
        private final ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        private final Principal executor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(PrincipalId.generate()),
                "Coding Specialist",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
        private final ExecutionPrincipalSnapshot principalSnapshot = mock(
                ExecutionPrincipalSnapshot.class);
        private final Task task = mock(Task.class);
        private final TaskExecution execution = mock(TaskExecution.class);
        private final TaskExecutionPlanningContext planning = mock(
                TaskExecutionPlanningContext.class);
        private final PolicySnapshot policy = mock(PolicySnapshot.class);
        private final SafetyEnforcementOverlay overlay = mock(SafetyEnforcementOverlay.class);
        private final ExecutionLease lease = mock(ExecutionLease.class);
        private final PlanVersion plan = mock(PlanVersion.class);
        private final PlanStep implementationPlan = new PlanStep(
                "implement",
                1,
                "Implement",
                PlanStepType.IMPLEMENTATION,
                Set.of(),
                Set.of(ExecutionCapability.WORKTREE, ExecutionCapability.SANDBOX),
                Set.of("fixture_execute"),
                true);
        private final PlanStep validationPlan = new PlanStep(
                "validate",
                2,
                "Validate",
                PlanStepType.VALIDATION,
                Set.of("implement"),
                Set.of(ExecutionCapability.PLAN),
                Set.of("fixture_validate"),
                true);
        private final StepExecution implementationPending = step(
                implementationId, "implement", 1, StepExecutionStatus.PENDING, 0L);
        private final StepExecution implementationReady = step(
                implementationId, "implement", 1, StepExecutionStatus.READY, 1L);
        private final StepExecution implementationSucceeded = step(
                implementationId, "implement", 1, StepExecutionStatus.SUCCEEDED, 3L);
        private final StepExecution validationPending = step(
                validationId, "validate", 2, StepExecutionStatus.PENDING, 0L);
        private final StepExecution validationReady = step(
                validationId, "validate", 2, StepExecutionStatus.READY, 1L);
        private final StepExecution validationRunning = step(
                validationId, "validate", 2, StepExecutionStatus.RUNNING, 2L);
        private final StepExecution validationSucceeded = step(
                validationId, "validate", 2, StepExecutionStatus.SUCCEEDED, 3L);
        private StepExecution implementationAfterRuntime = implementationReady;
        private final AgentProfile profile = mock(AgentProfile.class);
        private final TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        private final AgentRun run = mock(AgentRun.class);
        private final AgentRunSegment segment = mock(AgentRunSegment.class);
        private final TaskTokenExecutionContext authorization = mock(
                TaskTokenExecutionContext.class);
        private final TaskTokenGrantScope grantScope = mock(TaskTokenGrantScope.class);
        private final TaskExecutionRuntimeFacts originalFacts = mock(
                TaskExecutionRuntimeFacts.class);
        private final TaskTokenIssueResult token = mock(TaskTokenIssueResult.class);
        private final CodingWorkspaceExecution codingWorkspace = mock(
                CodingWorkspaceExecution.class);
        private final ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);

        private final CodingSpecialistStepRuntime runtime = mock(
                CodingSpecialistStepRuntime.class);
        private final TaskExecutionRepository executionRepository = mock(
                TaskExecutionRepository.class);
        private final ExecutionLeaseRepository leaseRepository = mock(
                ExecutionLeaseRepository.class);
        private final PlanVersionRepository planRepository = mock(PlanVersionRepository.class);
        private final SafetyEnforcementOverlayRepository overlayRepository = mock(
                SafetyEnforcementOverlayRepository.class);
        private final StepExecutionRepository stepRepository = mock(
                StepExecutionRepository.class, Answers.RETURNS_DEFAULTS);
        private final TaskAgentRuntimeSessionRepository sessionRepository = mock(
                TaskAgentRuntimeSessionRepository.class);
        private final AgentRunRepository runRepository = mock(AgentRunRepository.class);
        private final PrincipalRepository principalRepository = mock(PrincipalRepository.class);
        private final AgentProfileRepository profileRepository = mock(AgentProfileRepository.class);
        private final TestEvidenceRepository testEvidenceRepository = mock(
                TestEvidenceRepository.class);
        private final TransactionExecutor transactions = new DirectTransactionExecutor();
        private final TaskExecutionEvent completedTerminal = new TaskExecutionEvent(
                executionId,
                1,
                AgentRunId.generate(),
                1,
                10,
                NOW,
                new TaskExecutionEventPayload.Completed(Optional.empty()));

        private Fixture() {
            when(principalSnapshot.principalId()).thenReturn(executor.id());
            when(task.isClosed()).thenReturn(false);
            when(task.scope()).thenReturn(scope);
            when(task.id()).thenReturn(taskId);
            when(task.currentExecutionId()).thenReturn(Optional.of(executionId));

            when(execution.scope()).thenReturn(scope);
            when(execution.id()).thenReturn(executionId);
            when(execution.taskId()).thenReturn(taskId);
            when(execution.attempt()).thenReturn(1);
            when(execution.status()).thenReturn(TaskExecutionStatus.RUNNING);
            when(execution.planningContext()).thenReturn(Optional.of(planning));
            when(execution.lastFencingToken()).thenReturn(Optional.of(fencingToken));

            when(planning.currentPlanVersionId()).thenReturn(Optional.of(planId));
            when(planning.currentPlanVersionHash()).thenReturn(Optional.of(planHash));
            when(planning.executionPrincipal()).thenReturn(principalSnapshot);
            when(planning.policySnapshotId()).thenReturn(policyId);
            when(planning.policySnapshotHash()).thenReturn(policyHash);
            when(planning.safetyOverlay()).thenReturn(safetyReference);

            when(policy.scope()).thenReturn(scope);
            when(policy.taskId()).thenReturn(taskId);
            when(policy.executionId()).thenReturn(executionId);
            when(policy.executionPrincipal()).thenReturn(principalSnapshot);
            when(policy.id()).thenReturn(policyId);
            when(policy.snapshotHash()).thenReturn(policyHash);
            when(policy.agentProfileId()).thenReturn(profileId);
            when(policy.agentProfileVersion()).thenReturn(1L);

            when(overlay.scope()).thenReturn(scope);
            when(overlay.taskId()).thenReturn(taskId);
            when(overlay.executionId()).thenReturn(executionId);
            when(overlay.reference()).thenReturn(safetyReference);

            when(lease.organizationId()).thenReturn(organizationId);
            when(lease.taskExecutionId()).thenReturn(executionId);
            when(lease.attempt()).thenReturn(1);
            when(lease.id()).thenReturn(leaseId);
            when(lease.fencingToken()).thenReturn(fencingToken);
            when(lease.environment()).thenReturn(environment);
            when(lease.runtimeId()).thenReturn(runtimeId);
            when(lease.workerId()).thenReturn(workerId);
            when(lease.claimTokenHash()).thenReturn(claimTokenHash);
            when(lease.expiresAt()).thenReturn(
                    UtcTimestamp.parse("2026-08-21T13:01:00Z"));
            when(lease.release()).thenReturn(Optional.empty());

            when(plan.id()).thenReturn(planId);
            when(plan.versionHash()).thenReturn(planHash);
            when(plan.steps()).thenReturn(List.of(implementationPlan, validationPlan));
            when(plan.scope()).thenReturn(scope);
            when(plan.taskId()).thenReturn(taskId);
            when(plan.executionId()).thenReturn(executionId);
            when(plan.policySnapshotId()).thenReturn(policyId);
            when(plan.policySnapshotHash()).thenReturn(policyHash);
            when(plan.safetyOverlay()).thenReturn(safetyReference);
            when(plan.executionPrincipal()).thenReturn(principalSnapshot);

            when(implementationPending.markReady(0L, executor, NOW))
                    .thenReturn(implementationReady);
            when(validationPending.markReady(0L, executor, NOW)).thenReturn(validationReady);
            when(validationReady.beginRunning(1L, executor, NOW)).thenReturn(validationRunning);
            when(validationRunning.succeed(2L, executor, NOW))
                    .thenReturn(validationSucceeded);
            when(stepRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

            when(profile.id()).thenReturn(profileId);
            when(profile.scope()).thenReturn(WorkspaceScope.team(organizationId, teamId));
            when(profile.workspaceId()).thenReturn(scope.workspaceId());
            when(profile.agentPrincipalId()).thenReturn(executor.id());
            when(profile.type()).thenReturn(AgentProfileType.SPECIALIST);
            when(profile.status()).thenReturn(AgentProfileStatus.ACTIVE);
            when(profile.version()).thenReturn(1L);

            AgentRuntimeSessionId sessionId = AgentRuntimeSessionId.forTaskExecution(
                    executionId,
                    Optional.of(implementationId),
                    profileId,
                    TaskAgentSessionPurpose.SPECIALIST.name());
            when(session.id()).thenReturn(sessionId);
            when(session.scope()).thenReturn(scope);
            when(session.taskId()).thenReturn(taskId);
            when(session.executionId()).thenReturn(executionId);
            when(session.stepExecutionId()).thenReturn(Optional.of(implementationId));
            when(session.agentPrincipalId()).thenReturn(executor.id());
            when(session.agentProfileId()).thenReturn(profileId);
            when(session.agentProfileVersion()).thenReturn(1L);

            when(run.id()).thenReturn(AgentRunId.generate());
            when(run.status()).thenReturn(AgentRunStatus.RUNNING);
            when(run.currentSegment()).thenReturn(segment);
            when(run.scope()).thenReturn(scope);
            when(run.taskId()).thenReturn(taskId);
            when(run.executionId()).thenReturn(executionId);
            when(run.stepExecutionId()).thenReturn(Optional.of(implementationId));
            when(run.runtimeSessionId()).thenReturn(sessionId);
            when(run.agentPrincipalId()).thenReturn(executor.id());
            when(run.agentProfileId()).thenReturn(profileId);
            when(run.agentProfileVersion()).thenReturn(1L);
            when(run.runSequence()).thenReturn(2L);
            when(segment.status()).thenReturn(AgentRunSegmentStatus.ACTIVE);

            when(grantScope.workItemScope()).thenReturn(scope);
            when(grantScope.taskId()).thenReturn(taskId);
            when(grantScope.taskExecutionId()).thenReturn(executionId);
            when(grantScope.attempt()).thenReturn(1);
            when(grantScope.executionLeaseId()).thenReturn(leaseId);
            when(grantScope.environment()).thenReturn(environment);
            when(grantScope.runtimeId()).thenReturn(runtimeId);
            when(grantScope.workerId()).thenReturn(workerId);
            when(grantScope.claimTokenHash()).thenReturn(claimTokenHash);
            when(grantScope.fencingToken()).thenReturn(fencingToken);
            when(grantScope.executionPrincipal()).thenReturn(principalSnapshot);
            when(grantScope.policySnapshotId()).thenReturn(policyId);
            when(grantScope.policySnapshotHash()).thenReturn(policyHash);
            when(grantScope.safetyOverlay()).thenReturn(safetyReference);
            when(authorization.scope()).thenReturn(grantScope);
            when(authorization.expiresAt()).thenReturn(
                    UtcTimestamp.parse("2026-08-21T13:00:30Z"));

            when(originalFacts.task()).thenReturn(task);
            when(originalFacts.execution()).thenReturn(execution);
            when(originalFacts.policySnapshot()).thenReturn(policy);
            when(originalFacts.lease()).thenReturn(lease);
            when(token.context()).thenReturn(authorization);

            when(workspace.scope()).thenReturn(scope);
            when(workspace.id()).thenReturn(workspaceId);
            when(codingWorkspace.workspace()).thenReturn(workspace);

            when(executionRepository.findById(organizationId, executionId))
                    .thenReturn(Optional.of(execution));
            when(planRepository.findById(organizationId, planId)).thenReturn(Optional.of(plan));
            when(stepRepository.findByExecution(organizationId, executionId))
                    .thenReturn(List.of(implementationPending, validationPending));
            when(stepRepository.findById(organizationId, implementationId))
                    .thenAnswer(ignored -> Optional.of(implementationAfterRuntime));
            when(stepRepository.findById(organizationId, validationId))
                    .thenReturn(Optional.of(validationPending));
            when(principalRepository.findById(organizationId, executor.id()))
                    .thenReturn(Optional.of(executor));
            when(profileRepository.findById(organizationId, profileId))
                    .thenReturn(Optional.of(profile));
            when(sessionRepository.initializeIfAbsent(any())).thenReturn(session);
            when(runRepository.findActiveBySession(organizationId, sessionId))
                    .thenReturn(Optional.of(run));
            when(leaseRepository.findById(organizationId, environment, leaseId))
                    .thenReturn(Optional.of(lease));
            when(overlayRepository.findByIdAndVersion(
                            organizationId, safetyReference.id(), safetyReference.version()))
                    .thenReturn(Optional.of(overlay));
        }

        private StepExecution step(
                StepExecutionId id,
                String key,
                int sequence,
                StepExecutionStatus status,
                long version) {
            StepExecution step = mock(StepExecution.class);
            when(step.id()).thenReturn(id);
            when(step.scope()).thenReturn(scope);
            when(step.taskId()).thenReturn(taskId);
            when(step.executionId()).thenReturn(executionId);
            when(step.planVersionId()).thenReturn(planId);
            when(step.planVersionHash()).thenReturn(planHash);
            when(step.planStepKey()).thenReturn(key);
            when(step.sequence()).thenReturn(sequence);
            when(step.critical()).thenReturn(true);
            when(step.executionPrincipal()).thenReturn(principalSnapshot);
            when(step.policySnapshotId()).thenReturn(policyId);
            when(step.policySnapshotHash()).thenReturn(policyHash);
            when(step.safetyOverlay()).thenReturn(safetyReference);
            when(step.status()).thenReturn(status);
            when(step.version()).thenReturn(version);
            return step;
        }

        private TaskWorkerPreparedExecution prepared(boolean coding) {
            when(policy.capabilities()).thenReturn(coding
                    ? Set.of(ExecutionCapability.WORKTREE, ExecutionCapability.SANDBOX)
                    : Set.of(ExecutionCapability.PLAN));
            return new TaskWorkerPreparedExecution(
                    originalFacts,
                    mock(io.crewscope.application.task.LeaseCommandScope.class),
                    token,
                    UUID.randomUUID(),
                    Optional.of(codingWorkspace));
        }

        private TaskWorkerPreparedExecution preparedWithoutWorkspace() {
            when(policy.capabilities()).thenReturn(Set.of(
                    ExecutionCapability.WORKTREE, ExecutionCapability.SANDBOX));
            return new TaskWorkerPreparedExecution(
                    originalFacts,
                    mock(io.crewscope.application.task.LeaseCommandScope.class),
                    token,
                    UUID.randomUUID(),
                    Optional.empty());
        }

        private DurableCodingTaskRouter router() {
            return new DurableCodingTaskRouter(
                    runtime,
                    executionRepository,
                    leaseRepository,
                    planRepository,
                    overlayRepository,
                    stepRepository,
                    sessionRepository,
                    runRepository,
                    principalRepository,
                    profileRepository,
                    testEvidenceRepository,
                    transactions,
                    () -> NOW,
                    10);
        }
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
