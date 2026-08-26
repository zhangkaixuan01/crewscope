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
import java.util.UUID;

/**
 * Reliable Provider-neutral notification write Worker.
 *
 * <p>Every claim transaction commits before credential issuance and Provider invocation. Outcome
 * transactions are fenced by the exact claim token and lease.
 */
public final class NotificationWorker {

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
    private final Duration maximumRetryDelay;
    private final int maximumAttempts;
    private final int batchSize;

    public NotificationWorker(
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
            Duration maximumRetryDelay,
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
        this.credentialTtl = duration(
                credentialTtl, Duration.ofSeconds(1), this.leaseDuration);
        this.retryDelay = duration(retryDelay, Duration.ofSeconds(1), Duration.ofMinutes(30));
        this.maximumRetryDelay = duration(
                maximumRetryDelay, this.retryDelay, Duration.ofHours(6));
        this.maximumAttempts = bounded(maximumAttempts, "maximumAttempts");
        this.batchSize = bounded(batchSize, "batchSize");
    }

    public NotificationWorkerBatchResult runOnce() {
        NotificationWorkerBatchResult aggregate = NotificationWorkerBatchResult.empty();
        for (OrganizationId organization : dispatches.findExecutionOrganizations(
                timeProvider.now(), batchSize)) {
            aggregate = aggregate.plus(runOnce(organization));
        }
        return aggregate;
    }

    public NotificationWorkerBatchResult runOnce(OrganizationId organizationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        List<Outcome> outcomes = new ArrayList<>();
        for (int index = 0; index < batchSize; index++) {
            Optional<ClaimedNotification> claimed = transactions.required(
                    () -> dispatches.claimExecution(
                            organization, workerId, timeProvider.now(), leaseDuration));
            if (claimed.isEmpty()) {
                break;
            }
            outcomes.add(execute(organization, claimed.orElseThrow()));
        }
        return result(outcomes);
    }

    private Outcome execute(OrganizationId organization, ClaimedNotification claimed) {
        Preflight preflight = preflight(organization, claimed);
        if (preflight.outcome().isPresent()) {
            return preflight.outcome().orElseThrow();
        }
        NotificationCredentialHandle issued;
        try {
            issued = credentials.issue(claimed.plan(), claimed.claim(), credentialTtl);
        } catch (RuntimeException unavailableBeforeWrite) {
            return retryOrFail(
                    organization, claimed, "NOTIFICATION_CREDENTIAL_UNAVAILABLE");
        }
        NotificationSendResult result;
        try (NotificationCredentialHandle credential = issued) {
            result = provider.send(request(claimed, preflight.facts().orElseThrow()), credential);
        } catch (RuntimeException uncertain) {
            // Once send() is entered, an unclassified failure may follow an accepted write.
            return commit(organization, claimed, unknown(claimed), Outcome.UNCERTAIN);
        }
        return switch (result.kind()) {
            case ACCEPTED -> commit(
                    organization, claimed, succeeded(claimed, result), Outcome.SUCCEEDED);
            case RETRYABLE -> retryOrFail(organization, claimed, result.evidenceCode());
            case UNKNOWN -> commit(
                    organization, claimed, unknown(claimed), Outcome.UNCERTAIN);
            case FAILED_FINAL -> commit(
                    organization,
                    claimed,
                    failed(
                            claimed,
                            result.failureCode().orElseThrow(),
                            result.evidenceCode()),
                    Outcome.FAILED_FINAL);
        };
    }

