package io.crewscope.application.notification;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationFailureCode;
import io.crewscope.domain.notification.NotificationInvalidationReason;
import io.crewscope.domain.notification.NotificationPlannedAction;
import io.crewscope.domain.notification.NotificationReceipt;
import io.crewscope.domain.notification.NotificationReceiptId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Query-only recovery Worker for UNKNOWN and expired notification claims. */
public final class NotificationReconciliationWorker {

    private final NotificationDispatchRepository dispatches;
    private final NotificationAuthorizationFactsResolver factsResolver;
    private final NotificationCredentialIssuer credentials;
    private final NotificationProviderPort provider;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final NotificationWorkerId workerId;
    private final Duration leaseDuration;
    private final Duration credentialTtl;
    private final Duration retryDelay;
    private final int maximumAttempts;
    private final int batchSize;

    public NotificationReconciliationWorker(
            NotificationDispatchRepository dispatches,
            NotificationAuthorizationFactsResolver factsResolver,
            NotificationCredentialIssuer credentials,
            NotificationProviderPort provider,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            NotificationWorkerId workerId,
            Duration leaseDuration,
            Duration credentialTtl,
            Duration retryDelay,
            int maximumAttempts,
            int batchSize) {
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.factsResolver = Objects.requireNonNull(factsResolver, "factsResolver");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDuration = duration(leaseDuration, Duration.ofSeconds(5), Duration.ofMinutes(10));
        this.credentialTtl = duration(credentialTtl, Duration.ofSeconds(1), this.leaseDuration);
        this.retryDelay = duration(retryDelay, Duration.ofSeconds(1), Duration.ofHours(1));
        this.maximumAttempts = bounded(maximumAttempts, "maximumAttempts");
        this.batchSize = bounded(batchSize, "batchSize");
    }

    public NotificationWorkerBatchResult runOnce() {
        NotificationWorkerBatchResult aggregate = NotificationWorkerBatchResult.empty();
        for (OrganizationId organization : dispatches.findReconciliationOrganizations(
                timeProvider.now(), retryDelay, batchSize)) {
            aggregate = aggregate.plus(runOnce(organization));
        }
        return aggregate;
    }

    public NotificationWorkerBatchResult runOnce(OrganizationId organizationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        List<Outcome> outcomes = new ArrayList<>();
        for (int index = 0; index < batchSize; index++) {
            Optional<ClaimedNotification> claimed = transactions.required(
                    () -> dispatches.claimReconciliation(
                            organization,
                            workerId,
                            timeProvider.now(),
                            leaseDuration,
                            retryDelay));
            if (claimed.isEmpty()) {
                break;
            }
            outcomes.add(reconcile(organization, claimed.orElseThrow()));
        }
        return result(outcomes);
    }

    private Outcome reconcile(OrganizationId organization, ClaimedNotification claimed) {
        Preflight preflight = preflight(organization, claimed);
        if (preflight.outcome().isPresent()) {
            return preflight.outcome().orElseThrow();
        }
        if (claimed.claim().reconciliationCount() >= maximumAttempts) {
            return fail(
                    organization,
                    claimed,
                    NotificationFailureCode.RECONCILIATION_EXHAUSTED,
                    "NOTIFICATION_RECONCILIATION_EXHAUSTED");
        }
        NotificationQueryResult result;
        try (NotificationCredentialHandle credential = credentials.issue(
                claimed.plan(), claimed.claim(), credentialTtl)) {
            result = provider.query(request(claimed, preflight.facts().orElseThrow()), credential);
        } catch (RuntimeException queryUnavailable) {
            return defer(organization, claimed);
        }
        return switch (result.kind()) {
            case FOUND -> succeed(organization, claimed, result);
            case NOT_FOUND -> retryWriteOrFail(organization, claimed);
            case RETRYABLE, UNKNOWN -> defer(organization, claimed);
            case FAILED_FINAL -> fail(
                    organization,
                    claimed,
                    result.failureCode().orElseThrow(),
                    result.evidenceCode());
        };
    }

    private Preflight preflight(
            OrganizationId organization, ClaimedNotification claimed) {
        NotificationAuthorizationFacts current;
        try {
            current = factsResolver.resolveCurrent(claimed.plan().action().parameters().intentId());
        } catch (RuntimeException unavailable) {
            return Preflight.outcome(defer(organization, claimed));
        }
        Optional<NotificationInvalidationReason> drift =
                claimed.plan().action().authority().invalidationReason(current);
        if (drift.isEmpty()) {
            return Preflight.allowed(current);
        }
        NotificationInvalidationReason reason = drift.orElseThrow();
        NotificationPlannedAction action = claimed.plan().action().invalidate(
                claimed.plan().action().version(), reason);
        NotificationReceipt receipt = NotificationReceipt.invalidated(
                NotificationReceiptId.fromDelivery(claimed.plan().delivery().id()),
                claimed.plan().delivery(), action, reason, timeProvider.now());
        NotificationDelivery delivery = claimed.plan().delivery().invalidate(
                claimed.plan().delivery().version(), reason, receipt);
        return Preflight.outcome(commit(
                organization,
                claimed,
                new NotificationPlan(action, delivery),
                Outcome.INVALIDATED));
    }

    private Outcome succeed(
            OrganizationId organization,
            ClaimedNotification claimed,
            NotificationQueryResult result) {
        NotificationReceipt receipt = NotificationReceipt.accepted(
                NotificationReceiptId.fromDelivery(claimed.plan().delivery().id()),
                claimed.plan().delivery(),
                claimed.plan().action(),
                result.providerReference().orElseThrow(),
                result.providerMessageId().orElseThrow(),
                result.evidenceCode(),
                timeProvider.now());
        NotificationDelivery delivery = claimed.plan().delivery().succeed(
                claimed.plan().delivery().version(), receipt);
        return commit(
                organization,
                claimed,
                new NotificationPlan(claimed.plan().action(), delivery),
                Outcome.SUCCEEDED);
    }

