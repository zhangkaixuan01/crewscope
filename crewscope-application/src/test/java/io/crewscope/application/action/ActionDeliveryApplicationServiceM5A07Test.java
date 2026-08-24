package io.crewscope.application.action;

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
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ActionAuthoritySnapshot;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionCancellationReason;
import io.crewscope.domain.action.ActionDependency;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.ManualResolutionReason;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.action.ProviderAuthorizationReference;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.action.ResponsibilityReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** M5-A07 proof for exact confirmation, replay and atomic cancellation semantics. */
class ActionDeliveryApplicationServiceM5A07Test {

    @Test
    void planReplayRevalidatesCurrentOwnerWithoutRequiringAnExpiredCatalogSnapshot() {
        Fixture fixture = new Fixture();
        CommandReceipt original = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(fixture.commandReceipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(original));

        var replay = fixture.service().plan(
                fixture.context("plan-replay"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                new PlanSourceDeliveryActionRequest(
                        fixture.decision.id(),
                        fixture.providerBindingId,
                        new ExternalRepositoryId("101"),
                        Optional.empty(),
                        "Reviewed delivery",
                        "Create the reviewed Draft PR"));

        assertTrue(replay.replayed());
        assertEquals(original, replay.receipt());
        verify(fixture.planning, never()).resolve(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.bundles, never()).insert(any());
    }

    @Test
    void confirmsExactLatestDigestAndSchedulesTheOrderedGraphOnce() {
        Fixture fixture = new Fixture();

        var execution = fixture.service().confirm(
                fixture.context("confirm-once"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                fixture.bundleId,
                0,
                fixture.bundleDigest.toString());

        assertTrue(execution.result().isPresent());
        assertEquals(2, fixture.dispatchQueue.size());
        assertEquals(ActionDispatchStatus.READY, fixture.dispatchQueue.get(0).status());
        assertEquals(List.of(), fixture.dispatchQueue.get(0).dependencies());
        assertEquals(
                List.of(new ActionDependency(fixture.pushId)),
                fixture.dispatchQueue.get(1).dependencies());
        verify(fixture.confirmations).insert(any());
        verify(fixture.commandEvents).bundleConfirmed(any(), any(), any(), any());
    }

    @Test
    void exactIdempotentReplayReturnsTheOriginalReceiptWithoutDuplicateDispatches() {
        Fixture fixture = new Fixture();
        var first = fixture.service().confirm(
                fixture.context("confirm-replay"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                fixture.bundleId,
                0,
                fixture.bundleDigest.toString());
        when(fixture.commandReceipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(first.receipt()));

        var replay = fixture.service().confirm(
                fixture.context("confirm-replay"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                fixture.bundleId,
                0,
                fixture.bundleDigest.toString());

        assertTrue(replay.replayed());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(2, fixture.dispatchQueue.size());
        verify(fixture.dispatches).insertAll(any());
    }

    @Test
    void stalePageDigestIsRejectedBeforeConfirmationOrDispatchPersistence() {
        Fixture fixture = new Fixture();

        assertThrows(DomainValidationException.class, () -> fixture.service().confirm(
                fixture.context("stale-page"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                fixture.bundleId,
                0,
                "f".repeat(64)));

        verify(fixture.confirmations, never()).insert(any());
        verify(fixture.dispatches, never()).insertAll(any());
    }

    @Test
    void cancellationWritesOneReceiptForEachReadyActionAndReplayDoesNotRepeatIt() {
        Fixture fixture = new Fixture();
        var confirmed = fixture.service().confirm(
                fixture.context("confirm-before-cancel"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                fixture.bundleId,
                0,
                fixture.bundleDigest.toString()).result().orElseThrow();

        var cancelled = fixture.service().cancel(
                fixture.context("cancel-once"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                confirmed.id(),
                0,
                ActionCancellationReason.MEMBER_CANCELLED);

        assertEquals(2, fixture.actionReceiptRows.size());
        assertTrue(fixture.dispatchQueue.stream()
                .allMatch(value -> value.status() == ActionDispatchStatus.CANCELLED));
        when(fixture.commandReceipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(cancelled.receipt()));
        var replay = fixture.service().cancel(
                fixture.context("cancel-once"),
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                confirmed.id(),
                0,
                ActionCancellationReason.MEMBER_CANCELLED);
        assertTrue(replay.replayed());
        assertEquals(2, fixture.actionReceiptRows.size());
    }

    @Test
    void manualResolutionUsesAReceiptAndExactReplayDoesNotRepeatTheConclusion() {
        Fixture fixture = new Fixture();
        ActionDispatchId dispatchId = ActionDispatchId.generate();
        ActionDispatch current = mock(ActionDispatch.class);
        when(current.id()).thenReturn(dispatchId);
        when(current.bundleId()).thenReturn(fixture.bundleId);
        when(current.version()).thenReturn(3L);
        ActionDispatch committed = mock(ActionDispatch.class);
        when(committed.id()).thenReturn(dispatchId);
        when(committed.version()).thenReturn(4L);
        when(fixture.dispatches.findById(fixture.organizationId, dispatchId))
                .thenReturn(Optional.of(current));
        when(fixture.manualResolution.resolve(any(), any())).thenReturn(committed);
        TeamCommandContext context = fixture.context("manual-resolution-replay");

        var first = fixture.service().resolveManually(
                context,
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                dispatchId,
                3,
                ActionReceiptResult.MANUALLY_FAILED,
                Optional.empty(),
                Optional.empty(),
                ManualResolutionReason.NO_EXTERNAL_OBJECT_VERIFIED,
                "Provider audit proves no external object exists");
        when(fixture.commandReceipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(first.receipt()));

        var replay = fixture.service().resolveManually(
                context,
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                dispatchId,
                3,
                ActionReceiptResult.MANUALLY_FAILED,
                Optional.empty(),
                Optional.empty(),
                ManualResolutionReason.NO_EXTERNAL_OBJECT_VERIFIED,
                "Provider audit proves no external object exists");

        assertTrue(replay.replayed());
        assertEquals(first.receipt(), replay.receipt());
        verify(fixture.manualResolution, times(1))
                .resolve(any(), eq(context.correlationId()));
    }

    private static final class Fixture {
        private final UtcTimestamp now = UtcTimestamp.parse("2026-08-24T14:00:00Z");
        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId,
                teamId,
                WorkspaceId.generate(),
                WorkProjectId.generate());
        private final WorkItemId workItemId = WorkItemId.generate();
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final Principal owner = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                now);
        private final ActionBundleId bundleId = ActionBundleId.generate();
        private final ActionBundleDigest bundleDigest = new ActionBundleDigest(
                TaskFactHash.sha256("bundle"));
        private final PlannedActionId pushId = PlannedActionId.generate();
        private final PlannedActionId pullRequestId = PlannedActionId.generate();
        private final ProviderBindingId providerBindingId = ProviderBindingId.generate();
        private final ConnectionId connectionId = ConnectionId.generate();
        private final ActionAuthoritySnapshot authority = mock(ActionAuthoritySnapshot.class);
        private final ActionAuthorityFacts facts = mock(ActionAuthorityFacts.class);
        private final ReviewDecisionReference decision = mock(ReviewDecisionReference.class);
        private final ResponsibilityAssignment ownerAssignment = mock(ResponsibilityAssignment.class);
        private final PlannedAction push = action(pushId, 1, List.of());
        private final PlannedAction pullRequest = action(
                pullRequestId, 2, List.of(new ActionDependency(pushId)));
        private final ActionBundle bundle = mock(ActionBundle.class);
        private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
        private final TaskRepository tasks = mock(TaskRepository.class);
        private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        private final ResponsibilityAssignmentRepository responsibilities =
                mock(ResponsibilityAssignmentRepository.class);
        private final ActionDeliveryPlanningResolver planning =
                mock(ActionDeliveryPlanningResolver.class);
        private final ActionAuthorityFactsResolver authorityResolver =
                mock(ActionAuthorityFactsResolver.class);
        private final ActionBundleRepository bundles = mock(ActionBundleRepository.class);
        private final ConfirmationRepository confirmations = mock(ConfirmationRepository.class);
        private final ActionDispatchRepository dispatches = mock(ActionDispatchRepository.class);
        private final ActionReceiptRepository actionReceipts = mock(ActionReceiptRepository.class);
        private final ExternalResultRepository externalResults = mock(ExternalResultRepository.class);
        private final ActionManualResolutionService manualResolution =
                mock(ActionManualResolutionService.class);
        private final ActionCommandEventPublisher commandEvents =
                mock(ActionCommandEventPublisher.class);
        private final ActionWorkerEventPublisher workerEvents =
                mock(ActionWorkerEventPublisher.class);
        private final CommandReceiptStore commandReceipts = mock(CommandReceiptStore.class);
        private final List<ActionDispatch> dispatchQueue = new ArrayList<>();
        private final Map<PlannedActionId, ActionReceipt> actionReceiptRows = new HashMap<>();
        private io.crewscope.domain.action.Confirmation storedConfirmation;

        private Fixture() {
            Task task = mock(Task.class);
            when(task.id()).thenReturn(taskId);
            when(task.scope()).thenReturn(scope);
            when(task.workItemId()).thenReturn(workItemId);
            when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
            when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
            TaskExecution execution = mock(TaskExecution.class);
            when(execution.id()).thenReturn(executionId);
            when(execution.taskId()).thenReturn(taskId);
            when(execution.scope()).thenReturn(scope);
            when(executions.findById(organizationId, executionId))
                    .thenReturn(Optional.of(execution));
            when(ownerAssignment.isActive()).thenReturn(true);
            when(ownerAssignment.role()).thenReturn(ResponsibilityRole.OWNER);
            when(ownerAssignment.actorPrincipalId()).thenReturn(owner.id());
            when(ownerAssignment.scope()).thenReturn(scope);
            when(responsibilities.findActiveOwner(organizationId, workItemId))
                    .thenReturn(Optional.of(ownerAssignment));
            when(authority.scope()).thenReturn(scope);
            when(authority.workItemId()).thenReturn(workItemId);
            when(authority.taskId()).thenReturn(taskId);
            when(authority.taskExecutionId()).thenReturn(executionId);
            when(authority.reviewDecision()).thenReturn(decision);
            when(decision.id()).thenReturn(ReviewDecisionId.generate());
            when(authority.responsibility()).thenReturn(new ResponsibilityReference(
                    ResponsibilityAssignmentId.generate(),
                    0,
                    ResponsibilityRole.OWNER,
                    owner.id()));
            when(authority.providerAuthorization()).thenReturn(new ProviderAuthorizationReference(
                    providerBindingId,
                    0,
                    ProviderDefinitionId.generate(),
                    1,
                    ProviderImplementationId.generate(),
                    1,
                    ProviderType.SOURCE_CODE,
                    ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT,
                    connectionId,
                    0,
                    ConnectionGrantId.generate(),
                    0,
                    TaskFactHash.sha256("access")));
            when(bundle.id()).thenReturn(bundleId);
            when(bundle.authority()).thenReturn(authority);
            when(bundle.actions()).thenReturn(List.of(push, pullRequest));
            when(bundle.digest()).thenReturn(bundleDigest);
            when(bundle.version()).thenReturn(0L);
            when(bundle.validUntil()).thenReturn(UtcTimestamp.parse("2026-08-24T14:10:00Z"));
            when(bundles.findById(organizationId, bundleId)).thenReturn(Optional.of(bundle));
            when(bundles.findByReviewDecision(organizationId, decision.id()))
                    .thenReturn(Optional.of(bundle));
            when(planning.resolve(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ActionDeliveryPlanningFacts(
                            facts,
                            new RepositoryBranchReference(
                                    "refs/heads/crewscope/tasks/00000000-0000-0000-0000-000000000001/attempt-1"),
                            "github:repository:crewscope/crewscope-java"));
            when(authorityResolver.resolveCurrent(authority)).thenReturn(facts);
            when(confirmations.findByBundle(organizationId, bundleId)).thenAnswer(ignored ->
                    Optional.ofNullable(storedConfirmation));
            when(confirmations.insert(any())).thenAnswer(invocation -> {
                storedConfirmation = invocation.getArgument(0);
                return storedConfirmation;
            });
            when(confirmations.update(any())).thenAnswer(invocation -> {
                storedConfirmation = invocation.getArgument(0);
                return storedConfirmation;
            });
            when(confirmations.findById(any(), any())).thenAnswer(ignored ->
                    Optional.ofNullable(storedConfirmation));
            when(dispatches.insertAll(any())).thenAnswer(invocation -> {
                List<ActionDispatch> values = invocation.getArgument(0);
                dispatchQueue.clear();
                dispatchQueue.addAll(values);
                return List.copyOf(dispatchQueue);
            });
            when(dispatches.findByBundle(organizationId, bundleId))
                    .thenAnswer(ignored -> List.copyOf(dispatchQueue));
            when(dispatches.update(any())).thenAnswer(invocation -> {
                ActionDispatch value = invocation.getArgument(0);
                dispatchQueue.replaceAll(existing -> existing.id().equals(value.id())
                        ? value
                        : existing);
                return value;
            });
            when(actionReceipts.findReceiptByAction(any(), any())).thenAnswer(invocation ->
                    Optional.ofNullable(actionReceiptRows.get(invocation.getArgument(1))));
            when(actionReceipts.insertIfAbsent(any())).thenAnswer(invocation -> {
                ActionReceipt value = invocation.getArgument(0);
                ActionReceipt previous = actionReceiptRows.putIfAbsent(value.actionId(), value);
                return new ActionReceiptInsertResult(
                        previous == null, previous == null ? value : previous);
            });
            when(commandReceipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
            when(commandEvents.bundleConfirmed(any(), any(), any(), any()))
                    .thenReturn(UUID.randomUUID());
            when(commandEvents.confirmationCancelled(any(), any(), any(), any()))
                    .thenReturn(UUID.randomUUID());
        }

        private PlannedAction action(
                PlannedActionId id, int sequence, List<ActionDependency> dependencies) {
            PlannedAction value = mock(PlannedAction.class);
            when(value.id()).thenReturn(id);
            when(value.sequence()).thenReturn(sequence);
            when(value.dependencies()).thenReturn(dependencies);
            when(value.digest()).thenReturn(new ActionDigest(TaskFactHash.sha256(id.toString())));
            when(value.authority()).thenReturn(authority);
            when(value.validUntil()).thenReturn(UtcTimestamp.parse("2026-08-24T14:10:00Z"));
            when(value.parameters()).thenReturn(new PushBranchActionParameters(
                    new ExternalRepositoryId("101"),
                    new RepositoryBranchReference(
                            "refs/heads/crewscope/tasks/00000000-0000-0000-0000-000000000001/attempt-1"),
                    new io.crewscope.domain.coding.RepositoryCommitId("a".repeat(40)),
                    Optional.empty(),
                    connectionId));
            return value;
        }

        private TeamCommandContext context(String key) {
            return new TeamCommandContext(
                    new TeamAccessContext(owner, false),
                    new io.crewscope.application.command.IdempotencyKey(key),
                    UUID.randomUUID(),
                    Optional.empty());
        }

        private ActionDeliveryApplicationService service() {
            return new ActionDeliveryApplicationService(
                    accessPolicy,
                    tasks,
                    executions,
                    responsibilities,
                    planning,
                    authorityResolver,
                    bundles,
                    confirmations,
                    dispatches,
                    actionReceipts,
                    externalResults,
                    manualResolution,
                    commandEvents,
                    workerEvents,
                    commandReceipts,
                    immediateTransactions(),
                    () -> now);
        }

        private static TransactionExecutor immediateTransactions() {
            return new TransactionExecutor() {
                @Override
                public <T> T required(Supplier<T> operation) {
                    return operation.get();
                }
            };
        }
    }
}
