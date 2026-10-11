package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskAttempt;
import io.crewscope.application.task.TaskControlAction;
import io.crewscope.application.task.TaskControlAvailabilityProjector;
import io.crewscope.application.task.TaskDetails;
import io.crewscope.application.task.TaskListCursor;
import io.crewscope.application.task.TaskListItem;
import io.crewscope.application.task.TaskListPage;
import io.crewscope.application.task.TaskQueryService;
import io.crewscope.application.task.TaskRuntimeFacts;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
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
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentInterruptResolution;
import io.crewscope.domain.task.AgentInterruptStatus;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunContinuityGap;
import io.crewscope.domain.task.AgentRunContinuityGapReason;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.AgentRunSegmentStatus;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.AgentRunTerminal;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentStateSnapshotStatus;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.ExecutionLeaseRelease;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanStepType;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.ProposedPlan;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.StepCheckpoint;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.StepWaitReason;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecutionControlRequest;
import io.crewscope.domain.task.TaskExecutionControlRequestType;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskExecutionTerminal;
import io.crewscope.domain.task.TaskExecutionWaiting;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TodoStatus;
import io.crewscope.domain.task.TodoSummaryItem;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskSourceType;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP and disclosure contract for M3-A02 Task query APIs. */
class TaskQueryControllerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), projectId);
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            "Member",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            NOW);

    private TaskQueryService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(TaskQueryService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new TaskQueryController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsStableTaskSummariesAndReturnsAnOpaqueNextCursor() {
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        TaskListItem item = new TaskListItem(
                taskId,
                scope,
                WorkItemId.generate(),
                new TaskBrief("Ship Task query API", List.of("Stable cursor")),
                TaskStatus.ACTIVE,
                Optional.of(executionId),
                Optional.of(1),
                Optional.of(TaskExecutionStatus.WAITING),
                Optional.of(io.crewscope.domain.task.TaskExecutionWaitReason.RUNTIME),
                Optional.of(actor.id()),
                3,
                AuditMetadata.createdBy(actor.id(), NOW));
        when(service.list(any(), any(), any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new TaskListPage(List.of(item), Optional.of(item.cursor())));

        client.get()
                .uri(root() + "?projectId=" + projectId + "&status=ACTIVE&limit=20")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo(taskId.toString())
                .jsonPath("$.items[0].currentExecutionStatus").isEqualTo("WAITING")
                .jsonPath("$.items[0].currentWaitingReason").isEqualTo("RUNTIME")
                .jsonPath("$.items[0].ownerPrincipalId").isEqualTo(actor.id().toString())
                .jsonPath("$.nextCursor").isNotEmpty();
    }

    @Test
    void returnsTaskDetailsAndOrderedAttemptSummariesWithStrongEtags() {
        Task task = task();
        TaskExecution execution = execution(task.id());
        TaskAttempt attempt = new TaskAttempt(execution, actions(execution, true));
        TaskDetails details = new TaskDetails(task, List.of(attempt));
        when(service.get(any(), any(), any(), any())).thenReturn(details);
        when(service.attempts(any(), any(), any(), any()))
                .thenReturn(List.of(attempt));

        client.get()
                .uri(root() + "/" + task.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"4\"")
                .expectBody()
                .jsonPath("$.objective").isEqualTo("Inspect runtime history")
                .jsonPath("$.source.type").isEqualTo("WORK_ITEM")
                .jsonPath("$.attempts[0].status").isEqualTo("READY")
                // A READY attempt may be cancelled; the control travels with the row it belongs to,
                // disabled entries included, so a control bar never has to re-derive the verdict.
                .jsonPath("$.attempts[0].availableActions[0].actionId").isEqualTo("pause")
                .jsonPath("$.attempts[0].availableActions[0].enabled").isEqualTo(false)
                .jsonPath("$.attempts[0].availableActions[0].reason").isEqualTo("STATUS_NOT_ALLOWED")
                .jsonPath("$.attempts[0].availableActions[2].actionId").isEqualTo("cancel")
                .jsonPath("$.attempts[0].availableActions[2].enabled").isEqualTo(true)
                .jsonPath("$.attempts[0].availableActions[2].targetStatus").isEqualTo("CANCEL_REQUESTED");

        client.get()
                .uri(root() + "/" + task.id() + "/attempts")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].attempt").isEqualTo(1)
                .jsonPath("$[0].status").isEqualTo("READY")
                .jsonPath("$[0].availableActions[0].reasonMessage").isNotEmpty();
    }

    @Test
    void runtimeFactsNeverSerializeTokensFencingOrAgentScopeStateCoordinates() {
        Task task = task();
        TaskExecution execution = execution(task.id());
        TaskRuntimeFacts facts = new TaskRuntimeFacts(
                task,
                execution,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(service.runtimeFacts(any(), any(), any(), any(), any())).thenReturn(facts);

        client.get()
                .uri(root() + "/" + task.id() + "/attempts/" + execution.id()
                        + "/runtime-facts")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"2\"")
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"execution\""));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("claimToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("fencingToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("agentScopeUserId"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("stateReference"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("contentHash"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("interruptToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("responseHash"));
                });
    }

    @Test
    void rejectsMalformedCursorsAndRouteIdentifiersBeforeCallingTheService() {
        client.get()
                .uri(root() + "?after=not+canonical")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");

        client.get()
                .uri(root() + "/not-a-task")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    }

    @Test
    void runtimeFactsSerializesEveryPopulatedRuntimeAggregateShape() {
        RuntimeFixture fixture = new RuntimeFixture(scope);
        TaskExecution execution = execution(fixture.task.id());
        when(execution.id()).thenReturn(fixture.executionId);
        when(execution.waiting()).thenReturn(Optional.of(
                new TaskExecutionWaiting(TaskExecutionWaitReason.USER_INPUT, NOW)));
        when(execution.controlRequest()).thenReturn(Optional.of(new TaskExecutionControlRequest(
                TaskExecutionControlRequestType.PAUSE, actor.id(), NOW, "member requested")));
        when(execution.terminal()).thenReturn(Optional.of(new TaskExecutionTerminal(
                TaskExecutionStatus.FAILED, actor.id(), NOW,
                Optional.of(new TaskExecutionFailure(
                        TaskExecutionFailureClass.TIMEOUT, "AGENT_TIMEOUT")))));
        when(execution.planningContext()).thenReturn(Optional.of(fixture.planningContext));
        when(execution.version()).thenReturn(5L);
        TaskRuntimeFacts facts = new TaskRuntimeFacts(
                fixture.task,
                execution,
                List.of(fixture.plan),
                List.of(fixture.waitingStep, fixture.failedStep),
                List.of(fixture.session),
                List.of(fixture.completedRun, fixture.recoveryRun),
                List.of(fixture.resolvedInterrupt),
                List.of(fixture.currentSnapshot, fixture.invalidSnapshot),
                List.of(fixture.activeLease, fixture.releasedLease));
        when(service.runtimeFacts(any(), any(), any(), any(), any())).thenReturn(facts);

        client.get()
                .uri(root() + "/" + fixture.task.id() + "/attempts/" + execution.id()
                        + "/runtime-facts")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"5\"")
                .expectBody()
                .jsonPath("$.execution.status").isEqualTo("READY")
                .jsonPath("$.execution.waiting.reason").isEqualTo("USER_INPUT")
                .jsonPath("$.execution.controlRequest.type").isEqualTo("PAUSE")
                .jsonPath("$.execution.terminal.failureClass").isEqualTo("TIMEOUT")
                .jsonPath("$.execution.terminal.failureCode").isEqualTo("AGENT_TIMEOUT")
                .jsonPath("$.execution.executorPrincipalId").isEqualTo(fixture.executorId.toString())
                .jsonPath("$.execution.currentPlanVersionId").isEqualTo(fixture.plan.id().toString())
                .jsonPath("$.planVersions[0].revision").isEqualTo(1)
                .jsonPath("$.planVersions[0].changeReason").isEqualTo("INITIAL_PLAN")
                .jsonPath("$.planVersions[0].steps[0].key").isEqualTo("analyze")
                .jsonPath("$.planVersions[0].steps[0].critical").isEqualTo(true)
                .jsonPath("$.planVersions[0].todoSummary[0].status").isEqualTo("IN_PROGRESS")
                .jsonPath("$.planVersions[0].todoSummary[0].priority").isEqualTo("high")
                .jsonPath("$.steps[0].planStepKey").isEqualTo("analyze")
                .jsonPath("$.steps[0].waitReason").isEqualTo("HANDOFF")
                .jsonPath("$.steps[0].checkpoint.code").isEqualTo("COMPILED")
                .jsonPath("$.steps[1].failureClass").isEqualTo("TIMEOUT")
                .jsonPath("$.steps[1].failureCode").isEqualTo("STEP_TIMEOUT")
                .jsonPath("$.sessions[0].purpose").isEqualTo("STEP")
                .jsonPath("$.sessions[0].stepExecutionId").isEqualTo(
                        fixture.waitingStep.id().toString())
                .jsonPath("$.agentRuns[0].status").isEqualTo("COMPLETED")
                .jsonPath("$.agentRuns[0].segments[0].kind").isEqualTo("INVOKE")
                .jsonPath("$.agentRuns[0].segments[1].kind").isEqualTo("RESUME")
                .jsonPath("$.agentRuns[0].segments[1].resumedFromInterruptId").isEqualTo(
                        fixture.interruptId.toString())
                .jsonPath("$.agentRuns[0].terminal.status").isEqualTo("COMPLETED")
                .jsonPath("$.agentRuns[0].terminal.resultArtifactId").isNotEmpty()
                .jsonPath("$.agentRuns[1].segments[0].kind").isEqualTo("RECOVERY")
                .jsonPath("$.agentRuns[1].continuityGap.reason").isEqualTo("SNAPSHOT_MISSING")
                .jsonPath("$.agentRuns[1].continuityGap.firstMissingCheckpoint").isEqualTo(3)
                .jsonPath("$.interrupts[0].kind").isEqualTo("APPROVAL")
                .jsonPath("$.interrupts[0].status").isEqualTo("RESOLVED")
                .jsonPath("$.interrupts[0].resolvedByPrincipalId").isEqualTo(
                        fixture.ownerId.toString())
                .jsonPath("$.interrupts[0].resolvedAt").isNotEmpty()
                .jsonPath("$.snapshots[0].status").isEqualTo("CURRENT")
                .jsonPath("$.snapshots[0].sizeBytes").isEqualTo(2048)
                .jsonPath("$.snapshots[1].status").isEqualTo("INVALID")
                .jsonPath("$.snapshots[1].invalidReasonCode").isEqualTo("SNAPSHOT_CORRUPT")
                .jsonPath("$.leases[0].phase").isEqualTo("RUN")
                .jsonPath("$.leases[0].status").isEqualTo("ACTIVE")
                .jsonPath("$.leases[1].status").isEqualTo("RELEASED")
                .jsonPath("$.leases[1].releaseReason").isEqualTo("EXPIRED")
                .jsonPath("$.leases[1].releasedAt").isNotEmpty();

        client.get()
                .uri(root() + "/" + fixture.task.id() + "/attempts/" + execution.id()
                        + "/runtime-facts")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    // Runtime facts is a diagnostic read, so its execution row carries no
                    // adjudication: null, not an empty list that would claim every refusal.
                    org.junit.jupiter.api.Assertions.assertTrue(
                            body.contains("\"availableActions\":null"));
                    // Every secret-shaped field exists on the aggregates above; the wire
                    // contract has to keep dropping all of them.
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("claimTokenHash"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("fencingToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("stateReference"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("contentHash"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("interruptToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("responseHash"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("versionHash"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("agentScopeUserId"));
                });
    }

    private Task task() {
        Task task = mock(Task.class);
        TaskId id = new TaskId(UUID.nameUUIDFromBytes(
                "m3-a02-task".getBytes(StandardCharsets.UTF_8)));
        TaskSource source = mock(TaskSource.class);
        TaskResponsibilitySnapshot snapshot = mock(TaskResponsibilitySnapshot.class);
        when(task.id()).thenReturn(id);
        when(task.scope()).thenReturn(scope);
        when(task.workItemId()).thenReturn(WorkItemId.generate());
        when(task.brief()).thenReturn(new TaskBrief(
                "Inspect runtime history", List.of("Hide internal state")));
        when(source.type()).thenReturn(TaskSourceType.WORK_ITEM);
        when(source.workItemVersion()).thenReturn(7L);
        when(source.conversationId()).thenReturn(Optional.empty());
        when(source.inputReference()).thenReturn(Optional.empty());
        when(task.source()).thenReturn(source);
        when(snapshot.entries()).thenReturn(List.of());
        when(snapshot.capturedAt()).thenReturn(NOW);
        when(task.responsibilitySnapshot()).thenReturn(snapshot);
        when(task.status()).thenReturn(TaskStatus.ACTIVE);
        when(task.currentExecutionId()).thenReturn(Optional.empty());
        when(task.cancellation()).thenReturn(Optional.empty());
        when(task.version()).thenReturn(4L);
        when(task.audit()).thenReturn(AuditMetadata.createdBy(actor.id(), NOW));
        return task;
    }

    private TaskExecution execution(TaskId taskId) {
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(TaskExecutionId.generate());
        when(execution.taskId()).thenReturn(taskId);
        when(execution.scope()).thenReturn(scope);
        when(execution.attempt()).thenReturn(1);
        when(execution.maxAttempts()).thenReturn(3);
        when(execution.parentExecutionId()).thenReturn(Optional.empty());
        when(execution.priority()).thenReturn(TaskExecutionPriority.NORMAL);
        when(execution.notBefore()).thenReturn(NOW);
        when(execution.status()).thenReturn(TaskExecutionStatus.READY);
        when(execution.waiting()).thenReturn(Optional.empty());
        when(execution.controlRequest()).thenReturn(Optional.empty());
        when(execution.terminal()).thenReturn(Optional.empty());
        when(execution.planningContext()).thenReturn(Optional.empty());
        when(execution.version()).thenReturn(2L);
        when(execution.audit()).thenReturn(AuditMetadata.createdBy(actor.id(), NOW));
        // A Mockito mock answers the domain predicates with defaults, so state them: a READY attempt
        // may only be cancelled, which is the verdict the wire shape has to carry.
        when(execution.canRequestPause()).thenReturn(false);
        when(execution.canResume()).thenReturn(false);
        when(execution.canRequestCancel()).thenReturn(true);
        when(execution.canRetry()).thenReturn(false);
        return execution;
    }

    /**
     * Populated runtime aggregates for the characterization test above. The Task /
     * TaskExecution / PlanVersion lineage is produced by the real domain factories
     * (a published PlanVersion self-checks its hash), and the child aggregates are
     * reconstituted with every optional shape present: waiting, control request,
     * terminal failure, checkpoint, resumed segment, continuity gap, resolution,
     * invalid snapshot and a released lease.
     */
    private static final class RuntimeFixture {
        private static final UtcTimestamp POLICY_AT = UtcTimestamp.parse("2026-08-15T07:00:00Z");
        private static final UtcTimestamp PLAN_AT = UtcTimestamp.parse("2026-08-15T07:05:00Z");
        private static final UtcTimestamp LEASE_AT = UtcTimestamp.parse("2026-08-15T07:10:00Z");

        private final Task task;
        private final TaskExecutionId executionId;
        private final PrincipalId ownerId;
        private final PrincipalId executorId;
        private final PlanVersion plan;
        private final TaskExecutionPlanningContext planningContext;
        private final StepExecution waitingStep;
        private final StepExecution failedStep;
        private final AgentInterruptId interruptId;
        private final TaskAgentRuntimeSession session;
        private final AgentRun completedRun;
        private final AgentRun recoveryRun;
        private final AgentInterrupt resolvedInterrupt;
        private final AgentStateSnapshot currentSnapshot;
        private final AgentStateSnapshot invalidSnapshot;
        private final ExecutionLease activeLease;
        private final ExecutionLease releasedLease;

        RuntimeFixture(WorkItemScope scope) {
            Principal owner = Principal.create(
                    ownerId = PrincipalId.generate(),
                    PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    POLICY_AT);
            executorId = PrincipalId.generate();
            Principal executor = Principal.create(
                    executorId,
                    PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    PrincipalType.TEAM_AGENT,
                    Optional.of(owner.id()),
                    "Executor",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    POLICY_AT);
            WorkItem workItem = WorkItem.reconstitute(
                    WorkItemId.generate(), scope, new WorkItemKey("RUNTIME-1"),
                    "Runtime facts task", WorkItemStatus.READY, 0,
                    AuditMetadata.createdBy(owner.id(), POLICY_AT));
            ResponsibilityAssignment ownerAssignment = assignment(
                    scope, workItem, owner, ResponsibilityRole.OWNER,
                    Optional.of(TeamMemberId.generate()));
            ResponsibilityAssignment executorAssignment = assignment(
                    scope, workItem, executor, ResponsibilityRole.EXECUTOR, Optional.empty());
            TaskResponsibilitySnapshot snapshot = TaskResponsibilitySnapshot.capture(
                    workItem, List.of(ownerAssignment, executorAssignment), POLICY_AT);
            task = Task.create(
                    TaskId.generate(), workItem, TaskSource.fromWorkItem(workItem),
                    new TaskBrief("Serialize runtime shapes", List.of("Every response branch")),
                    snapshot, owner, POLICY_AT);
            TaskExecution execution = TaskExecution.firstAttempt(
                    executionId = TaskExecutionId.generate(), task, 3,
                    TaskExecutionPriority.NORMAL, POLICY_AT, owner, POLICY_AT);
            AgentProfileId profileId = AgentProfileId.generate();
            PolicySnapshot policy = PolicySnapshot.initial(
                    PolicySnapshotId.generate(), task, execution, executor,
                    new PolicyPackReference(PolicyPackId.generate(), 3), profileId, 4,
                    Set.of(ExecutionCapability.PLAN, ExecutionCapability.STRUCTURED_OUTPUT),
                    Set.of("repository.read", "validation.run"), Set.of(),
                    new PolicyBudget(100_000, 20, 50, 3_600), owner, POLICY_AT);
            SafetyEnforcementOverlay overlay = SafetyEnforcementOverlay.unrestricted(
                    SafetyEnforcementOverlayId.generate(), task, execution, owner, POLICY_AT);
            TaskExecution initialized = execution.initializePlanningContext(
                    policy, overlay, 0, owner, POLICY_AT);
            plan = PlanVersion.publishInitial(
                    PlanVersionId.generate(), task, initialized, ProposedPlan.of(
                            "# Plan\n\nAnalyze and hand off.",
                            List.of(
                                    new PlanStep("analyze", 1, "Analyze", PlanStepType.ANALYSIS,
                                            Set.of(), Set.of(ExecutionCapability.PLAN),
                                            Set.of("repository.read"), true),
                                    new PlanStep("validate", 2, "Validate",
                                            PlanStepType.VALIDATION, Set.of("analyze"),
                                            Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                                            Set.of("validation.run"), false))),
                    List.of(new TodoSummaryItem(
                            "Analyze", TodoStatus.IN_PROGRESS, Optional.of("high"),
                            Optional.of("analyze"))),
                    policy, overlay, owner, PLAN_AT);
            planningContext = initialized.switchCurrentPlan(
                    plan, Optional.empty(), 1, owner, PLAN_AT).planningContext().orElseThrow();
            ExecutionPrincipalSnapshot principal = plan.executionPrincipal();
            waitingStep = StepExecution.reconstitute(
                    StepExecutionId.generate(), scope, task.id(), execution.id(), plan.id(),
                    plan.versionHash(), "analyze", 1, true, principal, plan.policySnapshotId(),
                    plan.policySnapshotHash(), plan.safetyOverlay(), 1, 2,
                    StepExecutionStatus.WAITING, Optional.of(StepWaitReason.HANDOFF),
                    Optional.of(new StepCheckpoint(
                            1, "COMPILED", TaskFactHash.sha256("checkpoint"),
                            principal.principalId(), NOW)),
                    Optional.empty(), 5, AuditMetadata.createdBy(principal.principalId(), NOW));
            failedStep = StepExecution.reconstitute(
                    StepExecutionId.generate(), scope, task.id(), execution.id(), plan.id(),
                    plan.versionHash(), "validate", 2, false, principal, plan.policySnapshotId(),
                    plan.policySnapshotHash(), plan.safetyOverlay(), 2, 2,
                    StepExecutionStatus.FAILED_FINAL, Optional.empty(), Optional.empty(),
                    Optional.of(new TaskExecutionFailure(
                            TaskExecutionFailureClass.TIMEOUT, "STEP_TIMEOUT")),
                    3, AuditMetadata.createdBy(principal.principalId(), NOW));
            AgentRuntimeSessionId sessionId = AgentRuntimeSessionId.forTaskExecution(
                    execution.id(), Optional.of(waitingStep.id()), profileId,
                    TaskAgentSessionPurpose.STEP.name());
            AgentScopeSessionKey scopeKey = AgentScopeSessionKey.forTaskExecution(
                    scope.organizationId(), executorId, execution.id(), sessionId);
            session = TaskAgentRuntimeSession.reconstitute(
                    sessionId, scope, task.id(), execution.id(), Optional.of(waitingStep.id()),
                    TaskAgentSessionPurpose.STEP, executorId, PrincipalType.TEAM_AGENT, profileId,
                    AgentProfileType.TEAM, 4, scopeKey,
                    AgentRuntimeStateReference.forSession(sessionId),
                    AgentRuntimeSessionStatus.ACTIVE, 2,
                    AuditMetadata.createdBy(owner.id(), PLAN_AT));
            AgentRunId runId = AgentRunId.generate();
            interruptId = AgentInterruptId.generate();
            completedRun = AgentRun.reconstitute(
                    runId, scope, task.id(), execution.id(), Optional.of(waitingStep.id()),
                    sessionId, executorId, profileId, 4, 1, AgentRunStatus.COMPLETED,
                    List.of(
                            new AgentRunSegment(1, AgentRunSegmentKind.INVOKE,
                                    Optional.empty(), AgentRunSegmentStatus.COMPLETED,
                                    PLAN_AT, Optional.of(NOW)),
                            new AgentRunSegment(2, AgentRunSegmentKind.RESUME,
                                    Optional.of(interruptId), AgentRunSegmentStatus.COMPLETED,
                                    NOW, Optional.of(NOW))),
                    Optional.empty(),
                    Optional.of(new AgentRunTerminal(
                            AgentRunStatus.COMPLETED, Optional.empty(),
                            Optional.of(RuntimeArtifactId.generate()), NOW)),
                    3, AuditMetadata.createdBy(owner.id(), PLAN_AT));
            recoveryRun = AgentRun.reconstitute(
                    AgentRunId.generate(), scope, task.id(), execution.id(),
                    Optional.of(waitingStep.id()), sessionId, executorId, profileId, 4, 2,
                    AgentRunStatus.COMPLETED,
                    List.of(new AgentRunSegment(1, AgentRunSegmentKind.RECOVERY,
                            Optional.empty(), AgentRunSegmentStatus.COMPLETED,
                            NOW, Optional.of(NOW))),
                    Optional.of(new AgentRunContinuityGap(
                            runId, Optional.of(AgentStateSnapshotId.generate()),
                            3, 5, AgentRunContinuityGapReason.SNAPSHOT_MISSING, NOW)),
                    Optional.of(new AgentRunTerminal(
                            AgentRunStatus.COMPLETED, Optional.empty(),
                            Optional.of(RuntimeArtifactId.generate()), NOW)),
                    2, AuditMetadata.createdBy(owner.id(), PLAN_AT));
            resolvedInterrupt = AgentInterrupt.reconstitute(
                    interruptId, scope, execution.id(), runId, 2, AgentInterruptKind.APPROVAL,
                    RuntimeContentHash.sha256("opaque-interrupt-token"),
                    AgentInterruptStatus.RESOLVED,
                    Optional.of(new AgentInterruptResolution(
                            UUID.randomUUID(), RuntimeContentHash.sha256("approved-answer"),
                            owner.id(), NOW)),
                    1, AuditMetadata.createdBy(owner.id(), PLAN_AT));
            currentSnapshot = AgentStateSnapshot.reconstitute(
                    AgentStateSnapshotId.generate(), scope, execution.id(), runId, sessionId,
                    profileId, 4, executorId, "Executor", scopeKey, 1, 2,
                    RuntimeArtifactId.generate(), RuntimeContentHash.sha256("snapshot-payload"),
                    2048L, AgentStateSnapshotStatus.CURRENT, Optional.empty(), 0,
                    AuditMetadata.createdBy(owner.id(), PLAN_AT));
            invalidSnapshot = AgentStateSnapshot.reconstitute(
                    AgentStateSnapshotId.generate(), scope, execution.id(), runId, sessionId,
                    profileId, 4, executorId, "Executor", scopeKey, 2, 4,
                    RuntimeArtifactId.generate(), RuntimeContentHash.sha256("broken-payload"),
                    512L, AgentStateSnapshotStatus.INVALID, Optional.of("SNAPSHOT_CORRUPT"), 0,
                    AuditMetadata.createdBy(owner.id(), PLAN_AT));
            RuntimeEnvironment environment = new RuntimeEnvironment("production");
            activeLease = ExecutionLease.reconstitute(
                    ExecutionLeaseId.generate(), scope.organizationId(), environment,
                    execution.id(), 1, ExecutionRuntimeId.generate(), RuntimeWorkerId.generate(),
                    new ClaimTokenHash(TaskFactHash.sha256("claim-token").value()),
                    FencingToken.initial(), ExecutionLeasePhase.RUN, LEASE_AT, NOW,
                    UtcTimestamp.parse("2026-08-15T08:05:00Z"), Optional.empty(), 0);
            releasedLease = ExecutionLease.reconstitute(
                    ExecutionLeaseId.generate(), scope.organizationId(), environment,
                    execution.id(), 1, ExecutionRuntimeId.generate(), RuntimeWorkerId.generate(),
                    new ClaimTokenHash(TaskFactHash.sha256("released-claim").value()),
                    FencingToken.initial(), ExecutionLeasePhase.PREPARE, LEASE_AT,
                    UtcTimestamp.parse("2026-08-15T07:20:00Z"),
                    UtcTimestamp.parse("2026-08-15T07:40:00Z"),
                    Optional.of(new ExecutionLeaseRelease(
                            ExecutionLeaseReleaseReason.EXPIRED,
                            UtcTimestamp.parse("2026-08-15T07:45:00Z"))),
                    1);
        }

        private static ResponsibilityAssignment assignment(
                WorkItemScope scope,
                WorkItem workItem,
                Principal actor,
                ResponsibilityRole role,
                Optional<TeamMemberId> memberId) {
            return ResponsibilityAssignment.reconstitute(
                    ResponsibilityAssignmentId.generate(), scope, workItem.id(), role,
                    actor.id(), actor.type(), memberId, ResponsibilityAssignmentStatus.ACTIVE,
                    actor.id(), POLICY_AT, POLICY_AT, Optional.empty(), Optional.empty(), 0,
                    AuditMetadata.createdBy(actor.id(), POLICY_AT));
        }
    }

    /** The real adjudication, so the fixture cannot hand the controller an invented verdict. */
    private static List<TaskControlAction> actions(TaskExecution execution, boolean authorized) {
        return new TaskControlAvailabilityProjector().all(execution, authorized);
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId + "/tasks";
    }
}