    private Outcome retryWriteOrFail(
            OrganizationId organization, ClaimedNotification claimed) {
        UtcTimestamp now = timeProvider.now();
        UtcTimestamp retryAt = UtcTimestamp.from(now.value().plus(retryDelay));
        if (claimed.plan().delivery().attemptCount() >= maximumAttempts
                || retryAt.compareTo(claimed.plan().action().validUntil()) >= 0) {
            return fail(
                    organization,
                    claimed,
                    NotificationFailureCode.RETRY_EXHAUSTED,
                    "NOTIFICATION_RETRY_EXHAUSTED");
        }
        NotificationDelivery delivery = claimed.plan().delivery().retryWait(
                claimed.plan().delivery().version(), retryAt, now);
        return commit(
                organization,
                claimed,
                new NotificationPlan(claimed.plan().action(), delivery),
                Outcome.RETRY_SCHEDULED);
    }

    private Outcome defer(OrganizationId organization, ClaimedNotification claimed) {
        if (claimed.claim().reconciliationCount() >= maximumAttempts) {
            return fail(
                    organization,
                    claimed,
                    NotificationFailureCode.RECONCILIATION_EXHAUSTED,
                    "NOTIFICATION_RECONCILIATION_EXHAUSTED");
        }
        NotificationDelivery delivery = claimed.plan().delivery().deferReconciliation(
                claimed.plan().delivery().version(), timeProvider.now());
        return commit(
                organization,
                claimed,
                new NotificationPlan(claimed.plan().action(), delivery),
                Outcome.UNCERTAIN);
    }

    private Outcome fail(
            OrganizationId organization,
            ClaimedNotification claimed,
            NotificationFailureCode failureCode,
            String evidenceCode) {
        NotificationReceipt receipt = NotificationReceipt.failed(
                NotificationReceiptId.fromDelivery(claimed.plan().delivery().id()),
                claimed.plan().delivery(),
                claimed.plan().action(),
                failureCode,
                evidenceCode,
                timeProvider.now());
        NotificationDelivery delivery = claimed.plan().delivery().failFinal(
                claimed.plan().delivery().version(), receipt);
        return commit(
                organization,
                claimed,
                new NotificationPlan(claimed.plan().action(), delivery),
                Outcome.FAILED_FINAL);
    }

    private Outcome commit(
            OrganizationId organization,
            ClaimedNotification claimed,
            NotificationPlan outcome,
            Outcome expected) {
        try {
            transactions.required(() -> dispatches.updateClaimed(
                    organization, claimed.claim(), outcome, timeProvider.now()));
            return expected;
        } catch (OptimisticLockConflictException staleWorker) {
            return Outcome.FENCED;
        }
    }

    private NotificationProviderRequest request(
            ClaimedNotification claimed, NotificationAuthorizationFacts currentFacts) {
        var action = claimed.plan().action();
        var parameters = action.parameters();
        NotificationAuthorizationFacts current = Objects.requireNonNull(
                currentFacts, "currentFacts");
        return new NotificationProviderRequest(
                parameters.organizationId(), parameters.teamId(),
                parameters.recipientMemberId(), action.id(), action.digest(),
                parameters.template(), parameters.variableHash(),
                action.authority().recipientMappingId(), action.authority().connectionId(),
                action.authority().deduplicationKey(), action.authority(),
                current.intent().variables(),
                NotificationWorker.providerIdempotencyKey(claimed.plan()),
                Math.max(1, claimed.plan().delivery().attemptCount()));
    }

    private static NotificationWorkerBatchResult result(List<Outcome> outcomes) {
        return new NotificationWorkerBatchResult(
                outcomes.size(), count(outcomes, Outcome.SUCCEEDED),
                count(outcomes, Outcome.RETRY_SCHEDULED), count(outcomes, Outcome.UNCERTAIN),
                count(outcomes, Outcome.FAILED_FINAL), count(outcomes, Outcome.INVALIDATED),
                count(outcomes, Outcome.FENCED));
    }

    private static int count(List<Outcome> outcomes, Outcome expected) {
        return (int) outcomes.stream().filter(expected::equals).count();
    }

    private static Duration duration(Duration value, Duration minimum, Duration maximum) {
        Duration required = Objects.requireNonNull(value, "duration");
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Notification reconciliation duration is out of range");
        }
        return required;
    }

    private static int bounded(int value, String field) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException(field + " must be between 1 and 100");
        }
        return value;
    }

    private enum Outcome {
        SUCCEEDED,
        RETRY_SCHEDULED,
        UNCERTAIN,
        FAILED_FINAL,
        INVALIDATED,
        FENCED
    }

    private record Preflight(
            Optional<NotificationAuthorizationFacts> facts, Optional<Outcome> outcome) {

        private Preflight {
            facts = Objects.requireNonNull(facts, "facts");
            outcome = Objects.requireNonNull(outcome, "outcome");
            if (facts.isPresent() == outcome.isPresent()) {
                throw new IllegalArgumentException(
                        "Notification preflight must contain either facts or an outcome");
            }
        }

        static Preflight allowed(NotificationAuthorizationFacts facts) {
            return new Preflight(Optional.of(facts), Optional.empty());
        }

        static Preflight outcome(Outcome outcome) {
            return new Preflight(Optional.empty(), Optional.of(outcome));
        }
    }
}
