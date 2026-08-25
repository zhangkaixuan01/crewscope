package io.crewscope.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.github.GitHubBranchQueryResult;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubRepositoryPolicy;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ActionAuthoritySnapshot;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionClaim;
import io.crewscope.domain.action.ActionClaimMode;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ActionFencingToken;
import io.crewscope.domain.action.ActionIdempotencyKey;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ActionResultSource;
import io.crewscope.domain.action.ActionWorkerId;
import io.crewscope.domain.action.ExternalMergeOutcome;
import io.crewscope.domain.action.ExternalMergeResult;
import io.crewscope.domain.action.ExternalObjectStatus;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalObservation;
import io.crewscope.domain.action.ExternalObservationKey;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ExternalResultSource;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M5-I12 proof that UNKNOWN recovery is query-only, bounded and fenced. */
class ActionReconciliationWorkerM5I12Test {

    @Test
    void matchingRemoteHeadCreatesOneReceiptWithoutCallingThePushWritePath() {
        Fixture fixture = new Fixture();
        when(fixture.pushPort.queryBranch(any())).thenReturn(
                new GitHubBranchQueryResult(Optional.of(fixture.delivery), fixture.now));

        ActionReconciliationBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionReconciliationBatchResult(1, 1, 0, 0, 0), result);
        verify(fixture.pushPort).queryBranch(any());
        verify(fixture.pushPort, never()).pushBranch(any());
        verify(fixture.pullRequestPort, never()).ensureDraft(any());
        verify(fixture.receipts).insertIfAbsent(any());
    }

    @Test
    void providerFailureUsesBoundedBackoffAndEscalatesAtTheAttemptLimit() {
        Fixture fixture = new Fixture();
        when(fixture.pushPort.queryBranch(any()))
                .thenThrow(new io.crewscope.application.github.GitHubProviderException(
                        io.crewscope.application.github.GitHubProviderErrorCode.RATE_LIMITED,
                        "GitHub rate limited"));
        when(fixture.claimed.recordInconclusiveReconciliation(
                        anyLong(), any(), anyInt(), any(), any()))
                .thenReturn(fixture.manualReview);

        ActionReconciliationBatchResult result = fixture.worker(1).runOnce(fixture.organizationId);

        assertEquals(new ActionReconciliationBatchResult(1, 0, 0, 1, 0), result);
        verify(fixture.receipts, never()).insertIfAbsent(any());
        verify(fixture.pushPort, never()).pushBranch(any());
    }

    @Test
    void committedWebhookConvergesBeforeAnyActiveQueryAndNeverCallsAWritePath() {
        Fixture fixture = new Fixture();
        ExternalObservation webhook = fixture.pullRequestWebhook();
        ExternalResult merged = mock(ExternalResult.class);
        when(merged.status()).thenReturn(ExternalObjectStatus.OPEN);
        when(merged.lastSource()).thenReturn(ExternalResultSource.WEBHOOK);
        when(merged.providerVersion()).thenReturn(Optional.of(42L));
        when(merged.providerUpdatedAt()).thenReturn(Optional.of(fixture.now));
        when(merged.identity()).thenReturn(webhook.identity());
        when(merged.lastEvidence()).thenReturn(webhook.evidence());
        when(fixture.observations.findObservationsByAction(
                        fixture.organizationId, fixture.actionId))
                .thenReturn(List.of(webhook));
        when(fixture.externalResults.merge(any(), any(), any(), any()))
                .thenReturn(new ExternalMergeResult(merged, ExternalMergeOutcome.APPLIED));
        when(fixture.action.kind()).thenReturn(io.crewscope.domain.action.ActionKind.CREATE_DRAFT_PR);
        when(fixture.action.parameters()).thenReturn(fixture.pullRequestParameters());

        ActionReconciliationBatchResult result = fixture.worker().runOnce(fixture.organizationId);

        assertEquals(new ActionReconciliationBatchResult(1, 1, 0, 0, 0), result);
        verify(fixture.pullRequestPort, never()).queryDraft(any());
        verify(fixture.pullRequestPort, never()).ensureDraft(any());
        verify(fixture.pushPort, never()).pushBranch(any());
    }

    @Test
    void replacementFencingTokenRejectsTheLateQueryResultBeforeReceiptCommit() {
        Fixture fixture = new Fixture();
        ActionClaim replacement = fixture.claim(2);
        ActionDispatch replaced = fixture.claimed(replacement, 2);
        when(fixture.dispatches.findById(fixture.organizationId, fixture.dispatchId))
                .thenReturn(Optional.of(replaced));
        when(fixture.pushPort.queryBranch(any())).thenReturn(
                new GitHubBranchQueryResult(Optional.of(fixture.delivery), fixture.now));

        assertThrows(IllegalStateException.class,
                () -> fixture.worker().runOnce(fixture.organizationId));

        verify(fixture.receipts, never()).insertIfAbsent(any());
        verify(fixture.pushPort, never()).pushBranch(any());
    }

    @Test
    void receiptEventFailureEscapesForTheOuterTransactionToRollBack() {
        Fixture fixture = new Fixture();
        when(fixture.pushPort.queryBranch(any())).thenReturn(
                new GitHubBranchQueryResult(Optional.of(fixture.delivery), fixture.now));
        ActionWorkerEventPublisher events = mock(ActionWorkerEventPublisher.class);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(events)
                .receiptRecorded(any(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> fixture.worker(events, 5).runOnce(fixture.organizationId));

        verify(fixture.claimed, never()).recordInconclusiveReconciliation(
                anyLong(), any(), anyInt(), any(), any());
    }

    @Test
    void revokedAuthorityNeverReopensAWritePathAndEscalatesToManualReview() {
        Fixture fixture = new Fixture();
        when(fixture.authorityResolver.resolveCurrent(fixture.authority))
                .thenThrow(new io.crewscope.domain.shared.error.DomainValidationException(
                        "actionAuthority", "Connection grant was revoked"));
        when(fixture.claimed.recordInconclusiveReconciliation(
                        anyLong(), any(), anyInt(), any(), any()))
                .thenReturn(fixture.manualReview);

        ActionReconciliationBatchResult result = fixture.worker(1)
                .runOnce(fixture.organizationId);

        assertEquals(new ActionReconciliationBatchResult(1, 0, 0, 1, 0), result);
        verify(fixture.pushPort, never()).queryBranch(any());
        verify(fixture.pullRequestPort, never()).queryDraft(any());
        verify(fixture.pushPort, never()).pushBranch(any());
        verify(fixture.pullRequestPort, never()).ensureDraft(any());
        verify(fixture.receipts, never()).insertIfAbsent(any());
    }

    private static final class Fixture {

        private final UtcTimestamp now = UtcTimestamp.parse("2026-08-24T08:00:00Z");
        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        private final PrincipalId ownerId = PrincipalId.generate();
        private final ConnectionId connectionId = ConnectionId.generate();
        private final RepositoryCommitId delivery = new RepositoryCommitId("b".repeat(40));
        private final PlannedActionId actionId = PlannedActionId.generate();
        private final ActionDigest actionDigest = new ActionDigest(TaskFactHash.sha256("action"));
        private final ActionBundleId bundleId = ActionBundleId.generate();
        private final ActionBundleDigest bundleDigest =
                new ActionBundleDigest(TaskFactHash.sha256("bundle"));
        private final ActionDispatchId dispatchId = ActionDispatchId.generate();
        private final io.crewscope.domain.action.ConfirmationId confirmationId =
                io.crewscope.domain.action.ConfirmationId.generate();
        private final PushBranchActionParameters pushParameters = new PushBranchActionParameters(
                new ExternalRepositoryId("101"),
                new RepositoryBranchReference("refs/heads/crewscope/m5-i12"),
                delivery,
                Optional.empty(),
                connectionId);
        private final PlannedAction action = mock(PlannedAction.class);
        private final ActionAuthoritySnapshot authority = mock(ActionAuthoritySnapshot.class);
        private final ActionBundle bundle = mock(ActionBundle.class);
        private final io.crewscope.domain.action.Confirmation confirmation =
                mock(io.crewscope.domain.action.Confirmation.class);
        private final ActionDispatch candidate = mock(ActionDispatch.class);
        private final ActionClaim claim = claim(1);
        private final ActionDispatch claimed = claimed(claim, 1);
        private final ActionDispatch succeeded = terminal(ActionDispatchStatus.SUCCEEDED);
        private final ActionDispatch manualReview = terminal(ActionDispatchStatus.MANUAL_REVIEW);
        private final ActionDispatchRepository dispatches = mock(ActionDispatchRepository.class);
        private final ActionReceiptRepository receipts = mock(ActionReceiptRepository.class);
        private final ActionBundleRepository bundles = mock(ActionBundleRepository.class);
        private final ConfirmationRepository confirmations = mock(ConfirmationRepository.class);
        private final ExternalObservationRepository observations =
                mock(ExternalObservationRepository.class);
        private final ExternalResultMerger externalResults = mock(ExternalResultMerger.class);
        private final ActionAuthorityFactsResolver authorityResolver =
                mock(ActionAuthorityFactsResolver.class);
        private final GitHubPushPort pushPort = mock(GitHubPushPort.class);
        private final GitHubDraftPullRequestPort pullRequestPort =
                mock(GitHubDraftPullRequestPort.class);

        private Fixture() {
            when(action.id()).thenReturn(actionId);
            when(action.digest()).thenReturn(actionDigest);
            when(action.kind()).thenReturn(pushParameters.kind());
            when(action.parameters()).thenReturn(pushParameters);
            when(bundle.id()).thenReturn(bundleId);
            when(bundle.digest()).thenReturn(bundleDigest);
            when(bundle.authority()).thenReturn(authority);
            when(bundle.actions()).thenReturn(List.of(action));
            when(bundles.findById(organizationId, bundleId)).thenReturn(Optional.of(bundle));
            when(confirmation.id()).thenReturn(confirmationId);
            when(confirmations.findById(any(), any())).thenReturn(Optional.of(confirmation));
            when(candidate.id()).thenReturn(dispatchId);
            when(candidate.scope()).thenReturn(scope);
            when(candidate.bundleId()).thenReturn(bundleId);
            when(candidate.bundleDigest()).thenReturn(bundleDigest);
            when(candidate.confirmationId()).thenReturn(confirmationId);
            when(candidate.actionId()).thenReturn(actionId);
            when(candidate.actionDigest()).thenReturn(actionDigest);
            when(candidate.dependencies()).thenReturn(List.of());
            when(candidate.version()).thenReturn(0L);
            when(candidate.claimForReconciliation(
                            anyLong(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(claimed);
            when(dispatches.lockReconciliationCandidates(any(), any(), anyInt()))
                    .thenReturn(List.of(candidate), List.of());
            when(dispatches.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(dispatches.findById(organizationId, dispatchId))
                    .thenReturn(Optional.of(claimed));
            when(observations.findObservationsByAction(organizationId, actionId))
                    .thenReturn(List.of());
            ExternalResult queryProjection = mock(ExternalResult.class);
            when(externalResults.merge(any(), any(), any(), any()))
                    .thenReturn(new ExternalMergeResult(
                            queryProjection, ExternalMergeOutcome.APPLIED));
            when(receipts.insertIfAbsent(any())).thenAnswer(invocation ->
                    new ActionReceiptInsertResult(true, invocation.getArgument(0)));
            when(claimed.completeClaimed(anyLong(), any(), any(), any())).thenReturn(succeeded);
            when(claimed.completeFromObservation(anyLong(), any(), any())).thenReturn(succeeded);
            when(claimed.recordInconclusiveReconciliation(
                            anyLong(), any(), anyInt(), any(), any()))
                    .thenReturn(claimed);

            ActionAuthorityFacts facts = mock(ActionAuthorityFacts.class);
            ConnectionGrant grant = mock(ConnectionGrant.class);
            ProviderOwner owner = mock(ProviderOwner.class);
            when(owner.organizationId()).thenReturn(organizationId);
            when(grant.grantee()).thenReturn(owner);
            when(facts.connectionGrant()).thenReturn(grant);
            when(authorityResolver.resolveCurrent(authority)).thenReturn(facts);
            var provider = mock(io.crewscope.domain.action.ProviderAuthorizationReference.class);
            var grantId = ConnectionGrantId.generate();
            when(provider.connectionId()).thenReturn(connectionId);
            when(provider.connectionVersion()).thenReturn(1L);
            when(provider.grantId()).thenReturn(grantId);
            when(provider.grantVersion()).thenReturn(1L);
            when(provider.providerType()).thenReturn(
                    io.crewscope.domain.provider.ProviderType.SOURCE_CODE);
            when(authority.providerAuthorization()).thenReturn(provider);
            var target = mock(io.crewscope.domain.action.ActionTargetPrecondition.class);
            when(target.defaultBranch()).thenReturn(new RepositoryBranchName("main"));
            when(target.deliveryCommit()).thenReturn(delivery);
            when(authority.targetPrecondition()).thenReturn(target);
            when(authority.responsibility()).thenReturn(
                    new io.crewscope.domain.action.ResponsibilityReference(
                            io.crewscope.domain.responsibility.ResponsibilityAssignmentId.generate(),
                            1,
                            io.crewscope.domain.responsibility.ResponsibilityRole.OWNER,
                            ownerId));
            when(authority.taskExecutionId()).thenReturn(
                    io.crewscope.domain.task.TaskExecutionId.generate());
            var reviewDecision = mock(io.crewscope.domain.review.ReviewDecisionReference.class);
            when(reviewDecision.id()).thenReturn(
                    io.crewscope.domain.review.ReviewDecisionId.generate());
            when(authority.reviewDecision()).thenReturn(reviewDecision);
        }

        private ActionReconciliationWorker worker() {
            return worker(5);
        }

        private ActionReconciliationWorker worker(int maximumAttempts) {
            return worker(ActionWorkerEventPublisher.noOp(), maximumAttempts);
        }

        private ActionReconciliationWorker worker(
                ActionWorkerEventPublisher events, int maximumAttempts) {
            GitHubRepositoryPolicyResolver policy = (facts, plannedAction) ->
                    new GitHubRepositoryPolicy(
                            java.util.Set.of("crewscope/crewscope-java"),
                            java.util.Set.of("crewscope"), true, true, false);
            return new ActionReconciliationWorker(
                    dispatches,
                    receipts,
                    bundles,
                    confirmations,
                    observations,
                    externalResults,
                    authorityResolver,
                    policy,
                    pushPort,
                    pullRequestPort,
                    events,
                    ActionReconciliationObserver.noOp(),
                    new DirectTransactions(),
                    () -> now,
                    new ActionWorkerId("m5-i12-reconciler"),
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(5),
                    Duration.ofHours(1),
                    maximumAttempts,
                    10);
        }

        private ActionClaim claim(long fencing) {
            return new ActionClaim(
                    dispatchId,
                    actionId,
                    new ActionWorkerId("m5-i12-reconciler"),
                    new ActionFencingToken(fencing),
                    ActionClaimMode.RECONCILE,
                    now,
                    now,
                    UtcTimestamp.from(now.value().plusSeconds(120)));
        }

        private ActionDispatch claimed(ActionClaim value, long version) {
            ActionDispatch dispatch = mock(ActionDispatch.class);
            when(dispatch.id()).thenReturn(dispatchId);
            when(dispatch.scope()).thenReturn(scope);
            when(dispatch.bundleId()).thenReturn(bundleId);
            when(dispatch.bundleDigest()).thenReturn(bundleDigest);
            when(dispatch.actionId()).thenReturn(actionId);
            when(dispatch.actionDigest()).thenReturn(actionDigest);
            when(dispatch.idempotencyKey()).thenReturn(ActionIdempotencyKey.derive(
                    organizationId, bundleId, actionId, actionDigest));
            when(dispatch.status()).thenReturn(ActionDispatchStatus.RECONCILING);
            when(dispatch.claim()).thenReturn(Optional.of(value));
            when(dispatch.version()).thenReturn(version);
            when(dispatch.reconciliationAttempts()).thenReturn(0);
            when(dispatch.audit()).thenReturn(AuditMetadata.createdBy(ownerId, now));
            return dispatch;
        }

        private ActionDispatch terminal(ActionDispatchStatus status) {
            ActionDispatch dispatch = mock(ActionDispatch.class);
            when(dispatch.id()).thenReturn(dispatchId);
            when(dispatch.scope()).thenReturn(scope);
            when(dispatch.actionId()).thenReturn(actionId);
            when(dispatch.status()).thenReturn(status);
            return dispatch;
        }

        private io.crewscope.domain.action.CreateDraftPullRequestActionParameters
                pullRequestParameters() {
            return new io.crewscope.domain.action.CreateDraftPullRequestActionParameters(
                    new ExternalRepositoryId("101"),
                    new RepositoryBranchName("crewscope/m5-i12"),
                    new RepositoryBranchName("main"),
                    delivery,
                    "M5-I12",
                    "UNKNOWN reconciliation",
                    true,
                    connectionId);
        }

        private ExternalObservation pullRequestWebhook() {
            ExternalResultIdentity identity = new ExternalResultIdentity(
                    connectionId, ExternalObjectType.PULL_REQUEST, "42", "101:pull-request:42");
            return new ExternalObservation(
                    ExternalObservationKey.derive(
                            connectionId, ExternalResultSource.WEBHOOK, "delivery-42"),
                    actionId,
                    actionDigest,
                    identity,
                    ExternalObjectStatus.OPEN,
                    Optional.of(42L),
                    Optional.of(now),
                    ExternalResultSource.WEBHOOK,
                    ActionEvidenceReference.hashed("GITHUB_PULL_REQUEST_WEBHOOK", "42"),
                    now);
        }
    }

    private static final class DirectTransactions implements TransactionExecutor {

        @Override
        public <T> T required(java.util.function.Supplier<T> operation) {
            return operation.get();
        }
    }
}
