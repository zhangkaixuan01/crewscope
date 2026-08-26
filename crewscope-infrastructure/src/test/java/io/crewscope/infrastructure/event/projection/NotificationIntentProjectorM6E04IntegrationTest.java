package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.notification.CrewScopeNotificationIntentPolicies;
import io.crewscope.domain.collaboration.LarkExternalTenantId;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL contract for M6-E04 fixed-template intent planning and failure Inbox closure. */
@SpringBootTest(
        classes = NotificationIntentProjectorM6E04IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class NotificationIntentProjectorM6E04IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T04:00:00Z");
    private static final String CAPABILITY =
            "collaboration.notification.send-fixed-template";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private UUID teamId;
    private UUID workspaceId;
    private UUID principalId;
    private UUID memberId;
    private UUID sourceId;
    private UUID templateId;
    private Graph graph;
    private NotificationIntentProjector projector;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.execute("TRUNCATE TABLE crewscope.notification_template CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                InboxEventProjector.PROJECTION_NAME.value());
        seedScope();
        bootstrapProjection(ProjectionGeneration.FIRST, "ACTIVE", 1, true);
        seedTemplate(1);
        seedPreference(true, NOW.plusSeconds(600), 0);
        graph = seedLarkGraph(false);
        sourceId = UUID.randomUUID();
        seedReviewInbox(ProjectionGeneration.FIRST, sourceId);
        projector = new NotificationIntentProjector(
                jdbc,
                objectMapper,
                CrewScopeNotificationIntentPolicies.fixedRegistry(),
                "https://crewscope.example");
    }

    @Test
    void sameSourcePlansOnceAndDndDefersReadyDelivery() {
        seedMapping(graph);

        reconcile(lease(ProjectionGeneration.FIRST, 1));
        reconcile(lease(ProjectionGeneration.FIRST, 1));

        assertEquals(1, count("notification_intent"));
        assertEquals(1, count("notification_planned_action"));
        assertEquals(1, count("notification_delivery"));
        assertEquals("READY", text("SELECT status FROM crewscope.notification_delivery"));
        assertEquals(
                NOW.plusSeconds(600).atOffset(ZoneOffset.UTC),
                jdbc.queryForObject(
                        "SELECT not_before FROM crewscope.notification_planned_action",
                        java.time.OffsetDateTime.class));
        assertEquals("REVIEW", jdbc.queryForObject(
                "SELECT variables->>'itemType' FROM crewscope.notification_intent",
                String.class));
    }

    @Test
    void missingAndRevokedMappingProduceNoUnauthorizedWriteAndInvalidateExistingPlan() {
        reconcile(lease(ProjectionGeneration.FIRST, 1));
        assertEquals(1, count("notification_intent"));
        assertEquals(0, count("notification_planned_action"));

        UUID mappingId = seedMapping(graph);
        reconcile(lease(ProjectionGeneration.FIRST, 1));
        assertEquals(1, count("notification_planned_action"));
        jdbc.update(
                """
                UPDATE crewscope.lark_member_mapping
                SET status = 'REVOKED', terminal_reason = 'ADMIN_REVOKED',
                    version = version + 1, updated_at = ?, updated_by_principal_id = ?
                WHERE id = ?
                """,
                NOW.plusSeconds(30).atOffset(ZoneOffset.UTC), principalId, mappingId);

        reconcile(lease(ProjectionGeneration.FIRST, 1));

        assertEquals("INVALIDATED", text(
                "SELECT status FROM crewscope.notification_planned_action"));
        assertEquals("RECIPIENT_MAPPING", text(
                "SELECT invalidation_reason FROM crewscope.notification_delivery"));
        assertEquals("INVALIDATED", text(
                "SELECT result FROM crewscope.notification_receipt"));
    }

    @Test
    void templateUpgradeInvalidatesPinnedIntentAndAppliesToNextGeneration() {
        seedMapping(graph);
        reconcile(lease(ProjectionGeneration.FIRST, 1));
        String oldDigest = text(
                "SELECT authorization_digest FROM crewscope.notification_planned_action");
        jdbc.update(
                """
                UPDATE crewscope.notification_template
                SET status = 'RETIRED'
                WHERE template_id = ? AND template_version = 1
                """,
                templateId);
        seedTemplate(2);

        reconcile(lease(ProjectionGeneration.FIRST, 1));
        bootstrapProjection(new ProjectionGeneration(2), "BUILDING", 2, false);
        seedReviewInbox(new ProjectionGeneration(2), sourceId);
        reconcile(lease(new ProjectionGeneration(2), 2));
        switchPointerToGeneration2();
        projector.reconcileCurrentGeneration(
                organizationId, new TeamId(teamId), UtcTimestamp.from(NOW.plusSeconds(30)));

        assertEquals(2, count("notification_planned_action"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.notification_planned_action WHERE status = 'INVALIDATED'",
                Integer.class));
        assertEquals("TEMPLATE", text("""
                SELECT invalidation_reason FROM crewscope.notification_planned_action
                WHERE status = 'INVALIDATED'
                """));
        String newDigest = text("""
                SELECT authorization_digest FROM crewscope.notification_planned_action
                WHERE status = 'PLANNED'
                """);
        assertNotEquals(oldDigest, newDigest);
        assertEquals(List.of(1L, 2L), jdbc.queryForList(
                "SELECT template_version FROM crewscope.notification_intent ORDER BY generation",
                Long.class));
    }

    @Test
    void shadowBuildsIntentWithoutSchedulingAndPlansOnlyAfterPointerSwitch() {
        seedMapping(graph);
        bootstrapProjection(new ProjectionGeneration(2), "BUILDING", 2, false);
        seedReviewInbox(new ProjectionGeneration(2), sourceId);

        reconcile(lease(new ProjectionGeneration(2), 2));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.notification_intent WHERE generation = 2",
                Integer.class));
        assertEquals(0, count("notification_planned_action"));
        switchPointerToGeneration2();

        projector.reconcileCurrentGeneration(
                organizationId, new TeamId(teamId), UtcTimestamp.from(NOW.plusSeconds(30)));

        assertEquals(1, count("notification_planned_action"));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT generation FROM crewscope.notification_planned_action", Long.class));
    }

    @Test
    void resolvesOnlyTheCurrentGenerationPublishedTemplateAndExactAuthorizationFacts() {
        UUID mappingId = seedMapping(graph);
        reconcile(lease(ProjectionGeneration.FIRST, 1));
        NotificationIntentId intentId = new NotificationIntentId(jdbc.queryForObject(
                "SELECT intent_id FROM crewscope.notification_intent", UUID.class));

        var facts = projector.resolveCurrent(intentId);

        assertEquals(mappingId, facts.recipientMappingId().value());
        assertEquals(templateId, facts.intent().template().templateId().value());
        assertEquals(1, facts.intent().template().version().value());
        assertEquals(
                "review-required",
                projector.requireCurrentPublished(new NotificationTemplateRef(
                                new NotificationTemplateId(templateId),
                                new NotificationTemplateVersion(1)))
                        .serverTemplateKey());

        jdbc.update(
                "UPDATE crewscope.notification_template SET status = 'RETIRED' "
                        + "WHERE template_id = ? AND template_version = 1",
                templateId);
        assertThrows(IllegalStateException.class, () -> projector.resolveCurrent(intentId));
        assertThrows(
                IllegalStateException.class,
                () -> projector.requireCurrentPublished(facts.intent().template()));
    }

    @Test
    void finalFailureCreatesOneNonRecursiveInboxAndSuccessfulRedeliveryClosesIt() {
        seedMapping(graph);
        reconcile(lease(ProjectionGeneration.FIRST, 1));
        UUID failedDelivery = jdbc.queryForObject(
                "SELECT delivery_id FROM crewscope.notification_delivery", UUID.class);
        UUID failedReceipt = UUID.randomUUID();
        inReplica(() -> jdbc.update(
                """
                UPDATE crewscope.notification_delivery
                SET status = 'FAILED_FINAL', attempt_count = 1, receipt_id = ?,
                    version = 1, updated_at = ?
                WHERE delivery_id = ?
                """,
                failedReceipt, NOW.plusSeconds(20).atOffset(ZoneOffset.UTC), failedDelivery));

        reconcile(lease(ProjectionGeneration.FIRST, 1));

        assertEquals(1, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.inbox_item
                WHERE source_type = 'NOTIFICATION_DELIVERY' AND source_status = 'OPEN'
                """,
                Integer.class));
        // The failure Inbox is intentionally excluded from the policy registry.
        assertEquals(1, count("notification_planned_action"));
        seedSuccessfulRedelivery(failedDelivery);

        reconcile(lease(ProjectionGeneration.FIRST, 1));

        assertEquals("CLOSED", text("""
                SELECT source_status FROM crewscope.inbox_item
                WHERE source_type = 'NOTIFICATION_DELIVERY'
                """));
        assertEquals("EXCEPTION_RESOLVED", text("""
                SELECT close_reason FROM crewscope.inbox_item
                WHERE source_type = 'NOTIFICATION_DELIVERY'
                """));
        assertEquals(1, count("notification_intent"));
    }

    private void seedScope() {
        organizationId = OrganizationId.generate();
        teamId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        principalId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Notify Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'Notify Team', 'ACTIVE')",
                teamId, organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Notify Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId);
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Notify Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?, ?, ?)
                """,
                memberId, organizationId.value(), teamId, principalId,
                NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC));
    }

    private void bootstrapProjection(
            ProjectionGeneration generation,
            String status,
            long fencingToken,
            boolean pointer) {
        jdbc.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 1, 1, 'inbox.canonical-v1', 'inbox.expected-v1')
                ON CONFLICT DO NOTHING
                """,
                InboxEventProjector.PROJECTION_NAME.value());
        UUID rebuildJobId = status.equals("BUILDING") ? UUID.randomUUID() : null;
        inReplica(() -> jdbc.update(
                """
                INSERT INTO crewscope.projection_generation (
                    organization_id, projection_name, generation, definition_version,
                    rebuild_job_id, status, fencing_token, version, created_at, updated_at
                ) VALUES (?, ?, ?, 1, ?, ?, ?, 0, ?, ?)
                """,
                organizationId.value(), InboxEventProjector.PROJECTION_NAME.value(),
                generation.value(), rebuildJobId, status, fencingToken,
                NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC)));
        if (pointer) {
            inReplica(() -> jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, ?, 0, ?)
                    """,
                    organizationId.value(), InboxEventProjector.PROJECTION_NAME.value(),
                    generation.value(), NOW.atOffset(ZoneOffset.UTC)));
        }
    }

    private void seedTemplate(long version) {
        if (templateId == null) {
            templateId = UUID.randomUUID();
        }
        jdbc.update(
                """
                INSERT INTO crewscope.notification_template (
                    template_id, template_version, server_template_key, status
                ) VALUES (?, ?, 'review-required', 'PUBLISHED')
                """,
                templateId, version);
        jdbc.update(
                """
                INSERT INTO crewscope.notification_template_variable (
                    template_id, template_version, variable_name,
                    variable_type, maximum_length, trusted_origins
                ) VALUES (?, ?, 'itemType', 'TEXT', 40, '[]'::JSONB),
                         (?, ?, 'inboxUrl', 'TRUSTED_LINK', 500,
                          '["https://crewscope.example"]'::JSONB)
                """,
                templateId, version, templateId, version);
    }

    private void seedPreference(boolean enabled, Instant mutedUntil, long version) {
        jdbc.update(
                """
                INSERT INTO crewscope.notification_preference (
                    organization_id, team_id, member_id, enabled, enabled_item_types,
                    muted_until, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, '["REVIEW"]'::JSONB, ?, ?, ?, ?, ?, ?)
                """,
                organizationId.value(), teamId, memberId, enabled,
                mutedUntil.atOffset(ZoneOffset.UTC), version,
                NOW.atOffset(ZoneOffset.UTC), principalId,
                NOW.atOffset(ZoneOffset.UTC), principalId);
    }

    private Graph seedLarkGraph(boolean withMapping) {
        UUID connectionId = UUID.randomUUID();
        Graph value = new Graph(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                connectionId, UUID.randomUUID(), UUID.randomUUID(),
                LarkExternalTenantId.derive(
                                organizationId, new ConnectionId(connectionId))
                        .value());
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.connection (
                        id, organization_id, owner_type, owner_id, connector_key,
                        external_account_reference, credential_id, status, version,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, 'ORGANIZATION', ?, 'lark', 'tenant_alpha', ?,
                              'ACTIVE', 0, ?, ?)
                    """,
                    value.connectionId(), organizationId.value(), organizationId.value(),
                    value.credentialId(), principalId, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.connection_grant (
                        id, organization_id, connection_id,
                        connection_owner_type, connection_owner_id,
                        grantee_type, grantee_id, grantee_team_id,
                        granted_capabilities, resource_unrestricted, granted_resources,
                        valid_from, status, version,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, 'ORGANIZATION', ?, 'TEAM', ?, ?,
                              ?::JSONB, TRUE, '[]'::JSONB, ?, 'ACTIVE', 0, ?, ?)
                    """,
                    value.grantId(), organizationId.value(), value.connectionId(),
                    organizationId.value(), teamId, teamId, capabilityJson(),
                    NOW.minusSeconds(60).atOffset(ZoneOffset.UTC), principalId, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.provider_binding (
                        id, organization_id, team_id, workspace_id, target_type,
                        owner_type, owner_id, owner_team_id,
                        provider_definition_id, provider_definition_version, provider_type,
                        provider_implementation_id, provider_implementation_version,
                        connection_requirement, connection_id, connection_version,
                        connection_grant_id, connection_grant_version, execution_identity,
                        effective_capabilities, resource_unrestricted, effective_resources,
                        default_usage, status, version,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, 'WORKSPACE', 'TEAM', ?, ?, ?, 0, 'COLLABORATION',
                              ?, 0, 'REQUIRED', ?, 0, ?, 0, 'TEAM_SERVICE_ACCOUNT',
                              ?::JSONB, TRUE, '[]'::JSONB, TRUE, 'ACTIVE', 0, ?, ?)
                    """,
                    value.bindingId(), organizationId.value(), teamId, workspaceId, teamId, teamId,
                    value.definitionId(), value.implementationId(), value.connectionId(),
                    value.grantId(), capabilityJson(), principalId, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.lark_external_tenant (
                        id, organization_id, connection_id, connection_version,
                        connection_grant_id, connection_grant_version,
                        tenant_key, provider_version, status, verified_at,
                        version, created_at, updated_at
                    ) VALUES (?, ?, ?, 0, ?, 0, 'tenant_alpha', 'pv-1', 'VERIFIED',
                              ?, 0, ?, ?)
                    """,
                    value.tenantId(), organizationId.value(), value.connectionId(), value.grantId(),
                    NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC),
                    NOW.atOffset(ZoneOffset.UTC));
        });
        if (withMapping) {
            seedMapping(value);
        }
        return value;
    }

    private UUID seedMapping(Graph value) {
        UUID mappingId = UUID.randomUUID();
        inReplica(() -> jdbc.update(
                """
                INSERT INTO crewscope.lark_member_mapping (
                    id, organization_id, team_id, member_id,
                    provider_binding_id, provider_binding_version,
                    connection_id, connection_version,
                    connection_grant_id, connection_grant_version,
                    external_tenant_id, external_tenant_version,
                    tenant_key, open_id, union_id, provider_version,
                    verification_source, verified_at, verified_by_principal_id,
                    status, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 0, ?, 0, ?, 0, ?, 0,
                          'tenant_alpha', 'ou_notify_member', 'on_notify_member', 'pv-1',
                          'LARK_OPEN_API_EXACT_OPEN_ID', ?, ?, 'ACTIVE', 0, ?, ?, ?, ?)
                """,
                mappingId, organizationId.value(), teamId, memberId, value.bindingId(),
                value.connectionId(), value.grantId(), value.tenantId(),
                NOW.atOffset(ZoneOffset.UTC), principalId, NOW.atOffset(ZoneOffset.UTC),
                principalId, NOW.atOffset(ZoneOffset.UTC), principalId));
        return mappingId;
    }

    private void seedReviewInbox(ProjectionGeneration generation, UUID reviewId) {
        InboxSourceKey key = new InboxSourceKey(
                organizationId, new TeamMemberId(memberId), InboxItemType.REVIEW,
                InboxSourceType.REVIEW_REQUEST, reviewId, InboxSourceRevision.INITIAL);
        InboxItem item = InboxItem.project(
                new TeamId(teamId), InboxEventProjector.PROJECTION_NAME, generation,
                SchemaVersion.V1,
                InboxSource.open(
                        key, InboxPriority.HIGH, Optional.empty(), UtcTimestamp.from(NOW)));
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, opened_at,
                    source_status, close_reason, closed_at
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 'REVIEW', 'REVIEW_REQUEST', ?, 0,
                          'HIGH', ?, 'OPEN', NULL, NULL)
                """,
                organizationId.value(), teamId, memberId,
                InboxEventProjector.PROJECTION_NAME.value(), generation.value(),
                item.id().value(), reviewId, NOW.atOffset(ZoneOffset.UTC));
    }

    private void switchPointerToGeneration2() {
        inReplica(() -> {
            jdbc.update(
                    """
                    UPDATE crewscope.projection_generation
                    SET status = 'RETIRED', version = version + 1, updated_at = ?
                    WHERE organization_id = ? AND projection_name = ? AND generation = 1
                    """,
                    NOW.plusSeconds(20).atOffset(ZoneOffset.UTC), organizationId.value(),
                    InboxEventProjector.PROJECTION_NAME.value());
            jdbc.update(
                    """
                    UPDATE crewscope.projection_generation
                    SET status = 'ACTIVE', rebuild_job_id = NULL,
                        version = version + 1, updated_at = ?
                    WHERE organization_id = ? AND projection_name = ? AND generation = 2
                    """,
                    NOW.plusSeconds(20).atOffset(ZoneOffset.UTC), organizationId.value(),
                    InboxEventProjector.PROJECTION_NAME.value());
            jdbc.update(
                    """
                    UPDATE crewscope.projection_pointer
                    SET active_generation = 2, version = version + 1, updated_at = ?
                    WHERE organization_id = ? AND projection_name = ?
                    """,
                    NOW.plusSeconds(20).atOffset(ZoneOffset.UTC), organizationId.value(),
                    InboxEventProjector.PROJECTION_NAME.value());
        });
    }

    private void seedSuccessfulRedelivery(UUID originalDeliveryId) {
        UUID originalAction = jdbc.queryForObject(
                "SELECT action_id FROM crewscope.notification_delivery WHERE delivery_id = ?",
                UUID.class, originalDeliveryId);
        UUID replacementAction = UUID.randomUUID();
        UUID replacementDelivery = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        String dedup = "d".repeat(64);
        String digest = "e".repeat(64);
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_planned_action (
                        organization_id, team_id, recipient_member_id, projection_name, generation,
                        action_id, intent_id, source_identity_hash, template_id, template_version,
                        variable_hash, recipient_mapping_id, recipient_mapping_version,
                        provider_binding_id, provider_binding_version, connection_id,
                        connection_version, connection_grant_id, connection_grant_version,
                        team_policy_id, team_policy_version, preference_version,
                        deduplication_key, authorization_digest, not_before, valid_until,
                        status, redelivery_of, action_digest, version, created_at, updated_at
                    ) SELECT organization_id, team_id, recipient_member_id, projection_name, generation,
                        ?, intent_id, source_identity_hash, template_id, template_version,
                        variable_hash, recipient_mapping_id, recipient_mapping_version,
                        provider_binding_id, provider_binding_version, connection_id,
                        connection_version, connection_grant_id, connection_grant_version,
                        team_policy_id, team_policy_version, preference_version,
                        ?, ?, not_before, valid_until, 'PLANNED', ?, ?, 0, ?, ?
                    FROM crewscope.notification_planned_action WHERE action_id = ?
                    """,
                    replacementAction, dedup, "f".repeat(64), originalDeliveryId, digest,
                    NOW.plusSeconds(40).atOffset(ZoneOffset.UTC),
                    NOW.plusSeconds(40).atOffset(ZoneOffset.UTC), originalAction);
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_delivery (
                        organization_id, delivery_id, action_id, action_digest, deduplication_key,
                        redelivery_of, status, attempt_count, receipt_id, version,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED', 1, ?, 1, ?, ?)
                    """,
                    organizationId.value(), replacementDelivery, replacementAction, digest, dedup,
                    originalDeliveryId, receiptId, NOW.plusSeconds(40).atOffset(ZoneOffset.UTC),
                    NOW.plusSeconds(40).atOffset(ZoneOffset.UTC));
        });
    }

    private ProjectionGenerationLease lease(
            ProjectionGeneration generation, long fencingToken) {
        return new ProjectionGenerationLease(
                new ProjectionGenerationKey(
                        organizationId, InboxEventProjector.PROJECTION_NAME, generation),
                new ProjectionFencingToken(fencingToken));
    }

    private void reconcile(ProjectionGenerationLease lease) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                projector.reconcileTeam(
                        lease, new TeamId(teamId), UtcTimestamp.from(NOW.plusSeconds(10))));
    }

    private void inReplica(Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            operation.run();
        });
    }

    private String capabilityJson() {
        return "[\"" + CAPABILITY + "\"]";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    private String text(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private record Graph(
            UUID credentialId,
            UUID definitionId,
            UUID implementationId,
            UUID connectionId,
            UUID grantId,
            UUID bindingId,
            UUID tenantId) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
