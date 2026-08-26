package io.crewscope.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationIntent;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationPlannedAction;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.notification.NotificationRecipientMappingId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.notification.NotificationTemplate;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateStatus;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.notification.NotificationVariableSpec;
import io.crewscope.domain.notification.TeamNotificationPolicyId;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M6-I03 transaction boundary, deduplication and response-loss recovery tests. */
class NotificationWorkerM6I03Test {

    private static final Instant BASE = Instant.parse("2026-08-26T08:00:00Z");

    private MutableTime time;
    private GuardedTransactions transactions;
    private InMemoryDispatches dispatches;
    private NotificationAuthorizationFacts facts;

    @BeforeEach
    void setUp() {
        time = new MutableTime(BASE);
        transactions = new GuardedTransactions();
        facts = facts();
        NotificationAuthorizationSnapshot authority =
                NotificationAuthorizationSnapshot.captureAutomatic(facts);
        NotificationPlannedAction action = NotificationPlannedAction.plan(
                facts, authority, time.now(), UtcTimestamp.from(BASE.plusSeconds(3600)),
                Optional.empty());
        dispatches = new InMemoryDispatches(
                new NotificationPlan(action, NotificationDelivery.ready(action, time.now())));
    }

    @Test
    void providerCallStartsOnlyAfterClaimCommitAndDuplicatePollDoesNotResend() {
        AtomicInteger sends = new AtomicInteger();
        NotificationProviderPort provider = new ProviderStub() {
            @Override
            public NotificationSendResult send(
                    NotificationProviderRequest request, NotificationCredentialHandle credential) {
                assertFalse(transactions.inTransaction);
                sends.incrementAndGet();
                return NotificationSendResult.accepted(
                        "provider-receipt", "message-id", "PROVIDER_ACCEPTED");
            }
        };
        NotificationWorker worker = worker(provider, 5);

        NotificationWorkerBatchResult first = worker.runOnce(facts.intent().organizationId());
        NotificationWorkerBatchResult replay = worker.runOnce(facts.intent().organizationId());

        assertEquals(1, first.succeeded());
        assertEquals(0, replay.claimed());
        assertEquals(1, sends.get());
        assertEquals(NotificationDeliveryStatus.SUCCEEDED, dispatches.plan.delivery().status());
        assertTrue(dispatches.plan.delivery().receipt().isPresent());
    }

    @Test
    void lostWriteResponseBecomesUnknownAndQueryRecoversTheSameProviderOperation() {
        class ResponseLossProvider extends ProviderStub {
            UUID writeKey;
            UUID queryKey;

            @Override
            public NotificationSendResult send(
                    NotificationProviderRequest request, NotificationCredentialHandle credential) {
                assertFalse(transactions.inTransaction);
                writeKey = request.idempotencyKey();
                throw new IllegalStateException("sanitized response loss");
            }

            @Override
            public NotificationQueryResult query(
                    NotificationProviderRequest request, NotificationCredentialHandle credential) {
                assertFalse(transactions.inTransaction);
                queryKey = request.idempotencyKey();
                return NotificationQueryResult.found(
                        "provider-receipt", "message-id", "PROVIDER_QUERY_FOUND");
            }
        }
        ResponseLossProvider provider = new ResponseLossProvider();
        NotificationWorker writer = worker(provider, 5);

        NotificationWorkerBatchResult write = writer.runOnce(facts.intent().organizationId());
        assertEquals(1, write.uncertain());
        assertEquals(NotificationDeliveryStatus.UNKNOWN, dispatches.plan.delivery().status());

        time.advance(Duration.ofSeconds(2));
        NotificationReconciliationWorker reconciler = reconciler(provider, 5);
        NotificationWorkerBatchResult recovered = reconciler.runOnce(
                facts.intent().organizationId());

        assertEquals(1, recovered.succeeded());
        assertEquals(provider.writeKey, provider.queryKey);
        assertEquals(NotificationDeliveryStatus.SUCCEEDED, dispatches.plan.delivery().status());
        assertEquals(1, dispatches.plan.delivery().attemptCount());
    }

    @Test
    void boundedRetryEndsWithOneLogicalFailureReceipt() {
        NotificationProviderPort provider = new ProviderStub() {
            @Override
            public NotificationSendResult send(
                    NotificationProviderRequest request, NotificationCredentialHandle credential) {
                return NotificationSendResult.retryable("PROVIDER_RATE_LIMITED");
            }
        };
        NotificationWorker worker = worker(provider, 2);

        assertEquals(1, worker.runOnce(facts.intent().organizationId()).retryScheduled());
        time.advance(Duration.ofSeconds(2));
        assertEquals(1, worker.runOnce(facts.intent().organizationId()).failedFinal());

        assertEquals(NotificationDeliveryStatus.FAILED_FINAL, dispatches.plan.delivery().status());
        assertEquals(2, dispatches.plan.delivery().attemptCount());
        assertTrue(dispatches.plan.delivery().receipt().isPresent());
        assertEquals(0, worker.runOnce(facts.intent().organizationId()).claimed());
    }

