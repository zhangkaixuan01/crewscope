package io.crewscope.infrastructure.persistence.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.notification.NotificationPlan;
import io.crewscope.application.notification.ClaimedNotification;
import io.crewscope.application.notification.NotificationWorkerId;
import io.crewscope.application.notification.NotificationRedeliveryRecord;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationFailureCode;
import io.crewscope.domain.notification.NotificationIntent;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationPlannedAction;
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
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.infrastructure.event.projection.InboxEventProjector;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL atomic-plan, optimistic transition and redelivery contract for M6-I01. */
@SpringBootTest(
        classes = JdbcNotificationPlanRepositoryM6I01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class JdbcNotificationPlanRepositoryM6I01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T07:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private TeamId teamId;
    private TeamMemberId memberId;
    private NotificationAuthorizationFacts facts;
    private NotificationPlan plan;
    private JdbcNotificationPlanRepositoryAdapter repository;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                InboxEventProjector.PROJECTION_NAME.value());
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        memberId = TeamMemberId.generate();
        seedScopeAndProjection();
        facts = facts();
        seedIntent(facts.intent());
        NotificationAuthorizationSnapshot authority =
                NotificationAuthorizationSnapshot.captureAutomatic(facts);
        NotificationPlannedAction action = NotificationPlannedAction.plan(
                facts, authority, UtcTimestamp.from(NOW),
                UtcTimestamp.from(NOW.plusSeconds(3600)), Optional.empty());
        plan = new NotificationPlan(action, NotificationDelivery.ready(
                action, UtcTimestamp.from(NOW)));
        repository = new JdbcNotificationPlanRepositoryAdapter(jdbc);
    }

    @Test
    void savesAndReloadsTheExactAuthorizationObjectGraph() {
        NotificationPlan saved = inReplica(() -> repository.save(plan));

        NotificationPlan loaded = repository.findByDeduplicationKey(
                        organizationId, plan.action().authority().deduplicationKey())
                .orElseThrow();

        assertEquals(saved.action().id(), loaded.action().id());
        assertEquals(saved.action().digest(), loaded.action().digest());
        assertEquals(saved.action().authority().digest(), loaded.action().authority().digest());
        assertEquals(saved.delivery().id(), loaded.delivery().id());
        assertEquals(1, count("notification_planned_action"));
        assertEquals(1, count("notification_delivery"));
        assertTrue(repository.findByDeduplicationKey(
                OrganizationId.generate(), plan.action().authority().deduplicationKey()).isEmpty());
    }

    @Test
    void concurrentDeduplicationConvergesOnOneLogicalPlan() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<NotificationPlan> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return inReplica(() -> repository.save(plan));
            });
            Future<NotificationPlan> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return inReplica(() -> repository.save(plan));
            });
            ready.await();
            start.countDown();

            assertEquals(first.get().delivery().id(), second.get().delivery().id());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("notification_planned_action"));
        assertEquals(1, count("notification_delivery"));
    }

    @Test
    void deliveryTransitionsUseCasAndPersistOneTerminalReceipt() {
        inReplica(() -> repository.save(plan));
        ClaimedNotification claimed = inReplica(() -> repository.claimExecution(
                        organizationId, new NotificationWorkerId("m6-i01-test"),
                        UtcTimestamp.from(NOW.plusSeconds(1)), Duration.ofMinutes(1)))
                .orElseThrow();
        NotificationDelivery running = claimed.plan().delivery();
        NotificationReceipt receipt = NotificationReceipt.failed(
                NotificationReceiptId.generate(), running, plan.action(),
                NotificationFailureCode.PROVIDER_REJECTED, "PROVIDER_REJECTED",
                UtcTimestamp.from(NOW.plusSeconds(2)));
        NotificationDelivery failed = running.failFinal(1, receipt);

        repository.updateClaimed(
                organizationId, claimed.claim(), new NotificationPlan(plan.action(), failed),
                UtcTimestamp.from(NOW.plusSeconds(2)));

        NotificationPlan loaded = repository.findByDeliveryId(
                organizationId, failed.id()).orElseThrow();
        assertEquals(NotificationDeliveryStatus.FAILED_FINAL, loaded.delivery().status());
        assertEquals(receipt.id(), loaded.delivery().receipt().orElseThrow().id());
        assertEquals(1, count("notification_receipt"));
        assertThrows(RuntimeException.class,
                () -> repository.updateClaimed(
                        organizationId, claimed.claim(), new NotificationPlan(plan.action(), failed),
                        UtcTimestamp.from(NOW.plusSeconds(2))));
    }

    @Test
    void redeliveryCommandReceiptReplaysTheSameReplacement() {
        inReplica(() -> repository.save(plan));
        NotificationRedeliveryCommandId commandId = NotificationRedeliveryCommandId.generate();
        NotificationAuthorizationSnapshot authority =
                NotificationAuthorizationSnapshot.captureRedelivery(
                        facts, plan.delivery().id(), commandId);
        NotificationPlannedAction action = NotificationPlannedAction.plan(
                facts, authority, UtcTimestamp.from(NOW.plusSeconds(10)),
                UtcTimestamp.from(NOW.plusSeconds(3610)), Optional.of(plan.delivery().id()));
        NotificationPlan replacement = new NotificationPlan(
                action, NotificationDelivery.ready(action, UtcTimestamp.from(NOW.plusSeconds(10))));
        NotificationRedeliveryRecord requested = new NotificationRedeliveryRecord(
                commandId, plan.delivery().id(), replacement);

        NotificationRedeliveryRecord first = inReplica(
                () -> repository.saveRedelivery(requested));
        NotificationRedeliveryRecord replay = repository.findRedelivery(
                organizationId, commandId).orElseThrow();

        assertEquals(first.plan().delivery().id(), replay.plan().delivery().id());
        assertEquals(plan.delivery().id(), replay.originalDeliveryId());
        assertEquals(1, count("notification_redelivery_receipt"));
    }

    @Test
    void persistedDigestTamperingFailsClosed() {
        inReplica(() -> repository.save(plan));
        inReplica(() -> {
            jdbc.update(
                    "UPDATE crewscope.notification_planned_action SET authorization_digest = ?",
                    "f".repeat(64));
            return null;
        });

        assertThrows(RuntimeException.class, () -> repository.findByDeliveryId(
                organizationId, plan.delivery().id()));
    }

    @Test
    void concurrentWorkerClaimsConvergeOnOneRunningDelivery() throws Exception {
        inReplica(() -> repository.save(plan));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ClaimedNotification>> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return inReplica(() -> repository.claimExecution(
                        organizationId, new NotificationWorkerId("worker-a"),
                        UtcTimestamp.from(NOW.plusSeconds(1)), Duration.ofMinutes(1)));
            });
            Future<Optional<ClaimedNotification>> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return inReplica(() -> repository.claimExecution(
                        organizationId, new NotificationWorkerId("worker-b"),
                        UtcTimestamp.from(NOW.plusSeconds(1)), Duration.ofMinutes(1)));
            });
            ready.await();
            start.countDown();

            assertEquals(1, (first.get().isPresent() ? 1 : 0) + (second.get().isPresent() ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT status FROM crewscope.notification_delivery", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT claim_token FROM crewscope.notification_delivery", Long.class));
    }

    @Test
    void expiredWriterIsQueryTakenOverAndItsOldTokenCannotWriteAReceipt() {
        inReplica(() -> repository.save(plan));
        ClaimedNotification writer = inReplica(() -> repository.claimExecution(
                        organizationId, new NotificationWorkerId("expired-writer"),
                        UtcTimestamp.from(NOW.plusSeconds(1)), Duration.ofSeconds(5)))
                .orElseThrow();

        ClaimedNotification reconciler = inReplica(() -> repository.claimReconciliation(
                        organizationId, new NotificationWorkerId("reconciler"),
                        UtcTimestamp.from(NOW.plusSeconds(7)), Duration.ofMinutes(1),
                        Duration.ofSeconds(1)))
                .orElseThrow();

        assertEquals(NotificationDeliveryStatus.RECONCILING,
                reconciler.plan().delivery().status());
        assertTrue(reconciler.claim().fencingToken() > writer.claim().fencingToken());
        NotificationDelivery staleUnknown = writer.plan().delivery().markUnknown(
                writer.plan().delivery().version(), UtcTimestamp.from(NOW.plusSeconds(2)));
        assertThrows(RuntimeException.class, () -> inReplica(() -> repository.updateClaimed(
                organizationId, writer.claim(),
                new NotificationPlan(plan.action(), staleUnknown),
                UtcTimestamp.from(NOW.plusSeconds(2)))));

        NotificationReceipt recoveredReceipt = NotificationReceipt.accepted(
                NotificationReceiptId.fromDelivery(reconciler.plan().delivery().id()),
                reconciler.plan().delivery(), reconciler.plan().action(),
                "provider-reference", "provider-message", "PROVIDER_QUERY_FOUND",
                UtcTimestamp.from(NOW.plusSeconds(8)));
        NotificationDelivery recovered = reconciler.plan().delivery().succeed(
                reconciler.plan().delivery().version(), recoveredReceipt);
        inReplica(() -> repository.updateClaimed(
                organizationId, reconciler.claim(),
                new NotificationPlan(reconciler.plan().action(), recovered),
                UtcTimestamp.from(NOW.plusSeconds(8))));

        assertEquals(NotificationDeliveryStatus.SUCCEEDED,
                repository.findByDeliveryId(organizationId, recovered.id())
                        .orElseThrow().delivery().status());
        assertEquals(1, count("notification_receipt"));
    }

    private NotificationAuthorizationFacts facts() {
        InboxSourceKey sourceKey = new InboxSourceKey(
                organizationId, memberId, InboxItemType.REVIEW,
                InboxSourceType.REVIEW_REQUEST, UUID.randomUUID(), InboxSourceRevision.INITIAL);
        NotificationTemplate template = new NotificationTemplate(
                new NotificationTemplateRef(
                        NotificationTemplateId.generate(), new NotificationTemplateVersion(1)),
                "review-required",
                Map.of("itemType", NotificationVariableSpec.text("itemType", 40)),
                NotificationTemplateStatus.PUBLISHED);
        NotificationIntent intent = new NotificationIntent(
                new NotificationIntentId(UUID.randomUUID()), organizationId, teamId, memberId,
                sourceKey, ProjectionGeneration.FIRST, SchemaVersion.V1, template.ref(),
                template.validateVariables(Map.of("itemType", "REVIEW")),
                UtcTimestamp.from(NOW));
        return new NotificationAuthorizationFacts(
                intent, NotificationRecipientMappingId.generate(), 0,
                ProviderBindingId.generate(), 0, ConnectionId.generate(), 0,
                ConnectionGrantId.generate(), 0, TeamNotificationPolicyId.generate(), 0,
                new NotificationPreference(
                        memberId, true, Set.of(InboxItemType.REVIEW), Optional.empty(), 0));
    }

    private void seedScopeAndProjection() {
        UUID principalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) "
                        + "VALUES (?, 'Notification Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Notification Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'Notification Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?, ?, ?)
                """,
                memberId.value(), organizationId.value(), teamId.value(), principalId,
                NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC));
        jdbc.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 1, 1, 'inbox.canonical-v1', 'inbox.expected-v1')
                """,
                InboxEventProjector.PROJECTION_NAME.value());
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 1, 1, 'ACTIVE', 1, 0, ?, ?)
                    """,
                    organizationId.value(), InboxEventProjector.PROJECTION_NAME.value(),
                    NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    """,
                    organizationId.value(), InboxEventProjector.PROJECTION_NAME.value(),
                    NOW.atOffset(ZoneOffset.UTC));
            return null;
        });
    }

    private void seedIntent(NotificationIntent intent) {
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_intent (
                        organization_id, team_id, recipient_member_id,
                        projection_name, generation, intent_id, projection_schema_version,
                        inbox_item_id, item_type, source_type, source_id, source_revision,
                        template_id, template_version, variables, variable_hash, created_at
                    ) VALUES (?, ?, ?, ?, 1, ?, 1, ?, ?, ?, ?, ?, ?, ?,
                              CAST(? AS JSONB), ?, ?)
                    """,
                    organizationId.value(), teamId.value(), memberId.value(),
                    InboxEventProjector.PROJECTION_NAME.value(), intent.id().value(),
                    UUID.randomUUID(), intent.sourceKey().itemType().name(),
                    intent.sourceKey().sourceType().name(), intent.sourceKey().sourceId(),
                    intent.sourceKey().sourceRevision().value(),
                    intent.template().templateId().value(), intent.template().version().value(),
                    "{\"itemType\":\"REVIEW\"}", intent.variables().hash().toString(),
                    NOW.atOffset(ZoneOffset.UTC));
            return null;
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    private <T> T inReplica(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            return work.get();
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
