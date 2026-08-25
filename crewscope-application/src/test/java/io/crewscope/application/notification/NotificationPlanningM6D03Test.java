package io.crewscope.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationFailureCode;
import io.crewscope.domain.notification.NotificationIntent;
import io.crewscope.domain.notification.NotificationPlannedActionStatus;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.notification.NotificationReceipt;
import io.crewscope.domain.notification.NotificationReceiptId;
import io.crewscope.domain.notification.NotificationRecipientMappingId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.notification.NotificationTemplate;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateStatus;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.notification.NotificationVariableSpec;
import io.crewscope.domain.notification.TeamNotificationPolicyId;
import io.crewscope.domain.notification.TrustedNotificationOrigin;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationPlanningM6D03Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000651");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000652");
    private static final TeamMemberId MEMBER_ID =
            TeamMemberId.from("00000000-0000-0000-0000-000000000653");
    private static final NotificationTemplateId TEMPLATE_ID = new NotificationTemplateId(
            UUID.fromString("00000000-0000-0000-0000-000000000654"));
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T10:00:00Z");

    private TestCatalog catalog;
    private MutableFactsResolver factsResolver;
    private InMemoryPlans plans;
    private NotificationPlanningApplicationService service;
    private boolean recipientAuthorized;

    @BeforeEach
    void setUp() {
        catalog = new TestCatalog(template(1));
        factsResolver = new MutableFactsResolver();
        plans = new InMemoryPlans();
        recipientAuthorized = true;
        service = new NotificationPlanningApplicationService(
                catalog,
                factsResolver,
                (organizationId, memberId, actor) -> {
                    if (!ORGANIZATION_ID.equals(organizationId)
                            || !MEMBER_ID.equals(memberId)
                            || !actor.canAct()
                            || !recipientAuthorized) {
                        throw new IllegalArgumentException("Actor is not the active recipient");
                    }
                },
                plans,
                (TimeProvider) () -> NOW,
                Duration.ofHours(1));
    }

    @Test
    void duplicateAutomaticPlanReturnsOneActionAndOneDelivery() {
        NotificationIntent intent = intent(catalog.current);
        factsResolver.current = facts(intent, 1);

        NotificationPlan first = service.plan(intent);
        NotificationPlan replay = service.plan(intent);

        assertSame(first, replay);
        assertEquals(1, plans.byDelivery.size());
        assertEquals(NotificationDeliveryStatus.READY, first.delivery().status());
    }

    @Test
    void preferenceDriftInvalidatesPreviousPlanAndCreatesNewDigest() {
        NotificationIntent intent = intent(catalog.current);
        factsResolver.current = facts(intent, 1);
        NotificationPlan original = service.plan(intent);
        factsResolver.current = facts(intent, 2);

        NotificationPlan replacement = service.plan(intent);
        NotificationPlan historical = plans.byDelivery.get(original.delivery().id());

        assertNotEquals(original.action().authority().digest(), replacement.action().authority().digest());
        assertEquals(NotificationPlannedActionStatus.INVALIDATED, historical.action().status());
        assertEquals(NotificationDeliveryStatus.INVALIDATED, historical.delivery().status());
        assertEquals(2, plans.byDelivery.size());
    }

    @Test
    void supersededTemplateVersionFailsClosed() {
        NotificationTemplate old = catalog.current;
        NotificationIntent intent = intent(old);
        factsResolver.current = facts(intent, 1);
        catalog.current = template(2);

        assertThrows(DomainValidationException.class, () -> service.plan(intent));
        assertEquals(0, plans.byDelivery.size());
    }

    @Test
    void redeliveryCommandIsIdempotentAndDifferentCommandCreatesNewSend() {
        NotificationIntent intent = intent(catalog.current);
        factsResolver.current = facts(intent, 1);
        NotificationPlan planned = service.plan(intent);
        NotificationDelivery running = planned.delivery().start(
                planned.delivery().version(), planned.action(), NOW);
        NotificationReceipt failedReceipt = NotificationReceipt.failed(
                NotificationReceiptId.generate(),
                running,
                planned.action(),
                NotificationFailureCode.RETRY_EXHAUSTED,
                "RETRY_EXHAUSTED",
                NOW);
        NotificationDelivery failed = running.failFinal(running.version(), failedReceipt);
        plans.update(new NotificationPlan(planned.action(), failed));
        Principal actor = actor();
        NotificationRedeliveryCommandId commandId = NotificationRedeliveryCommandId.generate();
        RedeliverNotificationCommand command = new RedeliverNotificationCommand(
                commandId, ORGANIZATION_ID, failed.id(), failed.version(), actor);

        NotificationRedeliveryRecord first = service.redeliver(command);
        NotificationRedeliveryRecord replay = service.redeliver(command);
        NotificationRedeliveryRecord second = service.redeliver(new RedeliverNotificationCommand(
                NotificationRedeliveryCommandId.generate(),
                ORGANIZATION_ID,
                failed.id(),
                failed.version(),
                actor));

        assertSame(first, replay);
        assertNotEquals(first.plan().delivery().id(), second.plan().delivery().id());
        assertEquals(failed.id(), first.plan().delivery().redeliveryOf().orElseThrow());
        assertEquals(failedReceipt, plans.byDelivery.get(failed.id()).delivery().receipt().orElseThrow());
        assertEquals(3, plans.byDelivery.size());
    }

    @Test
    void redeliveryReplayRechecksCurrentRecipientAuthorization() {
        NotificationIntent intent = intent(catalog.current);
        factsResolver.current = facts(intent, 1);
        NotificationPlan planned = service.plan(intent);
        NotificationDelivery running = planned.delivery().start(
                planned.delivery().version(), planned.action(), NOW);
        NotificationReceipt failedReceipt = NotificationReceipt.failed(
                NotificationReceiptId.generate(),
                running,
                planned.action(),
                NotificationFailureCode.RETRY_EXHAUSTED,
                "RETRY_EXHAUSTED",
                NOW);
        NotificationDelivery failed = running.failFinal(running.version(), failedReceipt);
        plans.update(new NotificationPlan(planned.action(), failed));
        RedeliverNotificationCommand command = new RedeliverNotificationCommand(
                NotificationRedeliveryCommandId.generate(),
                ORGANIZATION_ID,
                failed.id(),
                failed.version(),
                actor());

        service.redeliver(command);
        recipientAuthorized = false;

        assertThrows(IllegalArgumentException.class, () -> service.redeliver(command));
        assertEquals(2, plans.byDelivery.size());
    }

    @Test
    void redeliveryRequiresFinalFailureAndStrongVersion() {
        NotificationIntent intent = intent(catalog.current);
        factsResolver.current = facts(intent, 1);
        NotificationPlan planned = service.plan(intent);

        assertThrows(
                DomainValidationException.class,
                () -> service.redeliver(new RedeliverNotificationCommand(
                        NotificationRedeliveryCommandId.generate(),
                        ORGANIZATION_ID,
                        planned.delivery().id(),
                        planned.delivery().version(),
                        actor())));
        assertThrows(
                IllegalStateException.class,
                () -> service.redeliver(new RedeliverNotificationCommand(
                        NotificationRedeliveryCommandId.generate(),
                        ORGANIZATION_ID,
                        planned.delivery().id(),
                        planned.delivery().version() + 1,
                        actor())));
    }

    private static NotificationTemplate template(long version) {
        return new NotificationTemplate(
                new NotificationTemplateRef(TEMPLATE_ID, new NotificationTemplateVersion(version)),
                "review-required",
                Map.of(
                        "workItemTitle", NotificationVariableSpec.text("workItemTitle", 200),
                        "reviewUrl", NotificationVariableSpec.trustedLink(
                                "reviewUrl",
                                500,
                                Set.of(TrustedNotificationOrigin.https("crewscope.example")))),
                NotificationTemplateStatus.PUBLISHED);
    }

    private static NotificationIntent intent(NotificationTemplate template) {
        InboxSourceKey key = new InboxSourceKey(
                ORGANIZATION_ID,
                MEMBER_ID,
                InboxItemType.REVIEW,
                InboxSourceType.REVIEW_REQUEST,
                UUID.fromString("00000000-0000-0000-0000-000000000655"),
                InboxSourceRevision.INITIAL);
        InboxItem item = InboxItem.project(
                TEAM_ID,
                new ProjectionName("member-inbox"),
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                InboxSource.open(key, InboxPriority.HIGH, Optional.empty(), NOW));
        return NotificationIntent.fromOpenInbox(
                item,
                template,
                Map.of(
                        "workItemTitle", "Review release branch",
                        "reviewUrl", "https://crewscope.example/reviews/42"),
                NOW);
    }

    private static NotificationAuthorizationFacts facts(
            NotificationIntent intent, long preferenceVersion) {
        return new NotificationAuthorizationFacts(
                intent,
                new NotificationRecipientMappingId(
                        UUID.fromString("00000000-0000-0000-0000-000000000656")),
                1,
                new ProviderBindingId(
                        UUID.fromString("00000000-0000-0000-0000-000000000657")),
                1,
                new ConnectionId(
                        UUID.fromString("00000000-0000-0000-0000-000000000658")),
                1,
                new ConnectionGrantId(
                        UUID.fromString("00000000-0000-0000-0000-000000000659")),
                1,
                new TeamNotificationPolicyId(
                        UUID.fromString("00000000-0000-0000-0000-000000000660")),
                1,
                new NotificationPreference(
                        MEMBER_ID,
                        true,
                        Set.of(InboxItemType.REVIEW),
                        Optional.empty(),
                        preferenceVersion));
    }

    private static Principal actor() {
        return Principal.create(
                PrincipalId.from("00000000-0000-0000-0000-000000000661"),
                PrincipalScope.team(ORGANIZATION_ID, TEAM_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Recipient",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
    }

    private static final class TestCatalog implements NotificationTemplateCatalog {
        private NotificationTemplate current;

        private TestCatalog(NotificationTemplate current) {
            this.current = current;
        }

        @Override
        public NotificationTemplate requireCurrentPublished(NotificationTemplateRef ref) {
            if (!current.ref().equals(ref)
                    || current.status() != NotificationTemplateStatus.PUBLISHED) {
                throw new DomainValidationException(
                        "notificationTemplate.ref", "must be the current published version");
            }
            return current;
        }
    }

    private static final class MutableFactsResolver
            implements NotificationAuthorizationFactsResolver {
        private NotificationAuthorizationFacts current;

        @Override
        public NotificationAuthorizationFacts resolveCurrent(
                io.crewscope.domain.notification.NotificationIntentId intentId) {
            if (current == null || !current.intent().id().equals(intentId)) {
                throw new IllegalArgumentException("Intent was not found");
            }
            return current;
        }
    }

    private static final class InMemoryPlans implements NotificationPlanRepository {
        private final Map<io.crewscope.domain.notification.NotificationDeduplicationKey,
                        NotificationPlan>
                byDedup = new HashMap<>();
        private final Map<io.crewscope.domain.notification.NotificationDeliveryId,
                        NotificationPlan>
                byDelivery = new LinkedHashMap<>();
        private final Map<io.crewscope.domain.notification.NotificationIntentId,
                        NotificationPlan>
                latest = new HashMap<>();
        private final Map<NotificationRedeliveryCommandId, NotificationRedeliveryRecord>
                redeliveries = new HashMap<>();

        @Override
        public Optional<NotificationPlan> findByDeduplicationKey(
                OrganizationId organizationId,
                io.crewscope.domain.notification.NotificationDeduplicationKey key) {
            return Optional.ofNullable(byDedup.get(key));
        }

        @Override
        public Optional<NotificationPlan> findLatestByIntent(
                OrganizationId organizationId,
                io.crewscope.domain.notification.NotificationIntentId intentId) {
            return Optional.ofNullable(latest.get(intentId));
        }

        @Override
        public Optional<NotificationPlan> findByDeliveryId(
                OrganizationId organizationId,
                io.crewscope.domain.notification.NotificationDeliveryId deliveryId) {
            return Optional.ofNullable(byDelivery.get(deliveryId));
        }

        @Override
        public Optional<NotificationRedeliveryRecord> findRedelivery(
                OrganizationId organizationId, NotificationRedeliveryCommandId commandId) {
            return Optional.ofNullable(redeliveries.get(commandId));
        }

        @Override
        public NotificationPlan save(NotificationPlan plan) {
            NotificationPlan existing = byDedup.putIfAbsent(
                    plan.action().authority().deduplicationKey(), plan);
            if (existing != null) {
                return existing;
            }
            index(plan);
            return plan;
        }

        @Override
        public NotificationPlan update(NotificationPlan plan) {
            if (!byDelivery.containsKey(plan.delivery().id())) {
                throw new IllegalArgumentException("Plan was not found");
            }
            byDedup.put(plan.action().authority().deduplicationKey(), plan);
            byDelivery.put(plan.delivery().id(), plan);
            return plan;
        }

        @Override
        public NotificationPlan replaceDrifted(
                NotificationPlan invalidatedPlan, NotificationPlan replacementPlan) {
            update(invalidatedPlan);
            return save(replacementPlan);
        }

        @Override
        public NotificationRedeliveryRecord saveRedelivery(
                NotificationRedeliveryRecord record) {
            NotificationRedeliveryRecord existing = redeliveries.get(record.commandId());
            if (existing != null) {
                return existing;
            }
            NotificationPlan saved = save(record.plan());
            NotificationRedeliveryRecord committed = new NotificationRedeliveryRecord(
                    record.commandId(), record.originalDeliveryId(), saved);
            redeliveries.put(committed.commandId(), committed);
            return committed;
        }

        private void index(NotificationPlan plan) {
            byDelivery.put(plan.delivery().id(), plan);
            latest.put(plan.action().parameters().intentId(), plan);
        }
    }
}
