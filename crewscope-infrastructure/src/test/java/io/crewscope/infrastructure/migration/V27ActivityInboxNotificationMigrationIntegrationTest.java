package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Locks down the V27 generation, Activity, Inbox, notification and Audit persistence contract. */
class V27ActivityInboxNotificationMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_26 = MigrationVersion.fromVersion("26");
    private static final MigrationVersion VERSION_27 = MigrationVersion.fromVersion("27");
    private static final String NOW = "TIMESTAMPTZ '2026-08-25 12:00:00+00'";
    private static final String LATER = "TIMESTAMPTZ '2026-08-25 12:01:00+00'";

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS v27_probe CASCADE");
            statement.execute("CREATE SCHEMA v27_probe");
        }
    }

    @Test
    void migratesEmptyAndNonDefaultSchemaToV27() throws SQLException {
        String url = POSTGRES.getJdbcUrl() + "&currentSchema=v27_probe";
        Flyway target = flyway(url, VERSION_27);

        assertEquals(27, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("27", target.info().current().getVersion().getVersion());
        assertEquals(1, tableCount("crewscope", "projection_generation"));
        assertEquals(1, tableCount("crewscope", "activity_event"));
        assertEquals(1, tableCount("crewscope", "notification_delivery"));
        assertEquals(0, tableCount("v27_probe", "projection_generation"));
    }

    @Test
    void upgradesV26CheckpointIntoActiveGenerationAndPointer() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_26).migrate();
        Scope scope = seedScope();
        UUID eventId = seedDomainEvent(scope, 0);
        execute("""
                INSERT INTO crewscope.event_projection_checkpoint (
                    organization_id, projection_name, partition_key,
                    last_event_id, last_event_cursor, last_event_occurred_at,
                    version, created_at, updated_at
                ) VALUES (
                    '%s', 'audit-event-v1', 'WorkItem:%s', '%s', '0|%s', %s,
                    3, %s, %s
                )
                """.formatted(
                scope.organizationId(), UUID.randomUUID(), eventId, eventId, NOW, NOW, LATER));

        Flyway target = flyway(POSTGRES.getJdbcUrl(), VERSION_27);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();

        assertEquals(1, scalar("""
                SELECT generation FROM crewscope.projection_generation
                WHERE organization_id = '%s' AND projection_name = 'audit-event-v1'
                  AND status = 'ACTIVE' AND fencing_token = 1
                """.formatted(scope.organizationId())));
        assertEquals(1, scalar("""
                SELECT active_generation FROM crewscope.projection_pointer
                WHERE organization_id = '%s' AND projection_name = 'audit-event-v1'
                """.formatted(scope.organizationId())));
        assertEquals(3, scalar("""
                SELECT version FROM crewscope.projection_generation_checkpoint
                WHERE organization_id = '%s' AND projection_name = 'audit-event-v1'
                  AND generation = 1
                """.formatted(scope.organizationId())));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM crewscope.event_projection_checkpoint
                WHERE organization_id = '%s'
                """.formatted(scope.organizationId())));
    }

    @Test
    void enforcesSingleActiveSingleShadowAndCommittedPointerInvariant() throws SQLException {
        migrateLatest();
        Scope scope = seedScope();
        bootstrapProjection(scope, "team-activity");
        UUID jobTwo = insertBuildingGeneration(scope, "team-activity", 2);

        assertSqlState("23505", () -> insertBuildingGeneration(scope, "team-activity", 3));
        assertSqlState("23514", () -> transaction(statement -> statement.executeUpdate("""
                UPDATE crewscope.projection_generation
                SET status = 'RETIRED', fencing_token = 2, version = 1, updated_at = %s
                WHERE organization_id = '%s' AND projection_name = 'team-activity'
                  AND generation = 1
                """.formatted(LATER, scope.organizationId()))));

        UUID validationId = UUID.randomUUID();
        transaction(statement -> {
            statement.executeUpdate(validationInsert(scope, "team-activity", 2, jobTwo, validationId));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_generation
                    SET status = 'VALIDATING', current_validation_id = '%s',
                        fencing_token = 2, version = 1, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'team-activity'
                      AND generation = 2
                    """.formatted(validationId, LATER, scope.organizationId()));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_rebuild_job
                    SET status = 'VALIDATING', current_validation_id = '%s',
                        version = 1, updated_at = %s
                    WHERE id = '%s'
                    """.formatted(validationId, LATER, jobTwo));
        });

        transaction(statement -> {
            statement.executeUpdate("""
                    UPDATE crewscope.projection_generation
                    SET status = 'RETIRED', fencing_token = 2, version = 1, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'team-activity'
                      AND generation = 1
                    """.formatted(LATER, scope.organizationId()));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_generation
                    SET status = 'ACTIVE', fencing_token = 3, version = 2, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'team-activity'
                      AND generation = 2
                    """.formatted(LATER, scope.organizationId()));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_rebuild_job
                    SET status = 'COMPLETED', version = 2, updated_at = %s
                    WHERE id = '%s'
                    """.formatted(LATER, jobTwo));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_pointer
                    SET active_generation = 2, version = 1, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'team-activity'
                    """.formatted(LATER, scope.organizationId()));
        });

        assertEquals(2, scalar("""
                SELECT active_generation FROM crewscope.projection_pointer
                WHERE organization_id = '%s' AND projection_name = 'team-activity'
                """.formatted(scope.organizationId())));
    }

    @Test
    void rejectsStaleLeaseAndCrossTenantProjectionWrites() throws SQLException {
        migrateLatest();
        Scope first = seedScope();
        Scope second = seedScope();
        bootstrapProjection(first, "team-activity");
        UUID eventId = seedDomainEvent(first, 0);

        execute("""
                INSERT INTO crewscope.projection_consumer_receipt (
                    organization_id, projection_name, generation, consumer_name,
                    domain_event_id, fencing_token, processed_at
                ) VALUES ('%s', 'team-activity', 1, 'activity', '%s', 1, %s)
                """.formatted(first.organizationId(), eventId, NOW));

        assertSqlState("23514", () -> execute("""
                INSERT INTO crewscope.projection_generation_checkpoint (
                    organization_id, projection_name, generation, partition_key,
                    fencing_token, version, created_at, updated_at
                ) VALUES ('%s', 'team-activity', 1, 'partition:stale', 2, 0, %s, %s)
                """.formatted(first.organizationId(), NOW, NOW)));

        assertSqlState("23503", () -> execute("""
                INSERT INTO crewscope.activity_event (
                    organization_id, team_id, projection_name, generation,
                    activity_event_id, domain_event_id, projection_schema_version,
                    team_sequence, event_type, category, visibility,
                    subject_type, subject_id, actor_type, occurred_at, payload,
                    payload_schema_name, payload_schema_version
                ) VALUES (
                    '%s', '%s', 'team-activity', 1, '%s', '%s', 1, 1,
                    'WorkItemCreated', 'WORK_ITEM', 'TEAM_MEMBERS', 'TEAM', '%s',
                    'SERVICE', %s, '{}'::jsonb, 'work-item-created', 1
                )
                """.formatted(
                first.organizationId(), second.teamId(), UUID.randomUUID(), eventId,
                second.teamId(), NOW)));
    }

    @Test
    void preservesDispositionWhenRetiredInboxGenerationIsCleaned() throws SQLException {
        migrateLatest();
        Scope scope = seedScope();
        bootstrapProjection(scope, "member-inbox");
        UUID itemId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        insertInboxItem(scope, "member-inbox", 1, itemId, sourceId);
        execute("""
                INSERT INTO crewscope.inbox_disposition (
                    organization_id, team_id, member_id, inbox_item_id,
                    status, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', 'READ', 1, %s, '%s', %s, '%s')
                """.formatted(
                scope.organizationId(), scope.teamId(), scope.memberId(), itemId,
                NOW, scope.principalId(), NOW, scope.principalId()));

        UUID jobId = insertBuildingGeneration(scope, "member-inbox", 2);
        UUID validationId = UUID.randomUUID();
        transaction(statement -> {
            statement.executeUpdate(validationInsert(
                    scope, "member-inbox", 2, jobId, validationId));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_generation
                    SET status = 'VALIDATING', current_validation_id = '%s',
                        fencing_token = 2, version = 1, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'member-inbox'
                      AND generation = 2
                    """.formatted(validationId, LATER, scope.organizationId()));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_rebuild_job
                    SET status = 'VALIDATING', current_validation_id = '%s',
                        version = 1, updated_at = %s WHERE id = '%s'
                    """.formatted(validationId, LATER, jobId));
        });
        insertInboxItem(scope, "member-inbox", 2, itemId, sourceId);
        transaction(statement -> {
            statement.executeUpdate("""
                    UPDATE crewscope.projection_generation
                    SET status = 'RETIRED', fencing_token = 2, version = 1, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'member-inbox'
                      AND generation = 1
                    """.formatted(LATER, scope.organizationId()));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_generation
                    SET status = 'ACTIVE', fencing_token = 3, version = 2, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'member-inbox'
                      AND generation = 2
                    """.formatted(LATER, scope.organizationId()));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_rebuild_job
                    SET status = 'COMPLETED', version = 2, updated_at = %s WHERE id = '%s'
                    """.formatted(LATER, jobId));
            statement.executeUpdate("""
                    UPDATE crewscope.projection_pointer
                    SET active_generation = 2, version = 1, updated_at = %s
                    WHERE organization_id = '%s' AND projection_name = 'member-inbox'
                    """.formatted(LATER, scope.organizationId()));
        });

        execute("""
                DELETE FROM crewscope.inbox_item
                WHERE organization_id = '%s' AND projection_name = 'member-inbox'
                  AND generation = 1
                """.formatted(scope.organizationId()));
        execute("""
                DELETE FROM crewscope.projection_generation
                WHERE organization_id = '%s' AND projection_name = 'member-inbox'
                  AND generation = 1
                """.formatted(scope.organizationId()));

        assertEquals("READ", textScalar("""
                SELECT status FROM crewscope.inbox_disposition
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(scope.organizationId(), itemId)));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM crewscope.inbox_item
                WHERE organization_id = '%s' AND projection_name = 'member-inbox'
                  AND generation = 2 AND inbox_item_id = '%s'
                """.formatted(scope.organizationId(), itemId)));
    }

    @Test
    void enforcesNotificationTemplateIntentAndDeliveryReceiptContracts() throws SQLException {
        migrateLatest();
        Scope scope = seedScope();
        bootstrapProjection(scope, "notification-intent");
        UUID itemId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        insertInboxItem(scope, "notification-intent", 1, itemId, sourceId);
        execute("""
                INSERT INTO crewscope.notification_template (
                    template_id, template_version, server_template_key, status
                ) VALUES ('%s', 1, 'crewscope.inbox.review', 'PUBLISHED')
                """.formatted(templateId));
        assertSqlState("23505", () -> execute("""
                INSERT INTO crewscope.notification_template (
                    template_id, template_version, server_template_key, status
                ) VALUES ('%s', 2, 'crewscope.inbox.review', 'PUBLISHED')
                """.formatted(templateId)));
        execute("""
                INSERT INTO crewscope.notification_intent (
                    organization_id, team_id, recipient_member_id,
                    projection_name, generation, intent_id, projection_schema_version,
                    inbox_item_id, item_type, source_type, source_id, source_revision,
                    template_id, template_version, variables, variable_hash, created_at
                ) VALUES (
                    '%s', '%s', '%s', 'notification-intent', 1, '%s', 1,
                    '%s', 'REVIEW', 'REVIEW_REQUEST', '%s', 0,
                    '%s', 1, '{"title":"Review"}'::jsonb, '%s', %s
                )
                """.formatted(
                scope.organizationId(), scope.teamId(), scope.memberId(), intentId,
                itemId, sourceId, templateId, "a".repeat(64), NOW));

        UUID actionId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        seedNotificationActionWithoutProviderGraph(scope, intentId, templateId, actionId);
        execute("""
                INSERT INTO crewscope.notification_delivery (
                    organization_id, delivery_id, action_id, action_digest,
                    deduplication_key, status, attempt_count, version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 'READY', 0, 0, %s, %s)
                """.formatted(
                scope.organizationId(), deliveryId, actionId, "b".repeat(64),
                "c".repeat(64), NOW, NOW));
        assertSqlState("23505", () -> execute("""
                INSERT INTO crewscope.notification_delivery (
                    organization_id, delivery_id, action_id, action_digest,
                    deduplication_key, status, attempt_count, version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 'READY', 0, 0, %s, %s)
                """.formatted(
                scope.organizationId(), UUID.randomUUID(), actionId, "b".repeat(64),
                "c".repeat(64), NOW, NOW)));

        UUID receiptId = UUID.randomUUID();
        assertSqlState("23514", () -> transaction(statement -> {
            statement.executeUpdate("""
                    INSERT INTO crewscope.notification_receipt (
                        organization_id, receipt_id, delivery_id, action_id,
                        action_digest, deduplication_key, result,
                        provider_receipt_hash, provider_message_hash,
                        evidence_code, received_at
                    ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'ACCEPTED',
                        '%s', '%s', 'LARK_ACCEPTED', %s)
                    """.formatted(
                    scope.organizationId(), receiptId, deliveryId, actionId,
                    "b".repeat(64), "c".repeat(64), "d".repeat(64),
                    "e".repeat(64), LATER));
            statement.executeUpdate("""
                    UPDATE crewscope.notification_delivery
                    SET status = 'FAILED_FINAL', receipt_id = '%s', attempt_count = 1,
                        version = 1, updated_at = %s
                    WHERE organization_id = '%s' AND delivery_id = '%s'
                    """.formatted(receiptId, LATER, scope.organizationId(), deliveryId));
        }));
    }

    @Test
    void addsAuditKeysetIndexesAndMakesAuditHistoryAppendOnly() throws SQLException {
        migrateLatest();
        Scope scope = seedScope();
        UUID eventId = seedDomainEvent(scope, 0);
        execute("""
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, initiator_id,
                    actor_type, actor_id, event_type, subject_type, subject_id,
                    outcome, authorization_context, domain_event_id,
                    correlation_id, schema_version, occurred_at, payload,
                    event_category, retention_level
                ) VALUES (
                    '%s', '%s', '%s', '%s', 'USER', '%s', 'ProjectionRebuilt',
                    'Projection', '%s', 'SUCCEEDED', '{}'::jsonb, '%s', '%s',
                    '1', %s, '{}'::jsonb, 'PROJECTION', 'EXTENDED'
                )
                """.formatted(
                UUID.randomUUID(), scope.organizationId(), scope.teamId(), scope.principalId(),
                scope.principalId(), UUID.randomUUID(), eventId, UUID.randomUUID(), NOW));

        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.audit_event SET retention_level = 'LEGAL_HOLD'
                WHERE organization_id = '%s'
                """.formatted(scope.organizationId())));
        assertTrue(indexExists("ix_audit_event_team_keyset_v27"));
        assertTrue(indexExists("ix_audit_event_team_category_keyset_v27"));
        assertTrue(indexExists("ix_activity_event_team_cursor_v27"));
        assertTrue(indexExists("ix_inbox_item_member_queue_v27"));
    }

    private static void migrateLatest() {
        flyway(POSTGRES.getJdbcUrl(), VERSION_27).migrate();
    }

    private static Scope seedScope() throws SQLException {
        Scope scope = new Scope(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V27 Org', 'ACTIVE')
                """.formatted(scope.organizationId()));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V27 Team', 'ACTIVE')
                """.formatted(scope.teamId(), scope.organizationId()));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V27 User', 'ACTIVE')
                """.formatted(scope.principalId(), scope.organizationId()));
        execute("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 'BOOTSTRAP', %s)
                """.formatted(
                scope.memberId(), scope.organizationId(), scope.teamId(),
                scope.principalId(), NOW));
        return scope;
    }

    private static UUID seedDomainEvent(Scope scope, long version) throws SQLException {
        UUID eventId = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    subject_type, subject_id, actor_type, actor_id, correlation_id,
                    occurred_at, payload, aggregate_version
                ) VALUES (
                    '%s', 'WorkItemChanged', '1', '%s', '%s', 'WorkItem', '%s',
                    'USER', '%s', '%s', %s, '{}'::jsonb, %d
                )
                """.formatted(
                eventId, scope.organizationId(), scope.teamId(), UUID.randomUUID(),
                scope.principalId(), UUID.randomUUID(), NOW, version));
        return eventId;
    }

    private static void bootstrapProjection(Scope scope, String projectionName)
            throws SQLException {
        transaction(statement -> {
            statement.executeUpdate("""
                    INSERT INTO crewscope.projection_definition (
                        projection_name, definition_version, projection_schema_version,
                        canonical_encoder, validator
                    ) VALUES ('%s', 1, 1, 'canonical.v1', 'validator.v1')
                    """.formatted(projectionName));
            statement.executeUpdate("""
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES ('%s', '%s', 1, 1, 'ACTIVE', 1, 0, %s, %s)
                    """.formatted(scope.organizationId(), projectionName, NOW, NOW));
            statement.executeUpdate("""
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES ('%s', '%s', 1, 0, %s)
                    """.formatted(scope.organizationId(), projectionName, NOW));
        });
    }

    private static UUID insertBuildingGeneration(
            Scope scope, String projectionName, long generation) throws SQLException {
        UUID jobId = UUID.randomUUID();
        transaction(statement -> {
            statement.executeUpdate("""
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        rebuild_job_id, status, fencing_token, version, created_at, updated_at
                    ) VALUES ('%s', '%s', %d, 1, '%s', 'BUILDING', 1, 0, %s, %s)
                    """.formatted(
                    scope.organizationId(), projectionName, generation, jobId, NOW, NOW));
            statement.executeUpdate("""
                    INSERT INTO crewscope.projection_rebuild_job (
                        id, organization_id, projection_name, definition_version, generation,
                        requested_by_principal_id, status, version, created_at, updated_at
                    ) VALUES ('%s', '%s', '%s', 1, %d, '%s', 'BUILDING', 0, %s, %s)
                    """.formatted(
                    jobId, scope.organizationId(), projectionName, generation,
                    scope.principalId(), NOW, NOW));
        });
        return jobId;
    }

    private static String validationInsert(
            Scope scope, String projectionName, long generation, UUID jobId, UUID validationId) {
        return """
                INSERT INTO crewscope.projection_validation_result (
                    id, organization_id, projection_name, generation, rebuild_job_id,
                    definition_version, expected_row_count, expected_canonical_hash,
                    expected_gap_count, actual_row_count, actual_canonical_hash,
                    actual_gap_count, passed, validated_by_principal_id, validated_at
                ) VALUES (
                    '%s', '%s', '%s', %d, '%s', 1, 0, '%s', 0, 0, '%s', 0,
                    TRUE, '%s', %s
                )
                """.formatted(
                validationId, scope.organizationId(), projectionName, generation, jobId,
                "0".repeat(64), "0".repeat(64), scope.principalId(), LATER);
    }

    private static void insertInboxItem(
            Scope scope, String projectionName, long generation, UUID itemId, UUID sourceId)
            throws SQLException {
        execute("""
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, opened_at, source_status
                ) VALUES (
                    '%s', '%s', '%s', '%s', %d, '%s', 1, 'REVIEW', 'REVIEW_REQUEST',
                    '%s', 0, 'NORMAL', %s, 'OPEN'
                )
                """.formatted(
                scope.organizationId(), scope.teamId(), scope.memberId(), projectionName,
                generation, itemId, sourceId, NOW));
    }

    private static void seedNotificationActionWithoutProviderGraph(
            Scope scope, UUID intentId, UUID templateId, UUID actionId) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            // This migration test isolates the Delivery/Receipt contract; provider graph behavior
            // remains covered by its adapters and M6-D09 adds the Lark recipient mapping tables.
            statement.execute("SET session_replication_role = replica");
            try {
                statement.executeUpdate("""
                        INSERT INTO crewscope.notification_planned_action (
                            organization_id, team_id, recipient_member_id,
                            projection_name, generation, action_id, intent_id,
                            source_identity_hash, template_id, template_version, variable_hash,
                            recipient_mapping_id, recipient_mapping_version,
                            provider_binding_id, provider_binding_version,
                            connection_id, connection_version,
                            connection_grant_id, connection_grant_version,
                            team_policy_id, team_policy_version, preference_version,
                            deduplication_key, authorization_digest, not_before, valid_until,
                            status, action_digest, version, created_at, updated_at
                        ) VALUES (
                            '%s', '%s', '%s', 'notification-intent', 1, '%s', '%s',
                            '%s', '%s', 1, '%s', '%s', 0, '%s', 0, '%s', 0,
                            '%s', 0, '%s', 0, 0, '%s', '%s', %s,
                            TIMESTAMPTZ '2026-08-25 13:00:00+00', 'PLANNED', '%s', 0, %s, %s
                        )
                        """.formatted(
                        scope.organizationId(), scope.teamId(), scope.memberId(), actionId,
                        intentId, "f".repeat(64), templateId, "a".repeat(64),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64),
                        "d".repeat(64), NOW, "b".repeat(64), NOW, NOW));
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true)
                .target(target)
                .load();
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void transaction(SqlWork work) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                work.execute(statement);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int scalar(String sql) throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            int value = result.getInt(1);
            assertFalse(result.next());
            return value;
        }
    }

    private static String textScalar(String sql) throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            String value = result.getString(1);
            assertFalse(result.next());
            return value;
        }
    }

    private static int tableCount(String schema, String table) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = '%s' AND table_name = '%s'
                """.formatted(schema, table));
    }

    private static boolean indexExists(String indexName) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'crewscope' AND indexname = '%s'
                """.formatted(indexName)) == 1;
    }

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException exception = assertThrows(SQLException.class, action::execute);
        assertEquals(expected, exception.getSQLState());
    }

    @FunctionalInterface
    private interface SqlAction {
        void execute() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork {
        void execute(Statement statement) throws SQLException;
    }

    private record Scope(
            UUID organizationId, UUID teamId, UUID principalId, UUID memberId) {}
}