    @Test
    void auditedRecoveryScheduleCreatesAndAcknowledgesOneReplacementDelivery() {
        NotificationRecoveryScheduleRepository schedules =
                mock(NotificationRecoveryScheduleRepository.class);
        NotificationPlanningApplicationService planning =
                mock(NotificationPlanningApplicationService.class);
        NotificationWorkerId workerId = new NotificationWorkerId("redelivery");
        NotificationRecoveryClaim claim = new NotificationRecoveryClaim(
                facts.intent().organizationId(), UUID.randomUUID(),
                NotificationRedeliveryCommandId.generate(), dispatches.plan.delivery().id(),
                2, workerId, 1, UtcTimestamp.from(BASE.plusSeconds(60)));
        NotificationDelivery replacement = mock(NotificationDelivery.class);
        NotificationPlan replacementPlan = mock(NotificationPlan.class);
        NotificationRedeliveryRecord record = mock(NotificationRedeliveryRecord.class);
        var replacementId = io.crewscope.domain.notification.NotificationDeliveryId
                .fromDeduplicationKey(dispatches.plan.delivery().deduplicationKey());
        when(schedules.claim(
                        eq(claim.organizationId()), eq(workerId), any(), eq(Duration.ofMinutes(1))))
                .thenReturn(Optional.of(claim), Optional.empty());
        when(planning.redeliverScheduled(
                        claim.commandId(), claim.organizationId(), claim.originalDeliveryId(),
                        claim.expectedDeliveryVersion()))
                .thenReturn(record);
        when(record.plan()).thenReturn(replacementPlan);
        when(replacementPlan.delivery()).thenReturn(replacement);
        when(replacement.id()).thenReturn(replacementId);
        NotificationRedeliveryWorker worker = new NotificationRedeliveryWorker(
                schedules, planning, transactions, time, workerId, Duration.ofMinutes(1), 10);

        assertEquals(1, worker.runOnce(claim.organizationId()));

        verify(schedules).complete(eq(claim), eq(replacementId), eq(time.now()));
    }

    private NotificationWorker worker(NotificationProviderPort provider, int attempts) {
        return new NotificationWorker(
                dispatches, ignored -> facts, new CredentialIssuer(time), provider,
                transactions, time, new NotificationWorkerId("writer"),
                Duration.ofMinutes(1), Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofMinutes(1), attempts, 10);
    }

    private NotificationReconciliationWorker reconciler(
            NotificationProviderPort provider, int attempts) {
        return new NotificationReconciliationWorker(
                dispatches, ignored -> facts, new CredentialIssuer(time), provider,
                transactions, time, new NotificationWorkerId("reconciler"),
                Duration.ofMinutes(1), Duration.ofSeconds(10), Duration.ofSeconds(1),
                attempts, 10);
    }

    private static NotificationAuthorizationFacts facts() {
        OrganizationId organization = OrganizationId.generate();
        TeamId team = TeamId.generate();
        TeamMemberId member = TeamMemberId.generate();
        InboxSourceKey source = new InboxSourceKey(
                organization, member, InboxItemType.REVIEW,
                InboxSourceType.REVIEW_REQUEST, UUID.randomUUID(), InboxSourceRevision.INITIAL);
        NotificationTemplate template = new NotificationTemplate(
                new NotificationTemplateRef(
                        NotificationTemplateId.generate(), new NotificationTemplateVersion(1)),
                "review-required",
                Map.of("itemType", NotificationVariableSpec.text("itemType", 40)),
                NotificationTemplateStatus.PUBLISHED);
        NotificationIntent intent = new NotificationIntent(
                new NotificationIntentId(UUID.randomUUID()), organization, team, member, source,
                ProjectionGeneration.FIRST, SchemaVersion.V1, template.ref(),
                template.validateVariables(Map.of("itemType", "REVIEW")),
                UtcTimestamp.from(BASE));
        return new NotificationAuthorizationFacts(
                intent, NotificationRecipientMappingId.generate(), 0,
                ProviderBindingId.generate(), 0, ConnectionId.generate(), 0,
                ConnectionGrantId.generate(), 0, TeamNotificationPolicyId.generate(), 0,
                new NotificationPreference(
                        member, true, Set.of(InboxItemType.REVIEW), Optional.empty(), 0));
    }

    private static class ProviderStub implements NotificationProviderPort {
        @Override
        public NotificationSendResult send(
                NotificationProviderRequest request, NotificationCredentialHandle credential) {
            return NotificationSendResult.accepted("receipt", "message", "PROVIDER_ACCEPTED");
        }

        @Override
        public NotificationQueryResult query(
                NotificationProviderRequest request, NotificationCredentialHandle credential) {
            return NotificationQueryResult.notFound("PROVIDER_NOT_FOUND");
        }
    }

    private static final class CredentialIssuer implements NotificationCredentialIssuer {
        private final TimeProvider time;

        private CredentialIssuer(TimeProvider time) {
            this.time = time;
        }

