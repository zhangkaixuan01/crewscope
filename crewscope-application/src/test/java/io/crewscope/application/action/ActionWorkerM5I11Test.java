package io.crewscope.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.github.GitHubDraftPullRequestErrorCode;
import io.crewscope.application.github.GitHubDraftPullRequestException;
import io.crewscope.application.github.GitHubDraftPullRequestOutcome;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubDraftPullRequestResult;
import io.crewscope.application.github.GitHubPullRequestState;
import io.crewscope.application.github.GitHubPushOutcome;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubPushResult;
import io.crewscope.application.github.GitHubRepositoryPolicy;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ActionAuthoritySnapshot;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionClaim;
import io.crewscope.domain.action.ActionClaimMode;
import io.crewscope.domain.action.ActionDependency;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionFencingToken;
import io.crewscope.domain.action.ActionIdempotencyKey;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionWorkerId;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.action.ProviderAuthorizationReference;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** M5-I11 orchestration proof for transaction boundaries, dependencies and partial success. */
class ActionWorkerM5I11Test {

    @Test
    void executesPushThenDraftPullRequestOnlyOutsideTransactionsAndWritesTwoReceipts() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate, fixture.pullRequestCandidate);
        when(fixture.pushPort.pushBranch(any())).thenAnswer(invocation -> {
            assertFalse(fixture.transactions.active());
            return fixture.pushResult();
        });
        when(fixture.pullRequestPort.ensureDraft(any())).thenAnswer(invocation -> {
            assertFalse(fixture.transactions.active());
            return fixture.pullRequestResult();
        });

        ActionWorkerBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionWorkerBatchResult(2, 2, 0, 0, 0), result);
        assertEquals(2, fixture.committedReceipts.size());
        InOrder order = inOrder(fixture.pushPort, fixture.pullRequestPort);
        order.verify(fixture.pushPort).pushBranch(any());
        order.verify(fixture.pullRequestPort).ensureDraft(any());
    }

    @Test
    void uncertainPushStopsTheBundleWithoutReceiptOrDraftPullRequest() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate);
        when(fixture.pushPort.pushBranch(any())).thenThrow(
                new io.crewscope.application.github.GitHubPushException(
                        io.crewscope.application.github.GitHubPushErrorCode.UNKNOWN,
                        "GitHub Push outcome requires reconciliation"));

        ActionWorkerBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionWorkerBatchResult(1, 0, 0, 1, 0), result);
        assertEquals(0, fixture.committedReceipts.size());
        verify(fixture.pullRequestPort, never()).ensureDraft(any());
        verify(fixture.pushClaimed).markUnknown(anyLong(), any(), any());
    }

    @Test
    void definiteDraftPullRequestFailureKeepsSuccessfulPushAndNeverRepeatsIt() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate, fixture.pullRequestCandidate);
        when(fixture.pushPort.pushBranch(any())).thenReturn(fixture.pushResult());
        when(fixture.pullRequestPort.ensureDraft(any())).thenThrow(
                new GitHubDraftPullRequestException(
                        GitHubDraftPullRequestErrorCode.PULL_REQUEST_CONFLICT,
                        "Existing GitHub Pull Request conflicts with the confirmed action"));

        ActionWorkerBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionWorkerBatchResult(2, 1, 1, 0, 0), result);
        assertEquals(2, fixture.committedReceipts.size());
        verify(fixture.pushPort).pushBranch(any());
        verify(fixture.pullRequestPort).ensureDraft(any());
    }

    @Test
    void claimEventFailureEscapesTheTransactionAndPreventsTheProviderCall() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate);
        ActionWorkerEventPublisher events = mock(ActionWorkerEventPublisher.class);
        doThrow(new IllegalStateException("event store unavailable"))
                .when(events)
                .dispatchTransitioned(any(), any(), any());

        assertThrows(IllegalStateException.class, () ->
                fixture.worker(events).runOnce(fixture.organizationId));

        verify(fixture.pushPort, never()).pushBranch(any());
        verify(fixture.pullRequestPort, never()).ensureDraft(any());
    }

    @Test
    void policyFailureBeforeProviderInvocationEscapesWithoutInventingAnUnknownWrite() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate);
        fixture.policyResolver = (ignoredFacts, ignoredAction) -> {
            throw new IllegalStateException("policy repository unavailable");
        };

        assertThrows(IllegalStateException.class,
                () -> fixture.worker().runOnce(fixture.organizationId));

        verify(fixture.pushPort, never()).pushBranch(any());
        verify(fixture.pushClaimed, never()).markUnknown(anyLong(), any(), any());
    }

    @Test
    void unclassifiedFailureInsideProviderInvocationUsesUnknownRecovery() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate);
        when(fixture.pushPort.pushBranch(any()))
                .thenThrow(new IllegalStateException("provider invocation interrupted"));

        ActionWorkerBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionWorkerBatchResult(1, 0, 0, 1, 0), result);
        verify(fixture.pushPort).pushBranch(any());
        verify(fixture.pushClaimed).markUnknown(anyLong(), any(), any());
    }

    @Test
    void rateLimitedPushUsesOnlyTheProvenNoSideEffectRetryPath() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate);
        when(fixture.pushPort.pushBranch(any())).thenThrow(
                new io.crewscope.application.github.GitHubProviderException(
                        io.crewscope.application.github.GitHubProviderErrorCode.RATE_LIMITED,
                        "GitHub rate limited"));
        ActionDispatch retry = fixture.terminal(
                fixture.pushClaimed, ActionDispatchStatus.READY);
        when(fixture.pushClaimed.scheduleRetry(anyLong(), any(), any(), any()))
                .thenReturn(retry);

        ActionWorkerBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionWorkerBatchResult(1, 0, 0, 0, 1), result);
        verify(fixture.pushClaimed).scheduleRetry(anyLong(), any(), any(), any());
        verify(fixture.pushClaimed, never()).markUnknown(anyLong(), any(), any());
        assertEquals(0, fixture.committedReceipts.size());
    }

    @Test
    void uncertainDraftPullRequestKeepsThePushReceiptAndNeverRepeatsThePush() {
        Fixture fixture = new Fixture();
        fixture.queue(fixture.pushCandidate, fixture.pullRequestCandidate);
        when(fixture.pushPort.pushBranch(any())).thenReturn(fixture.pushResult());
        when(fixture.pullRequestPort.ensureDraft(any())).thenThrow(
                new GitHubDraftPullRequestException(
                        GitHubDraftPullRequestErrorCode.UNKNOWN,
                        "Draft Pull Request outcome requires reconciliation"));

        ActionWorkerBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionWorkerBatchResult(2, 1, 0, 1, 0), result);
        assertEquals(1, fixture.committedReceipts.size());
        verify(fixture.pushPort).pushBranch(any());
        verify(fixture.pullRequestPort).ensureDraft(any());
        verify(fixture.pullRequestClaimed).markUnknown(anyLong(), any(), any());
    }

    private static final class Fixture {

        private final UtcTimestamp now = UtcTimestamp.parse("2026-08-23T12:00:00Z");
        private final OrganizationId organizationId = OrganizationId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId,
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        private final PrincipalId ownerId = PrincipalId.generate();
        private final ConnectionId connectionId = ConnectionId.generate();
        private final ExternalRepositoryId repositoryId = new ExternalRepositoryId("101");
        private final RepositoryCommitId baseline = new RepositoryCommitId("a".repeat(40));
        private final RepositoryCommitId delivery = new RepositoryCommitId("b".repeat(40));
        private final RepositoryBranchName defaultBranch = new RepositoryBranchName("main");
        private final RepositoryBranchReference deliveryBranch =
                new RepositoryBranchReference("refs/heads/crewscope/m5-i11");
        private final ActionBundleId bundleId = ActionBundleId.generate();
        private final ActionBundleDigest bundleDigest =
                new ActionBundleDigest(TaskFactHash.sha256("bundle"));
        private final PlannedActionId pushId = PlannedActionId.generate();
        private final PlannedActionId pullRequestId = PlannedActionId.generate();
        private final ActionDigest pushDigest = new ActionDigest(TaskFactHash.sha256("push"));
        private final ActionDigest pullRequestDigest =
                new ActionDigest(TaskFactHash.sha256("pull-request"));
        private final PushBranchActionParameters pushParameters = new PushBranchActionParameters(
                repositoryId, deliveryBranch, delivery, Optional.of(baseline), connectionId);
        private final CreateDraftPullRequestActionParameters pullRequestParameters =
                new CreateDraftPullRequestActionParameters(
                        repositoryId,
                        deliveryBranch.shortName(),
                        defaultBranch,
                        delivery,
                        "M5 I11 delivery",
                        "Reviewed delivery",
                        true,
                        connectionId);
        private final ProviderAuthorizationReference provider =
                new ProviderAuthorizationReference(
                        ProviderBindingId.generate(),
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
                        TaskFactHash.sha256("access"));
        private final ActionAuthoritySnapshot authority = mock(ActionAuthoritySnapshot.class);
        private final ActionAuthorityFacts facts = mock(ActionAuthorityFacts.class);
        private final ConnectionGrant grant = mock(ConnectionGrant.class);
        private final PlannedAction pushAction = action(
                pushId, pushDigest, pushParameters, List.of());
        private final PlannedAction pullRequestAction = action(
                pullRequestId,
                pullRequestDigest,
                pullRequestParameters,
                List.of(new ActionDependency(pushId)));
        private final ActionBundle bundle = mock(ActionBundle.class);
        private final io.crewscope.domain.action.Confirmation confirmation =
                mock(io.crewscope.domain.action.Confirmation.class);
        private final ActionDispatch pushCandidate = dispatch(
                pushId, pushDigest, List.of(), 1);
        private final ActionDispatch pullRequestCandidate = dispatch(
                pullRequestId,
                pullRequestDigest,
                List.of(new ActionDependency(pushId)),
                2);
        private final ActionClaim pushClaim = claim(pushCandidate, pushId, 1);
        private final ActionClaim pullRequestClaim = claim(pullRequestCandidate, pullRequestId, 1);
        private final ActionDispatch pushClaimed = claimed(pushCandidate, pushClaim, 1);
        private final ActionDispatch pullRequestClaimed = claimed(
                pullRequestCandidate, pullRequestClaim, 1);
        private final ActionDispatch pushTerminal = terminal(pushClaimed, ActionDispatchStatus.SUCCEEDED);
        private final ActionDispatch pullRequestTerminal = terminal(
                pullRequestClaimed, ActionDispatchStatus.SUCCEEDED);
        private final ActionDispatchRepository dispatches = mock(ActionDispatchRepository.class);
        private final ActionReceiptRepository receipts = mock(ActionReceiptRepository.class);
        private final ActionBundleRepository bundles = mock(ActionBundleRepository.class);
        private final ConfirmationRepository confirmations = mock(ConfirmationRepository.class);
        private final ActionAuthorityFactsResolver authorityResolver =
                mock(ActionAuthorityFactsResolver.class);
        private final GitHubPushPort pushPort = mock(GitHubPushPort.class);
        private final GitHubDraftPullRequestPort pullRequestPort =
                mock(GitHubDraftPullRequestPort.class);
        private final TrackingTransactions transactions = new TrackingTransactions();
        private final Map<PlannedActionId, ActionReceipt> committedReceipts = new HashMap<>();
        private final List<ActionDispatch> queue = new ArrayList<>();
        private final AtomicInteger queueIndex = new AtomicInteger();
        private GitHubRepositoryPolicyResolver policyResolver = (ignoredFacts, ignoredAction) ->
                new GitHubRepositoryPolicy(
                        java.util.Set.of("crewscope/crewscope-java"),
                        java.util.Set.of("crewscope"),
                        true,
                        true,
                        false);

        private Fixture() {
            var target = new io.crewscope.domain.action.ActionTargetPrecondition(
                    RepositoryBindingId.generate(),
                    0,
                    new RepositoryKey("crewscope-java"),
                    defaultBranch,
                    new CodingTargetSnapshotReference(
                            CodingTargetSnapshotId.generate(),
                            1,
                            TaskFactHash.sha256("target")),
                    baseline,
                    delivery);
            when(authority.providerAuthorization()).thenReturn(provider);
            when(authority.targetPrecondition()).thenReturn(target);
            when(authority.responsibility()).thenReturn(
                    new io.crewscope.domain.action.ResponsibilityReference(
                            io.crewscope.domain.responsibility.ResponsibilityAssignmentId.generate(),
                            0,
                            io.crewscope.domain.responsibility.ResponsibilityRole.OWNER,
                            ownerId));
            ProviderOwner providerOwner = mock(ProviderOwner.class);
            when(providerOwner.organizationId()).thenReturn(organizationId);
            when(grant.grantee()).thenReturn(providerOwner);
            when(facts.connectionGrant()).thenReturn(grant);
            when(bundle.id()).thenReturn(bundleId);
            when(bundle.digest()).thenReturn(bundleDigest);
            when(bundle.authority()).thenReturn(authority);
            when(bundle.actions()).thenReturn(List.of(pushAction, pullRequestAction));
            when(bundles.findById(organizationId, bundleId)).thenReturn(Optional.of(bundle));
            when(confirmations.findById(any(), any())).thenReturn(Optional.of(confirmation));
            when(authorityResolver.resolveCurrent(authority)).thenReturn(facts);
            when(dispatches.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(dispatches.findById(organizationId, pushCandidate.id()))
                    .thenReturn(Optional.of(pushClaimed));
            when(dispatches.findById(organizationId, pullRequestCandidate.id()))
                    .thenReturn(Optional.of(pullRequestClaimed));
            when(receipts.findReceiptByAction(any(), any())).thenAnswer(invocation ->
                    Optional.ofNullable(committedReceipts.get(invocation.getArgument(1))));
            when(receipts.insertIfAbsent(any())).thenAnswer(invocation -> {
                ActionReceipt receipt = invocation.getArgument(0);
                ActionReceipt previous = committedReceipts.putIfAbsent(receipt.actionId(), receipt);
                return new ActionReceiptInsertResult(
                        previous == null, previous == null ? receipt : previous);
            });
            when(pushClaimed.completeClaimed(anyLong(), any(), any(), any()))
                    .thenReturn(pushTerminal);
            when(pullRequestClaimed.completeClaimed(anyLong(), any(), any(), any()))
                    .thenReturn(pullRequestTerminal);
            ActionDispatch pushUnknown = terminal(
                    pushClaimed, ActionDispatchStatus.UNKNOWN);
            when(pushClaimed.markUnknown(anyLong(), any(), any()))
                    .thenReturn(pushUnknown);
            ActionDispatch pullRequestUnknown = terminal(
                    pullRequestClaimed, ActionDispatchStatus.UNKNOWN);
            when(pullRequestClaimed.markUnknown(anyLong(), any(), any()))
                    .thenReturn(pullRequestUnknown);
        }

        private void queue(ActionDispatch... values) {
            queue.addAll(List.of(values));
            when(dispatches.lockClaimable(any(), any(), anyInt())).thenAnswer(invocation -> {
                int index = queueIndex.getAndIncrement();
                return index < queue.size() ? List.of(queue.get(index)) : List.of();
            });
        }

        private ActionWorker worker() {
            return worker(ActionWorkerEventPublisher.noOp());
        }

        private ActionWorker worker(ActionWorkerEventPublisher events) {
            return new ActionWorker(
                    dispatches,
                    receipts,
                    bundles,
                    confirmations,
                    authorityResolver,
                    policyResolver,
                    pushPort,
                    pullRequestPort,
                    events,
                    transactions,
                    () -> now,
                    new ActionWorkerId("m5-i11-worker"),
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(15),
                    10);
        }

        private GitHubPushResult pushResult() {
            return new GitHubPushResult(
                    GitHubPushOutcome.PUSHED, repositoryId, deliveryBranch, delivery);
        }

        private GitHubDraftPullRequestResult pullRequestResult() {
            return new GitHubDraftPullRequestResult(
                    GitHubDraftPullRequestOutcome.CREATED,
                    connectionId,
                    repositoryId,
                    "42",
                    42,
                    URI.create("https://github.com/crewscope/crewscope-java/pull/42"),
                    deliveryBranch.shortName(),
                    defaultBranch,
                    delivery,
                    TaskFactHash.sha256("title").value(),
                    TaskFactHash.sha256("body").value(),
                    true,
                    GitHubPullRequestState.OPEN,
                    now);
        }

        private PlannedAction action(
                PlannedActionId id,
                ActionDigest digest,
                io.crewscope.domain.action.ActionParameters parameters,
                List<ActionDependency> dependencies) {
            PlannedAction action = mock(PlannedAction.class);
            when(action.id()).thenReturn(id);
            when(action.digest()).thenReturn(digest);
            when(action.kind()).thenReturn(parameters.kind());
            when(action.parameters()).thenReturn(parameters);
            when(action.dependencies()).thenReturn(dependencies);
            return action;
        }

        private ActionDispatch dispatch(
                PlannedActionId actionId,
                ActionDigest digest,
                List<ActionDependency> dependencies,
                int sequence) {
            ActionDispatch dispatch = mock(ActionDispatch.class);
            when(dispatch.id()).thenReturn(ActionDispatchId.generate());
            when(dispatch.scope()).thenReturn(scope);
            when(dispatch.bundleId()).thenReturn(bundleId);
            when(dispatch.bundleDigest()).thenReturn(bundleDigest);
            when(dispatch.confirmationId()).thenReturn(io.crewscope.domain.action.ConfirmationId.generate());
            when(dispatch.actionId()).thenReturn(actionId);
            when(dispatch.actionDigest()).thenReturn(digest);
            when(dispatch.sequence()).thenReturn(sequence);
            when(dispatch.dependencies()).thenReturn(dependencies);
            when(dispatch.status()).thenReturn(ActionDispatchStatus.READY);
            when(dispatch.version()).thenReturn(0L);
            when(dispatch.validUntil()).thenReturn(UtcTimestamp.from(now.value().plusSeconds(600)));
            return dispatch;
        }

        private ActionClaim claim(
                ActionDispatch dispatch, PlannedActionId actionId, long fencing) {
            return new ActionClaim(
                    dispatch.id(),
                    actionId,
                    new ActionWorkerId("m5-i11-worker"),
                    new ActionFencingToken(fencing),
                    ActionClaimMode.EXECUTE,
                    now,
                    now,
                    UtcTimestamp.from(now.value().plusSeconds(120)));
        }

        private ActionDispatch claimed(
                ActionDispatch candidate, ActionClaim claim, long version) {
            ActionDispatchId dispatchId = candidate.id();
            var confirmationId = candidate.confirmationId();
            ActionDigest actionDigest = candidate.actionDigest();
            int sequence = candidate.sequence();
            List<ActionDependency> dependencies = candidate.dependencies();
            UtcTimestamp validUntil = candidate.validUntil();
            ActionDispatch dispatch = mock(ActionDispatch.class);
            when(dispatch.id()).thenReturn(dispatchId);
            when(dispatch.scope()).thenReturn(scope);
            when(dispatch.bundleId()).thenReturn(bundleId);
            when(dispatch.bundleDigest()).thenReturn(bundleDigest);
            when(dispatch.confirmationId()).thenReturn(confirmationId);
            when(dispatch.actionId()).thenReturn(claim.actionId());
            when(dispatch.actionDigest()).thenReturn(actionDigest);
            when(dispatch.sequence()).thenReturn(sequence);
            when(dispatch.dependencies()).thenReturn(dependencies);
            when(dispatch.status()).thenReturn(ActionDispatchStatus.RUNNING);
            when(dispatch.claim()).thenReturn(Optional.of(claim));
            when(dispatch.idempotencyKey()).thenReturn(ActionIdempotencyKey.derive(
                    organizationId, bundleId, claim.actionId(), actionDigest));
            when(dispatch.version()).thenReturn(version);
            when(dispatch.validUntil()).thenReturn(validUntil);
            when(candidate.claim(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(dispatch);
            return dispatch;
        }

        private ActionDispatch terminal(ActionDispatch source, ActionDispatchStatus status) {
            ActionDispatchId dispatchId = source.id();
            PlannedActionId actionId = source.actionId();
            ActionDispatch terminal = mock(ActionDispatch.class);
            when(terminal.id()).thenReturn(dispatchId);
            when(terminal.scope()).thenReturn(scope);
            when(terminal.bundleId()).thenReturn(bundleId);
            when(terminal.actionId()).thenReturn(actionId);
            when(terminal.status()).thenReturn(status);
            return terminal;
        }
    }

    private static final class TrackingTransactions implements TransactionExecutor {

        private final AtomicInteger depth = new AtomicInteger();

        @Override
        public <T> T required(java.util.function.Supplier<T> operation) {
            depth.incrementAndGet();
            try {
                return operation.get();
            } finally {
                depth.decrementAndGet();
            }
        }

        boolean active() {
            return depth.get() > 0;
        }
    }
}