    private Preflight preflight(
            OrganizationId organization, ClaimedNotification claimed) {
        NotificationAuthorizationFacts current;
        try {
            current = factsResolver.resolveCurrent(claimed.plan().action().parameters().intentId());
        } catch (RuntimeException temporarilyUnavailable) {
            return Preflight.outcome(retryOrFail(
                    organization, claimed, "AUTHORIZATION_PREFLIGHT_UNAVAILABLE"));
        }
        Optional<NotificationInvalidationReason> drift =
                claimed.plan().action().authority().invalidationReason(current);
        if (drift.isEmpty()) {
            return Preflight.allowed(current);
        }
        NotificationInvalidationReason reason = drift.orElseThrow();
        NotificationPlannedAction invalidatedAction = claimed.plan().action().invalidate(
                claimed.plan().action().version(), reason);
        NotificationReceipt receipt = NotificationReceipt.invalidated(
                NotificationReceiptId.fromDelivery(claimed.plan().delivery().id()),
                claimed.plan().delivery(),
                invalidatedAction,
                reason,
                timeProvider.now());
        NotificationDelivery invalidatedDelivery = claimed.plan().delivery().invalidate(
                claimed.plan().delivery().version(), reason, receipt);
        return Preflight.outcome(commit(
                organization,
                claimed,
                new NotificationPlan(invalidatedAction, invalidatedDelivery),
                Outcome.INVALIDATED));
    }

    private Outcome retryOrFail(
            OrganizationId organization, ClaimedNotification claimed, String evidenceCode) {
        NotificationDelivery delivery = claimed.plan().delivery();
        UtcTimestamp now = timeProvider.now();
        Duration delay = backoff(delivery.attemptCount());
        UtcTimestamp retryAt = UtcTimestamp.from(now.value().plus(delay));
        if (delivery.attemptCount() >= maximumAttempts
                || retryAt.compareTo(claimed.plan().action().validUntil()) >= 0) {
            return commit(
                    organization,
                    claimed,
                    failed(
                            claimed,
                            NotificationFailureCode.RETRY_EXHAUSTED,
                            "NOTIFICATION_RETRY_EXHAUSTED"),
                    Outcome.FAILED_FINAL);
        }
        NotificationDelivery retry = delivery.retryWait(delivery.version(), retryAt, now);
        return commit(
                organization,
                claimed,
                new NotificationPlan(claimed.plan().action(), retry),
                Outcome.RETRY_SCHEDULED);
    }

    private NotificationPlan succeeded(
            ClaimedNotification claimed, NotificationSendResult result) {
        NotificationReceipt receipt = NotificationReceipt.accepted(
                NotificationReceiptId.fromDelivery(claimed.plan().delivery().id()),
                claimed.plan().delivery(),
                claimed.plan().action(),
                result.providerReference().orElseThrow(),
                result.providerMessageId().orElseThrow(),
                result.evidenceCode(),
                timeProvider.now());
        NotificationDelivery succeeded = claimed.plan().delivery().succeed(
                claimed.plan().delivery().version(), receipt);
        return new NotificationPlan(claimed.plan().action(), succeeded);
    }

    private NotificationPlan failed(
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
        NotificationDelivery failed = claimed.plan().delivery().failFinal(
                claimed.plan().delivery().version(), receipt);
        return new NotificationPlan(claimed.plan().action(), failed);
    }

    private NotificationPlan unknown(ClaimedNotification claimed) {
        NotificationDelivery unknown = claimed.plan().delivery().markUnknown(
                claimed.plan().delivery().version(), timeProvider.now());
        return new NotificationPlan(claimed.plan().action(), unknown);
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
                parameters.organizationId(),
                parameters.teamId(),
                parameters.recipientMemberId(),
                action.id(),
                action.digest(),
                parameters.template(),
                parameters.variableHash(),
                action.authority().recipientMappingId(),
                action.authority().connectionId(),
                action.authority().deduplicationKey(),
                action.authority(),
                current.intent().variables(),
                providerIdempotencyKey(claimed.plan()),
                claimed.plan().delivery().attemptCount());
    }

    static UUID providerIdempotencyKey(NotificationPlan plan) {
        return NotificationProviderRequest.stableIdempotencyKey(
                plan.action().parameters().organizationId(),
                plan.action().authority().connectionId(),
                plan.action().id(),
                plan.action().digest(),
                plan.action().authority().deduplicationKey());
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration candidate;
        try {
            candidate = retryDelay.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            candidate = maximumRetryDelay;
        }
        return candidate.compareTo(maximumRetryDelay) > 0 ? maximumRetryDelay : candidate;
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
            throw new IllegalArgumentException("Notification Worker duration is out of range");
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