        @Override
        public NotificationCredentialHandle issue(
                NotificationPlan plan, NotificationClaim claim, Duration timeToLive) {
            UtcTimestamp expiresAt = UtcTimestamp.from(time.now().value().plus(timeToLive));
            return new NotificationCredentialHandle() {
                private boolean closed;

                @Override
                public <T> T useSecret(NotificationCredentialOperation<T> operation) {
                    return operation.apply(new byte[] {1});
                }
                @Override
                public UtcTimestamp expiresAt() { return expiresAt; }
                @Override
                public boolean isClosed() { return closed; }
                @Override
                public void close() { closed = true; }
                @Override
                public String toString() { return "TestCredential[secret=REDACTED]"; }
            };
        }
    }

    private static final class GuardedTransactions implements TransactionExecutor {
        private boolean inTransaction;

        @Override
        public <T> T required(Supplier<T> operation) {
            if (inTransaction) {
                return operation.get();
            }
            inTransaction = true;
            try {
                return operation.get();
            } finally {
                inTransaction = false;
            }
        }
    }

    private static final class MutableTime implements TimeProvider {
        private Instant current;

        private MutableTime(Instant current) {
            this.current = current;
        }

        @Override
        public UtcTimestamp now() {
            return UtcTimestamp.from(current);
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    private static final class InMemoryDispatches implements NotificationDispatchRepository {
        private NotificationPlan plan;
        private long token;
        private int reconciliationCount;
        private Optional<NotificationClaim> active = Optional.empty();

        private InMemoryDispatches(NotificationPlan plan) {
            this.plan = plan;
        }

        @Override
        public List<OrganizationId> findExecutionOrganizations(UtcTimestamp now, int limit) {
            return executable(now) ? List.of(plan.action().parameters().organizationId()) : List.of();
        }

        @Override
        public Optional<ClaimedNotification> claimExecution(
                OrganizationId organizationId,
                NotificationWorkerId workerId,
                UtcTimestamp now,
                Duration leaseDuration) {
            if (!executable(now)) {
                return Optional.empty();
            }
            NotificationDelivery delivery = plan.delivery().start(
                    plan.delivery().version(), plan.action(), now);
            plan = new NotificationPlan(plan.action(), delivery);
            NotificationClaim claim = new NotificationClaim(
                    delivery.id(), workerId, ++token, delivery.version(), reconciliationCount,
                    UtcTimestamp.from(now.value().plus(leaseDuration)));
            active = Optional.of(claim);
            return Optional.of(new ClaimedNotification(plan, claim));
        }

        @Override
        public List<OrganizationId> findReconciliationOrganizations(
                UtcTimestamp now, Duration retryDelay, int limit) {
            return reconcilable(now, retryDelay)
                    ? List.of(plan.action().parameters().organizationId()) : List.of();
        }

        @Override
        public Optional<ClaimedNotification> claimReconciliation(
                OrganizationId organizationId,
                NotificationWorkerId workerId,
                UtcTimestamp now,
                Duration leaseDuration,
                Duration retryDelay) {
            if (!reconcilable(now, retryDelay)) {
                return Optional.empty();
            }
            NotificationDelivery delivery = plan.delivery().beginReconciliation(
                    plan.delivery().version(), now);
            plan = new NotificationPlan(plan.action(), delivery);
            NotificationClaim claim = new NotificationClaim(
                    delivery.id(), workerId, ++token, delivery.version(), ++reconciliationCount,
                    UtcTimestamp.from(now.value().plus(leaseDuration)));
            active = Optional.of(claim);
            return Optional.of(new ClaimedNotification(plan, claim));
        }

        @Override
        public NotificationPlan updateClaimed(
                OrganizationId organizationId,
                NotificationClaim claim,
                NotificationPlan outcome,
                UtcTimestamp authoritativeNow) {
            NotificationClaim current = active.orElseThrow();
            if (current.fencingToken() != claim.fencingToken()
                    || current.deliveryVersion() != claim.deliveryVersion()
                    || authoritativeNow.compareTo(current.leaseExpiresAt()) >= 0
                    || plan.delivery().version() != claim.deliveryVersion()) {
                throw new OptimisticLockConflictException(
                        "NotificationDelivery", claim.deliveryId(),
                        claim.deliveryVersion(), plan.delivery().version());
            }
            plan = outcome;
            active = Optional.empty();
            return plan;
        }

        private boolean executable(UtcTimestamp now) {
            return active.isEmpty()
                    && (plan.delivery().status() == NotificationDeliveryStatus.READY
                        || (plan.delivery().status() == NotificationDeliveryStatus.RETRY_WAIT
                            && plan.delivery().nextAttemptAt().orElseThrow().compareTo(now) <= 0));
        }

        private boolean reconcilable(UtcTimestamp now, Duration retryDelay) {
            return active.isEmpty()
                    && plan.delivery().status() == NotificationDeliveryStatus.UNKNOWN
                    && plan.delivery().updatedAt().value().plus(retryDelay).compareTo(now.value()) <= 0;
        }
    }
}
