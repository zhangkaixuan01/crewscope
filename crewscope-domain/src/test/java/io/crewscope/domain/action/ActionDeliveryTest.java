package io.crewscope.domain.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.action.event.ActionBundleConfirmed;
import io.crewscope.domain.action.event.ActionDispatchTransitioned;
import io.crewscope.domain.action.event.ActionReceiptRecorded;
import io.crewscope.domain.action.event.ExternalResultMerged;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActionDeliveryTest {

    private static final UtcTimestamp CONFIRMED_AT = time("2026-08-23T02:01:00Z");
    private static final ActionWorkerId WORKER_ONE = new ActionWorkerId("action-worker-1");
    private static final ActionWorkerId WORKER_TWO = new ActionWorkerId("action-worker-2");

    @Test
    void confirmsExactBundleOnlyByCurrentHumanOwnerAndSupportsExplicitCancellation() {
        ActionBundleTest.Fixture fixture = new ActionBundleTest.Fixture();
        ActionBundle bundle = fixture.bundle();

        assertThrows(DomainValidationException.class, () -> Confirmation.confirm(
                ConfirmationId.generate(), bundle, fixture.facts(), fixture.reviewer, CONFIRMED_AT));

        Confirmation confirmation = Confirmation.confirm(
                ConfirmationId.generate(), bundle, fixture.facts(), fixture.owner, CONFIRMED_AT);

        confirmation.requireAuthorizes(bundle, fixture.facts(), plus(CONFIRMED_AT, 1));
        assertEquals(bundle.digest(), confirmation.bundleDigest());
        assertEquals(
                bundle.actions().stream().map(PlannedAction::digest).toList(),
                confirmation.actions().stream().map(ConfirmedActionReference::actionDigest).toList());
        ActionBundleConfirmed event = ActionBundleConfirmed.from(confirmation);
        assertEquals(bundle.id().value(), event.actionBundleId());
        assertEquals(2, event.actionDigests().size());

        Confirmation cancelled = confirmation.cancel(
                confirmation.version(), ActionCancellationReason.MEMBER_CANCELLED,
                fixture.owner, plus(CONFIRMED_AT, 2));
        assertEquals(ConfirmationStatus.CANCELLED, cancelled.status());
        assertThrows(DomainValidationException.class, () -> cancelled.requireAuthorizes(
                bundle, fixture.facts(), plus(CONFIRMED_AT, 3)));
        assertThrows(InvalidStateTransitionException.class, () -> cancelled.cancel(
                cancelled.version(), ActionCancellationReason.MEMBER_CANCELLED,
                fixture.owner, plus(CONFIRMED_AT, 4)));
    }

    @Test
    void releasesDependentActionOnlyAfterSuccessfulReceiptInTheSameBundle() {
        Setup setup = setup();
        ActionDispatch pushDispatch = setup.pushDispatch();
        ActionDispatch pullRequestDispatch = setup.pullRequestDispatch();

        assertThrows(DomainValidationException.class, () -> pullRequestDispatch.claim(
                pullRequestDispatch.version(), setup.bundle(), setup.fixture().facts(),
                setup.confirmation(), List.of(), WORKER_ONE, plus(CONFIRMED_AT, 5),
                plus(CONFIRMED_AT, 35)));

        ActionDispatch claimedPush = claimExecution(
                pushDispatch, setup, WORKER_ONE, plus(CONFIRMED_AT, 5));
        ActionClaim pushClaim = claimedPush.claim().orElseThrow();
        UtcTimestamp completedAt = plus(CONFIRMED_AT, 6);
        ActionReceipt pushReceipt = successfulReceipt(
                claimedPush, setup.push(), pushClaim, ActionResultSource.WRITE_RESPONSE,
                completedAt);
        ActionDispatch completedPush = claimedPush.completeClaimed(
                claimedPush.version(), pushClaim, pushReceipt, completedAt);

        assertEquals(ActionDispatchStatus.SUCCEEDED, completedPush.status());
        ActionDispatch claimedPullRequest = pullRequestDispatch.claim(
                pullRequestDispatch.version(), setup.bundle(), setup.fixture().facts(),
                setup.confirmation(), List.of(pushReceipt), WORKER_ONE,
                plus(CONFIRMED_AT, 7), plus(CONFIRMED_AT, 37));
        assertEquals(ActionClaimMode.EXECUTE, claimedPullRequest.claim().orElseThrow().mode());
        assertEquals(ActionDispatchStatus.RUNNING, claimedPullRequest.status());
    }

    @Test
    void expiredExecutionLeaseIsTakenOverOnlyForReconciliationAndRejectsOldFencingToken() {
        Setup setup = setup();
        UtcTimestamp acquiredAt = plus(CONFIRMED_AT, 10);
        ActionDispatch first = setup.pushDispatch().claim(
                0, setup.bundle(), setup.fixture().facts(), setup.confirmation(), List.of(),
                WORKER_ONE, acquiredAt, plus(acquiredAt, 5));
        ActionClaim staleClaim = first.claim().orElseThrow();

        // Reconciliation remains available after Confirmation expiry because it performs no write.
        UtcTimestamp takeoverAt = time("2026-08-23T02:11:00Z");
        ActionDispatch takeover = first.claim(
                first.version(), setup.bundle(), setup.fixture().facts(), setup.confirmation(),
                List.of(), WORKER_TWO, takeoverAt, plus(takeoverAt, 30));

        assertEquals(ActionClaimMode.RECONCILE, takeover.claim().orElseThrow().mode());
        assertEquals(2, takeover.lastFencingToken());
        UtcTimestamp completedAt = plus(takeoverAt, 1);
        assertThrows(DomainValidationException.class, () -> successfulReceipt(
                takeover, setup.push(), staleClaim, ActionResultSource.ACTIVE_QUERY, completedAt));

        ActionClaim currentClaim = takeover.claim().orElseThrow();
        assertThrows(DomainValidationException.class, () -> ActionReceipt.fromClaim(
                ActionReceiptId.generate(), takeover, setup.push(), currentClaim,
                ActionReceiptResult.SUCCEEDED, ActionResultSource.WRITE_RESPONSE,
                Optional.of(identity(setup.push())), Optional.of(targetVersion(setup.push())),
                evidence("WRITE_RESPONSE"), completedAt));
        ActionReceipt reconciled = successfulReceipt(
                takeover, setup.push(), currentClaim, ActionResultSource.ACTIVE_QUERY, completedAt);
        ActionDispatch completed = takeover.completeClaimed(
                takeover.version(), currentClaim, reconciled, completedAt);
        assertEquals(ActionDispatchStatus.SUCCEEDED, completed.status());
    }

    @Test
    void separatesProvenNoEffectRetryFromUnknownAndEscalatesBoundedReconciliation() {
        Setup setup = setup();
        ActionDispatch claimed = claimExecution(
                setup.pushDispatch(), setup, WORKER_ONE, plus(CONFIRMED_AT, 10));
        ActionClaim executionClaim = claimed.claim().orElseThrow();
        UtcTimestamp failureAt = plus(CONFIRMED_AT, 11);
        ActionDispatch retry = claimed.scheduleRetry(
                claimed.version(), executionClaim,
                new ActionRetryDirective(
                        evidence("NO_SIDE_EFFECT_PROVIDER_REJECTED"), plus(CONFIRMED_AT, 20)),
                failureAt);
        assertEquals(ActionDispatchStatus.READY, retry.status());
        assertThrows(DomainValidationException.class, () -> retry.claim(
                retry.version(), setup.bundle(), setup.fixture().facts(), setup.confirmation(),
                List.of(), WORKER_ONE, plus(CONFIRMED_AT, 19), plus(CONFIRMED_AT, 49)));

        ActionDispatch retried = retry.claim(
                retry.version(), setup.bundle(), setup.fixture().facts(), setup.confirmation(),
                List.of(), WORKER_ONE, plus(CONFIRMED_AT, 20), plus(CONFIRMED_AT, 50));
        ActionDispatch unknown = retried.markUnknown(
                retried.version(), retried.claim().orElseThrow(), plus(CONFIRMED_AT, 21));
        assertEquals(ActionDispatchStatus.UNKNOWN, unknown.status());
        assertThrows(InvalidStateTransitionException.class, () -> unknown.scheduleRetry(
                unknown.version(), retried.claim().orElseThrow(),
                new ActionRetryDirective(evidence("NO_SIDE_EFFECT_FALSE"), plus(CONFIRMED_AT, 22)),
                plus(CONFIRMED_AT, 21)));

        ActionDispatch firstReconcile = unknown.claim(
                unknown.version(), setup.bundle(), setup.fixture().facts(), setup.confirmation(),
                List.of(), WORKER_TWO, plus(CONFIRMED_AT, 22), plus(CONFIRMED_AT, 52));
        ActionDispatch stillUnknown = firstReconcile.recordInconclusiveReconciliation(
                firstReconcile.version(), firstReconcile.claim().orElseThrow(), 2,
                plus(CONFIRMED_AT, 30), plus(CONFIRMED_AT, 23));
        assertEquals(ActionDispatchStatus.UNKNOWN, stillUnknown.status());

        ActionDispatch secondReconcile = stillUnknown.claim(
                stillUnknown.version(), setup.bundle(), setup.fixture().facts(), setup.confirmation(),
                List.of(), WORKER_TWO, plus(CONFIRMED_AT, 30), plus(CONFIRMED_AT, 60));
        ActionDispatch manualReview = secondReconcile.recordInconclusiveReconciliation(
                secondReconcile.version(), secondReconcile.claim().orElseThrow(), 2,
                plus(CONFIRMED_AT, 31), plus(CONFIRMED_AT, 31));
        assertEquals(ActionDispatchStatus.MANUAL_REVIEW, manualReview.status());
        assertEquals(2, manualReview.reconciliationAttempts());
    }

    @Test
    void manualReceiptIsIrreversibleAndLateProviderFactsCannotRewriteIt() {
        Setup setup = setup();
        ActionDispatch manualReview = reachManualReview(setup);
        UtcTimestamp resolvedAt = plus(CONFIRMED_AT, 40);
        ActionReceipt manualReceipt = ActionReceipt.manual(
                ActionReceiptId.generate(), manualReview, setup.push(),
                ActionReceiptResult.MANUALLY_FAILED, Optional.empty(), Optional.empty(),
                evidence("NO_EXTERNAL_OBJECT_VERIFIED"),
                ManualResolutionReason.NO_EXTERNAL_OBJECT_VERIFIED,
                setup.fixture().owner, resolvedAt);
        ActionDispatch resolved = manualReview.resolveManually(
                manualReview.version(), manualReceipt, resolvedAt);

        assertEquals(ActionDispatchStatus.MANUALLY_FAILED, resolved.status());
        assertEquals(manualReceipt.reference(), resolved.receipt().orElseThrow());
        ActionReceipt lateSuccess = ActionReceipt.fromObservation(
                ActionReceiptId.generate(), resolved, setup.push(), ActionReceiptResult.SUCCEEDED,
                ActionResultSource.WEBHOOK, Optional.of(identity(setup.push())),
                Optional.of(targetVersion(setup.push())), evidence("LATE_WEBHOOK"),
                plus(resolvedAt, 1));
        assertThrows(DomainValidationException.class, () -> resolved.completeFromObservation(
                resolved.version(), lateSuccess, plus(resolvedAt, 1)));
        assertThrows(DomainValidationException.class, () -> resolved.resolveManually(
                resolved.version(), manualReceipt, plus(resolvedAt, 2)));

        Principal agent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(
                        manualReview.scope().organizationId(), manualReview.scope().teamId()),
                PrincipalType.SPECIALIST_AGENT, Optional.of(setup.fixture().owner.id()),
                "Delivery Agent", Optional.empty(), PrincipalVisibility.TEAM, CONFIRMED_AT);
        assertThrows(DomainValidationException.class, () -> ActionReceipt.manual(
                ActionReceiptId.generate(), manualReview, setup.push(),
                ActionReceiptResult.MANUALLY_FAILED, Optional.empty(), Optional.empty(),
                evidence(ManualResolutionReason.NO_EXTERNAL_OBJECT_VERIFIED.name()),
                ManualResolutionReason.NO_EXTERNAL_OBJECT_VERIFIED, agent, resolvedAt));
        assertThrows(DomainValidationException.class, () -> ActionReceipt.manual(
                ActionReceiptId.generate(), manualReview, setup.push(),
                ActionReceiptResult.MANUALLY_FAILED, Optional.empty(), Optional.empty(),
                evidence("PROVIDER_AUDIT_VERIFIED"),
                ManualResolutionReason.NO_EXTERNAL_OBJECT_VERIFIED,
                setup.fixture().owner, resolvedAt));
    }

    @Test
    void cancellationWritesOneReceiptAndFlagsManualCompensationAfterPartialSuccess() {
        Setup setup = setup();
        UtcTimestamp cancelledAt = plus(CONFIRMED_AT, 5);
        ActionReceipt cancelledPushReceipt = ActionReceipt.cancelled(
                ActionReceiptId.generate(), setup.pushDispatch(), setup.push(),
                evidence("NO_SIDE_EFFECT_MEMBER_CANCELLED"), setup.fixture().owner, cancelledAt);
        ActionDispatch cancelledPush = setup.pushDispatch().cancel(
                setup.pushDispatch().version(), cancelledPushReceipt,
                ActionCancellationReason.MEMBER_CANCELLED, List.of(), cancelledAt);
        assertEquals(ActionDispatchStatus.CANCELLED, cancelledPush.status());
        assertEquals(CompensationDisposition.NOT_REQUIRED, cancelledPush.compensationDisposition());

        ActionDispatch claimedPush = claimExecution(
                setup.pushDispatch(), setup, WORKER_ONE, plus(CONFIRMED_AT, 6));
        ActionClaim claim = claimedPush.claim().orElseThrow();
        ActionReceipt successfulPush = successfulReceipt(
                claimedPush, setup.push(), claim, ActionResultSource.WRITE_RESPONSE,
                plus(CONFIRMED_AT, 7));
        ActionReceipt cancelledPullRequestReceipt = ActionReceipt.cancelled(
                ActionReceiptId.generate(), setup.pullRequestDispatch(), setup.pullRequest(),
                evidence("NO_SIDE_EFFECT_DEPENDENT_CANCELLED"), setup.fixture().owner,
                plus(CONFIRMED_AT, 8));
        ActionDispatch cancelledPullRequest = setup.pullRequestDispatch().cancel(
                setup.pullRequestDispatch().version(), cancelledPullRequestReceipt,
                ActionCancellationReason.MEMBER_CANCELLED, List.of(successfulPush),
                plus(CONFIRMED_AT, 8));

        assertEquals(
                CompensationDisposition.MANUAL_REVIEW_REQUIRED,
                cancelledPullRequest.compensationDisposition());
        assertThrows(InvalidStateTransitionException.class, () -> claimedPush.cancel(
                claimedPush.version(), cancelledPushReceipt,
                ActionCancellationReason.MEMBER_CANCELLED, List.of(), plus(CONFIRMED_AT, 9)));
    }

    @Test
    void mergesExternalResultsByProviderVersionAndProtectsManualTerminalDecision() {
        Setup setup = setup();
        ExternalResultIdentity identity = identity(setup.pullRequest());
        ExternalObservation mergedV3 = observation(
                setup.pullRequest(), identity, "delivery-3", ExternalObjectStatus.MERGED,
                Optional.of(3L), Optional.of(plus(CONFIRMED_AT, 20)),
                ExternalResultSource.WEBHOOK, plus(CONFIRMED_AT, 21));
        ExternalResult result = ExternalResult.observeFirst(
                ExternalResultId.generate(), setup.pullRequestDispatch(), setup.pullRequest(),
                mergedV3, setup.fixture().owner);

        ExternalMergeResult duplicate = result.merge(
                result.version(), mergedV3, Optional.empty(), setup.fixture().owner);
        assertEquals(ExternalMergeOutcome.DUPLICATE, duplicate.outcome());
        ExternalObservation sameVersionAndStatusWithDifferentProviderTime = observation(
                setup.pullRequest(), identity, "delivery-3-repeat", ExternalObjectStatus.MERGED,
                Optional.of(3L), Optional.of(plus(CONFIRMED_AT, 19)),
                ExternalResultSource.ACTIVE_QUERY, plus(CONFIRMED_AT, 22));
        assertEquals(ExternalMergeOutcome.DUPLICATE, result.merge(
                result.version(), sameVersionAndStatusWithDifferentProviderTime,
                Optional.empty(), setup.fixture().owner).outcome());

        ExternalObservation staleV1 = observation(
                setup.pullRequest(), identity, "delivery-1", ExternalObjectStatus.OPEN,
                Optional.of(1L), Optional.of(plus(CONFIRMED_AT, 10)),
                ExternalResultSource.WEBHOOK, plus(CONFIRMED_AT, 22));
        assertEquals(ExternalMergeOutcome.STALE, result.merge(
                result.version(), staleV1, Optional.empty(), setup.fixture().owner).outcome());

        ExternalObservation conflictingV3 = observation(
                setup.pullRequest(), identity, "delivery-conflict", ExternalObjectStatus.CLOSED,
                Optional.of(3L), Optional.of(plus(CONFIRMED_AT, 20)),
                ExternalResultSource.WEBHOOK, plus(CONFIRMED_AT, 23));
        assertEquals(ExternalMergeOutcome.CONFLICT, result.merge(
                result.version(), conflictingV3, Optional.empty(), setup.fixture().owner).outcome());

        ExternalObservation impossibleAfterMerge = observation(
                setup.pullRequest(), identity, "delivery-4", ExternalObjectStatus.OPEN,
                Optional.of(4L), Optional.of(plus(CONFIRMED_AT, 24)),
                ExternalResultSource.ACTIVE_QUERY, plus(CONFIRMED_AT, 25));
        assertEquals(ExternalMergeOutcome.CONFLICT, result.merge(
                result.version(), impossibleAfterMerge, Optional.empty(), setup.fixture().owner).outcome());

        ActionReceiptReference manualTerminal = new ActionReceiptReference(
                ActionReceiptId.generate(), setup.pullRequest().id(), setup.pullRequest().digest(),
                ActionReceiptResult.MANUALLY_FAILED);
        assertEquals(ExternalMergeOutcome.MANUAL_TERMINAL_CONFLICT, result.merge(
                result.version(), staleV1, Optional.of(manualTerminal),
                setup.fixture().owner).outcome());

        ExternalObservation openWithoutVersion = observation(
                setup.pullRequest(), identity, "query-open", ExternalObjectStatus.OPEN,
                Optional.empty(), Optional.of(plus(CONFIRMED_AT, 10)),
                ExternalResultSource.ACTIVE_QUERY, plus(CONFIRMED_AT, 11));
        ExternalResult unversioned = ExternalResult.observeFirst(
                ExternalResultId.generate(), setup.pullRequestDispatch(), setup.pullRequest(),
                openWithoutVersion, setup.fixture().owner);
        ExternalObservation newerClosed = observation(
                setup.pullRequest(), identity, "query-closed", ExternalObjectStatus.CLOSED,
                Optional.empty(), Optional.of(plus(CONFIRMED_AT, 12)),
                ExternalResultSource.ACTIVE_QUERY, plus(CONFIRMED_AT, 13));
        ExternalMergeResult applied = unversioned.merge(
                unversioned.version(), newerClosed, Optional.empty(), setup.fixture().owner);
        assertEquals(ExternalMergeOutcome.APPLIED, applied.outcome());
        assertEquals(ExternalObjectStatus.CLOSED, applied.result().status());

        ExternalObservation olderOpen = observation(
                setup.pullRequest(), identity, "query-stale", ExternalObjectStatus.OPEN,
                Optional.empty(), Optional.of(plus(CONFIRMED_AT, 9)),
                ExternalResultSource.ACTIVE_QUERY, plus(CONFIRMED_AT, 14));
        assertEquals(ExternalMergeOutcome.STALE, unversioned.merge(
                unversioned.version(), olderOpen, Optional.empty(),
                setup.fixture().owner).outcome());
        ExternalObservation sameTimeConflict = observation(
                setup.pullRequest(), identity, "query-conflict", ExternalObjectStatus.MERGED,
                Optional.empty(), Optional.of(plus(CONFIRMED_AT, 10)),
                ExternalResultSource.ACTIVE_QUERY, plus(CONFIRMED_AT, 15));
        assertEquals(ExternalMergeOutcome.CONFLICT, unversioned.merge(
                unversioned.version(), sameTimeConflict, Optional.empty(),
                setup.fixture().owner).outcome());
    }

    @Test
    void enforcesReceiptExternalShapeAndEmitsOnlySanitizedEvents() {
        Setup setup = setup();
        ActionDispatch claimed = claimExecution(
                setup.pushDispatch(), setup, WORKER_ONE, plus(CONFIRMED_AT, 5));
        ActionClaim claim = claimed.claim().orElseThrow();
        UtcTimestamp receivedAt = plus(CONFIRMED_AT, 6);

        assertThrows(DomainValidationException.class, () -> ActionReceipt.fromClaim(
                ActionReceiptId.generate(), claimed, setup.push(), claim,
                ActionReceiptResult.SUCCEEDED, ActionResultSource.WRITE_RESPONSE,
                Optional.empty(), Optional.empty(), evidence("WRITE_RESPONSE"), receivedAt));
        ExternalResultIdentity wrongConnection = new ExternalResultIdentity(
                ConnectionId.generate(), ExternalObjectType.BRANCH, "branch-1", "repo|branch");
        assertThrows(DomainValidationException.class, () -> ActionReceipt.fromClaim(
                ActionReceiptId.generate(), claimed, setup.push(), claim,
                ActionReceiptResult.SUCCEEDED, ActionResultSource.WRITE_RESPONSE,
                Optional.of(wrongConnection), Optional.of(targetVersion(setup.push())),
                evidence("WRITE_RESPONSE"), receivedAt));

        ActionReceipt receipt = successfulReceipt(
                claimed, setup.push(), claim, ActionResultSource.WRITE_RESPONSE, receivedAt);
        ActionIdempotencyKey forgedKey = new ActionIdempotencyKey(
                TaskFactHash.sha256("forged-action-idempotency-key"));
        assertThrows(DomainValidationException.class, () -> reconstituteDispatch(
                setup.pushDispatch(), forgedKey, setup.pushDispatch().status(),
                setup.pushDispatch().receipt(), setup.pushDispatch().cancellationReason(),
                setup.pushDispatch().compensationDisposition()));
        assertThrows(DomainValidationException.class, () -> ActionReceipt.reconstitute(
                receipt.id(), receipt.scope(), receipt.bundleId(), receipt.bundleDigest(),
                receipt.actionId(), receipt.actionDigest(), forgedKey, receipt.result(),
                receipt.source(), receipt.claim(), receipt.externalIdentity(),
                receipt.targetVersion(), receipt.evidence(), receipt.resolvedByPrincipalId(),
                receipt.manualReason(), receipt.receivedAt()));

        ActionDispatch completed = claimed.completeClaimed(
                claimed.version(), claim, receipt, receivedAt);
        assertThrows(DomainValidationException.class, () -> reconstituteDispatch(
                completed, completed.idempotencyKey(), ActionDispatchStatus.FAILED,
                completed.receipt(), completed.cancellationReason(),
                completed.compensationDisposition()));
        assertThrows(DomainValidationException.class, () -> reconstituteDispatch(
                setup.pushDispatch(), setup.pushDispatch().idempotencyKey(),
                ActionDispatchStatus.READY, Optional.empty(), Optional.empty(),
                CompensationDisposition.MANUAL_REVIEW_REQUIRED));

        ActionReceiptRecorded receiptEvent = ActionReceiptRecorded.from(receipt);
        assertTrue(receiptEvent.externalIdentityHash().isPresent());
        assertNotEquals(identity(setup.push()).externalId(),
                receiptEvent.externalIdentityHash().orElseThrow());
        assertEquals(claimed.status().name(), ActionDispatchTransitioned.from(claimed).status());

        ExternalObservation observation = observation(
                setup.push(), identity(setup.push()), "query-1", ExternalObjectStatus.PRESENT,
                Optional.of(1L), Optional.of(receivedAt), ExternalResultSource.ACTIVE_QUERY,
                plus(receivedAt, 1));
        ExternalResult result = ExternalResult.observeFirst(
                ExternalResultId.generate(), claimed, setup.push(), observation,
                setup.fixture().owner);
        ExternalResultMerged externalEvent = ExternalResultMerged.from(
                result, ExternalMergeOutcome.APPLIED);
        assertNotEquals(result.identity().externalId(), externalEvent.externalIdentityHash());
    }

    private static Setup setup() {
        ActionBundleTest.Fixture fixture = new ActionBundleTest.Fixture();
        ActionBundle bundle = fixture.bundle();
        Confirmation confirmation = Confirmation.confirm(
                ConfirmationId.generate(), bundle, fixture.facts(), fixture.owner, CONFIRMED_AT);
        PlannedAction push = bundle.actions().get(0);
        PlannedAction pullRequest = bundle.actions().get(1);
        return new Setup(
                fixture,
                bundle,
                confirmation,
                push,
                pullRequest,
                ActionDispatch.schedule(
                        ActionDispatchId.generate(), bundle, push, confirmation,
                        fixture.owner, CONFIRMED_AT),
                ActionDispatch.schedule(
                        ActionDispatchId.generate(), bundle, pullRequest, confirmation,
                        fixture.owner, CONFIRMED_AT));
    }

    private static ActionDispatch claimExecution(
            ActionDispatch dispatch, Setup setup, ActionWorkerId worker, UtcTimestamp acquiredAt) {
        return dispatch.claim(
                dispatch.version(), setup.bundle(), setup.fixture().facts(), setup.confirmation(),
                List.of(), worker, acquiredAt, plus(acquiredAt, 30));
    }

    private static ActionDispatch reachManualReview(Setup setup) {
        ActionDispatch claimed = claimExecution(
                setup.pushDispatch(), setup, WORKER_ONE, plus(CONFIRMED_AT, 5));
        ActionDispatch unknown = claimed.markUnknown(
                claimed.version(), claimed.claim().orElseThrow(), plus(CONFIRMED_AT, 6));
        ActionDispatch reconciling = unknown.claim(
                unknown.version(), setup.bundle(), setup.fixture().facts(), setup.confirmation(),
                List.of(), WORKER_TWO, plus(CONFIRMED_AT, 7), plus(CONFIRMED_AT, 37));
        return reconciling.recordInconclusiveReconciliation(
                reconciling.version(), reconciling.claim().orElseThrow(), 1,
                plus(CONFIRMED_AT, 8), plus(CONFIRMED_AT, 8));
    }

    private static ActionReceipt successfulReceipt(
            ActionDispatch dispatch,
            PlannedAction action,
            ActionClaim claim,
            ActionResultSource source,
            UtcTimestamp receivedAt) {
        return ActionReceipt.fromClaim(
                ActionReceiptId.generate(), dispatch, action, claim, ActionReceiptResult.SUCCEEDED,
                source, Optional.of(identity(action)), Optional.of(targetVersion(action)),
                evidence(source.name()), receivedAt);
    }

    private static ExternalResultIdentity identity(PlannedAction action) {
        if (action.parameters() instanceof PushBranchActionParameters push) {
            return new ExternalResultIdentity(
                    push.connectionId(), ExternalObjectType.BRANCH, push.branch().value(),
                    push.repositoryId().value() + "|" + push.branch().value());
        }
        CreateDraftPullRequestActionParameters pullRequest =
                (CreateDraftPullRequestActionParameters) action.parameters();
        return new ExternalResultIdentity(
                pullRequest.connectionId(), ExternalObjectType.PULL_REQUEST, "42",
                pullRequest.repositoryId().value() + "|" + pullRequest.head().value()
                        + "|" + pullRequest.base().value());
    }

    private static String targetVersion(PlannedAction action) {
        if (action.parameters() instanceof PushBranchActionParameters push) {
            return push.deliveryHead().value();
        }
        return ((CreateDraftPullRequestActionParameters) action.parameters()).headSha().value();
    }

    private static ExternalObservation observation(
            PlannedAction action,
            ExternalResultIdentity identity,
            String sourceEventId,
            ExternalObjectStatus status,
            Optional<Long> providerVersion,
            Optional<UtcTimestamp> providerUpdatedAt,
            ExternalResultSource source,
            UtcTimestamp observedAt) {
        return new ExternalObservation(
                ExternalObservationKey.derive(identity.connectionId(), source, sourceEventId),
                action.id(), action.digest(), identity, status, providerVersion, providerUpdatedAt,
                source, evidence("PROVIDER_OBSERVATION"), observedAt);
    }

    private static ActionEvidenceReference evidence(String code) {
        return ActionEvidenceReference.hashed(code, "canonical:" + code);
    }

    private static ActionDispatch reconstituteDispatch(
            ActionDispatch source,
            ActionIdempotencyKey idempotencyKey,
            ActionDispatchStatus status,
            Optional<ActionReceiptReference> receipt,
            Optional<ActionCancellationReason> cancellationReason,
            CompensationDisposition compensationDisposition) {
        return ActionDispatch.reconstitute(
                source.id(), source.scope(), source.bundleId(), source.bundleDigest(),
                source.confirmationId(), source.actionId(), source.actionDigest(), source.sequence(),
                source.dependencies(), idempotencyKey, source.validUntil(), status, source.claim(),
                source.lastFencingToken(), source.claimAttempts(), source.reconciliationAttempts(),
                source.notBefore(), receipt, cancellationReason, compensationDisposition,
                source.version(), source.audit());
    }

    private static UtcTimestamp plus(UtcTimestamp value, long seconds) {
        return UtcTimestamp.from(value.value().plusSeconds(seconds));
    }

    private static UtcTimestamp time(String value) {
        return UtcTimestamp.parse(value);
    }

    private record Setup(
            ActionBundleTest.Fixture fixture,
            ActionBundle bundle,
            Confirmation confirmation,
            PlannedAction push,
            PlannedAction pullRequest,
            ActionDispatch pushDispatch,
            ActionDispatch pullRequestDispatch) {}
}
