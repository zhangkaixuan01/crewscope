package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V2 identity, authorization, audit, projection and credential database contract. */
class V2IdentityPlatformMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String APPLICATION_SCHEMA = "crewscope";
    private static final String ALTERNATE_SCHEMA = "migration_probe";
    private static final MigrationVersion VERSION_1 = MigrationVersion.fromVersion("1");
    private static final MigrationVersion VERSION_2 = MigrationVersion.fromVersion("2");
    private static final Set<String> V2_TABLES =
            Set.of(
                    "principal",
                    "team_member",
                    "team_role",
                    "team_member_role",
                    "audit_event",
                    "event_projection_checkpoint",
                    "credential_secret");
    private static final Set<String> BUSINESS_AUDIT_TABLES =
            Set.of(
                    "organization",
                    "team",
                    "workspace",
                    "work_project",
                    "work_item",
                    "principal",
                    "team_member",
                    "team_role",
                    "team_member_role",
                    "credential_secret");
    private static final Set<String> REQUIRED_V2_INDEXES =
            Set.of(
                    "ux_principal_external_identity",
                    "ix_principal_owner",
                    "ix_team_member_team_status",
                    "ix_team_member_user",
                    "ix_team_role_team_status",
                    "ux_team_member_role_active_scope",
                    "ix_team_member_role_member_status",
                    "ix_projection_checkpoint_updated",
                    "ux_audit_event_domain_event",
                    "ix_audit_event_organization_time",
                    "ix_audit_event_team_time",
                    "ix_audit_event_actor_time",
                    "ix_audit_event_subject_time",
                    "ix_audit_event_correlation",
                    "ix_credential_secret_subject_status",
                    "ix_credential_secret_provider_connection",
                    "ix_work_item_team_updated",
                    "ix_work_item_team_status",
                    "ix_work_item_project_status_updated",
                    "ux_domain_event_idempotency");
    private static final Set<String> REQUIRED_V2_FOREIGN_KEYS =
            Set.of(
                    "fk_principal_organization",
                    "fk_principal_team",
                    "fk_principal_owner",
                    "fk_team_member_team",
                    "fk_team_member_user",
                    "fk_team_member_inviter",
                    "fk_team_role_team",
                    "fk_team_member_role_member",
                    "fk_team_member_role_role",
                    "fk_team_member_role_project",
                    "fk_team_member_role_grantor",
                    "fk_projection_checkpoint_organization",
                    "fk_projection_checkpoint_event",
                    "fk_audit_event_organization",
                    "fk_audit_event_team",
                    "fk_audit_event_workspace",
                    "fk_audit_event_principal",
                    "fk_audit_event_initiator",
                    "fk_audit_event_actor",
                    "fk_audit_event_agent",
                    "fk_audit_event_domain_event",
                    "fk_credential_secret_organization",
                    "fk_credential_secret_team",
                    "fk_credential_secret_principal",
                    "fk_workspace_team_scope",
                    "fk_work_project_team_scope",
                    "fk_work_project_workspace_scope",
                    "fk_work_item_team_scope",
                    "fk_work_item_workspace_scope",
                    "fk_work_item_project_scope",
                    "fk_domain_event_team_scope",
                    "fk_domain_event_workspace_scope",
                    "fk_workspace_owner_principal",
                    "fk_domain_event_actor_principal",
                    "fk_organization_created_by_principal",
                    "fk_organization_updated_by_principal",
                    "fk_team_created_by_principal",
                    "fk_team_updated_by_principal",
                    "fk_workspace_created_by_principal",
                    "fk_workspace_updated_by_principal",
                    "fk_work_project_created_by_principal",
                    "fk_work_project_updated_by_principal",
                    "fk_work_item_created_by_principal",
                    "fk_work_item_updated_by_principal",
                    "fk_principal_created_by_principal",
                    "fk_principal_updated_by_principal",
                    "fk_team_member_created_by_principal",
                    "fk_team_member_updated_by_principal",
                    "fk_team_role_created_by_principal",
                    "fk_team_role_updated_by_principal",
                    "fk_team_member_role_created_by_principal",
                    "fk_team_member_role_updated_by_principal",
                    "fk_credential_secret_created_by_principal",
                    "fk_credential_secret_updated_by_principal");

    @BeforeEach
    void resetSchemas() throws SQLException {
        // Schema reset keeps all cases independent without restarting the class-level container.
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS migration_probe CASCADE");
            statement.execute("CREATE SCHEMA migration_probe");
        }
    }

    @Test
    void createsV2ObjectsOnlyInCrewscopeSchema() throws SQLException {
        Flyway flyway = versionTwoFlyway();
        MigrateResult result = flyway.migrate();

        assertTrue(result.success);
        assertEquals("2", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.info().pending().length);
        flyway.validate();

        for (String table : V2_TABLES) {
            assertEquals(1, tableCount(APPLICATION_SCHEMA, table), table);
            assertEquals(0, tableCount(ALTERNATE_SCHEMA, table), table);
            assertEquals(0, tableCount("public", table), table);
        }

        Set<String> credentialColumns = columnsOf("credential_secret");
        assertTrue(
                credentialColumns.containsAll(
                        Set.of(
                                "ciphertext",
                                "nonce",
                                "authentication_tag",
                                "key_id",
                                "algorithm",
                                "aad_version")));
        assertFalse(credentialColumns.contains("plaintext"));
        assertFalse(credentialColumns.contains("secret_value"));
        assertFalse(credentialColumns.contains("token"));
        assertTrue(columnsOf("outbox_event").containsAll(Set.of("version", "updated_at")));

        for (String table : BUSINESS_AUDIT_TABLES) {
            Set<String> columns = columnsOf(table);
            assertTrue(
                    columns.containsAll(
                            Set.of(
                                    "created_at",
                                    "created_by_principal_id",
                                    "updated_at",
                                    "updated_by_principal_id",
                                    "version")),
                    table);
            assertFalse(columns.contains("is_deleted"), table);
            assertFalse(columns.contains("deleted_at"), table);
            assertFalse(columns.contains("delete_reason"), table);
        }
        for (String technicalTable :
                Set.of("domain_event", "outbox_event", "audit_event", "event_projection_checkpoint")) {
            Set<String> columns = columnsOf(technicalTable);
            assertFalse(columns.contains("created_by_principal_id"), technicalTable);
            assertFalse(columns.contains("updated_by_principal_id"), technicalTable);
        }
    }

    @Test
    void createsValidatedForeignKeysAndRequiredIndexes() throws SQLException {
        versionTwoFlyway().migrate();

        Set<String> indexes =
                queryStrings(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = ?",
                        APPLICATION_SCHEMA);
        assertTrue(indexes.containsAll(REQUIRED_V2_INDEXES));
        assertTrue(indexDefinition("ix_work_item_team_updated")
                .contains("(organization_id, team_id, updated_at DESC, id DESC)"));
        assertTrue(indexDefinition("ix_work_item_team_status")
                .contains("(organization_id, team_id, status, updated_at DESC, id DESC)"));
        assertTrue(indexDefinition("ix_work_item_project_status_updated")
                .contains(
                        "(organization_id, team_id, project_id, status, updated_at DESC, id DESC)"));

        Set<String> foreignKeys =
                queryStrings(
                        """
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE constraint_schema = ? AND constraint_type = 'FOREIGN KEY'
                        """,
                        APPLICATION_SCHEMA);
        assertTrue(foreignKeys.containsAll(REQUIRED_V2_FOREIGN_KEYS));

        Set<String> unvalidatedForeignKeys =
                queryStrings(
                        """
                        SELECT c.conname
                        FROM pg_constraint c
                        JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = ? AND c.contype = 'f' AND NOT c.convalidated
                        """,
                        APPLICATION_SCHEMA);
        assertTrue(
                unvalidatedForeignKeys.stream().noneMatch(REQUIRED_V2_FOREIGN_KEYS::contains));

        Set<String> nonRestrictingForeignKeys =
                queryStrings(
                        """
                        SELECT c.conname
                        FROM pg_constraint c
                        JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = ? AND c.contype = 'f' AND c.confdeltype <> 'r'
                        """,
                        APPLICATION_SCHEMA);
        assertTrue(
                nonRestrictingForeignKeys.stream().noneMatch(REQUIRED_V2_FOREIGN_KEYS::contains));
    }

    @Test
    void bootstrapsOrganizationAndItsFirstPrincipalInOneTransaction() throws SQLException {
        versionTwoFlyway().migrate();
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();

        // Deferred Organization actor FKs break the intentional Organization/Principal bootstrap
        // cycle while still requiring both rows to be consistent when the transaction commits.
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement organization = connection.prepareStatement(
                            """
                            INSERT INTO crewscope.organization (
                                id, name, status,
                                created_by_principal_id, updated_by_principal_id
                            ) VALUES (?, 'Bootstrapped Org', 'ACTIVE', ?, ?)
                            """);
                    PreparedStatement principal = connection.prepareStatement(
                            """
                            INSERT INTO crewscope.principal (
                                id, organization_id, principal_type, display_name, status,
                                created_by_principal_id, updated_by_principal_id
                            ) VALUES (?, ?, 'USER', 'First User', 'ACTIVE', ?, ?)
                            """)) {
                bind(organization, organizationId, principalId, principalId);
                organization.executeUpdate();
                bind(principal, principalId, organizationId, principalId, principalId);
                principal.executeUpdate();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }

        assertEquals(
                principalId.toString(),
                queryString(
                        "SELECT created_by_principal_id::TEXT FROM crewscope.organization WHERE id = ?",
                        organizationId));
    }

    @Test
    void enforcesTenantIdentityRoleAuditAndCredentialInvariants() throws SQLException {
        versionTwoFlyway().migrate();
        BaselineIds ids = seedIdentityGraph();

        execute(
                """
                UPDATE crewscope.team
                SET created_by_principal_id = ?, updated_by_principal_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                ids.userOne(),
                ids.userOne(),
                ids.teamOne());
        assertEquals(
                ids.userOne().toString(),
                queryString(
                        "SELECT updated_by_principal_id::TEXT FROM crewscope.team WHERE id = ?",
                        ids.teamOne()));
        assertSqlState(
                "23503",
                "UPDATE crewscope.team SET updated_by_principal_id = ? WHERE id = ?",
                ids.userTwo(),
                ids.teamOne());

        assertSqlState(
                "23514",
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name,
                    identity_provider, status
                ) VALUES (?, ?, 'USER', 'Broken identity', 'oidc', 'ACTIVE')
                """,
                UUID.randomUUID(),
                ids.organizationOne());

        assertSqlState(
                "23503",
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'OIDC', CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.userTwo());

        assertSqlState(
                "23505",
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'OIDC', CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.userOne());

        assertSqlState(
                "23514",
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name,
                    permissions, status
                ) VALUES (?, ?, ?, 'BROKEN', 'Broken', CAST('{}' AS JSONB), 'ACTIVE')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne());

        execute(
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id,
                    scope_type, granted_by_principal_id, status
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                ids.memberRole(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.memberOne(),
                ids.roleOne(),
                ids.userOne());

        assertSqlState(
                "23505",
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id,
                    scope_type, granted_by_principal_id, status
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.memberOne(),
                ids.roleOne(),
                ids.userOne());

        assertSqlState(
                "23514",
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id,
                    scope_type, scope_id, granted_by_principal_id, status
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', ?, ?, 'ACTIVE')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.memberOne(),
                ids.roleOne(),
                UUID.randomUUID(),
                ids.userOne());

        assertSqlState(
                "23503",
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, workspace_type, owner_principal_id, name, status
                ) VALUES (?, ?, 'PERSONAL', ?, 'Cross tenant', 'ACTIVE')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.userTwo());

        UUID workspaceOne = UUID.randomUUID();
        UUID workspaceTwo = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace One', 'ACTIVE')
                """,
                workspaceOne,
                ids.organizationOne(),
                ids.teamOne());
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace Two', 'ACTIVE')
                """,
                workspaceTwo,
                ids.organizationTwo(),
                ids.teamTwo());

        // A globally valid Team ID cannot be combined with another Organization.
        assertSqlState(
                "23503",
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Mixed Workspace', 'ACTIVE')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamTwo());

        UUID projectOne = UUID.randomUUID();
        UUID projectTwo = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, 'ONE', 'Project One')
                """,
                projectOne,
                ids.organizationOne(),
                ids.teamOne(),
                workspaceOne);
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, 'TWO', 'Project Two')
                """,
                projectTwo,
                ids.organizationTwo(),
                ids.teamTwo(),
                workspaceTwo);

        // Project and WorkItem relationships must preserve their complete declared Scope.
        assertSqlState(
                "23503",
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, 'MIX', 'Mixed Project')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne(),
                workspaceTwo);
        assertSqlState(
                "23503",
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) VALUES (?, ?, ?, ?, ?, 'ONE-1', 'TASK', 'Mixed WorkItem',
                    'BACKLOG', 'MEDIUM')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne(),
                workspaceOne,
                projectTwo);

        // Event scope follows the same Organization/Team/Workspace consistency rule.
        assertSqlState(
                "23503",
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id, workspace_id,
                    subject_type, subject_id, actor_type, correlation_id, occurred_at, payload
                ) VALUES (?, 'MIXED_SCOPE', '1', ?, ?, ?, 'WORK_ITEM', ?, 'SERVICE', ?,
                    CURRENT_TIMESTAMP, CAST('{}' AS JSONB))
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.teamOne(),
                workspaceTwo,
                UUID.randomUUID(),
                UUID.randomUUID());

        execute(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    subject_type, subject_id, actor_type, actor_id, correlation_id,
                    occurred_at, payload
                ) VALUES (?, 'TEAM_MEMBER_CREATED', '1', ?, ?, 'TEAM_MEMBER', ?,
                    'USER', ?, ?, ?, CAST('{}' AS JSONB))
                """,
                ids.domainEvent(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.memberOne(),
                ids.userOne(),
                ids.correlation(),
                OffsetDateTime.now(ZoneOffset.UTC));

        execute(
                """
                INSERT INTO crewscope.event_projection_checkpoint (
                    organization_id, projection_name, partition_key, last_event_id,
                    last_event_cursor, last_event_occurred_at
                ) VALUES (?, 'audit', 'team-1', ?, 'cursor-1', ?)
                """,
                ids.organizationOne(),
                ids.domainEvent(),
                OffsetDateTime.now(ZoneOffset.UTC));

        execute(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, principal_id, initiator_id,
                    actor_type, actor_id, event_type, subject_type, subject_id,
                    outcome, domain_event_id, correlation_id, schema_version,
                    occurred_at, payload
                ) VALUES (?, ?, ?, ?, ?, 'USER', ?, 'TEAM_MEMBER_CREATED',
                    'TEAM_MEMBER', ?, 'SUCCEEDED', ?, ?, '1', ?, CAST('{}' AS JSONB))
                """,
                ids.auditEvent(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.userOne(),
                ids.userOne(),
                ids.userOne(),
                ids.memberOne(),
                ids.domainEvent(),
                ids.correlation(),
                OffsetDateTime.now(ZoneOffset.UTC));

        execute(
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, principal_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type, ciphertext, nonce,
                    authentication_tag, key_id, algorithm, aad_version, status
                ) VALUES (?, ?, ?, 'PRINCIPAL', ?, 'github-main', 'github', 'OAUTH_TOKEN',
                    DECODE(REPEAT('ab', 32), 'hex'), DECODE(REPEAT('01', 12), 'hex'),
                    DECODE(REPEAT('02', 16), 'hex'), 'dev-key-1', 'AES-256-GCM', '1', 'ACTIVE')
                """,
                ids.credential(),
                ids.organizationOne(),
                ids.userOne(),
                ids.userOne());

        assertSqlState(
                "23514",
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, principal_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type, ciphertext, nonce,
                    authentication_tag, key_id, algorithm, aad_version, status
                ) VALUES (?, ?, ?, 'PRINCIPAL', ?, 'bad-nonce', 'github', 'OAUTH_TOKEN',
                    DECODE('ab', 'hex'), DECODE('01', 'hex'),
                    DECODE(REPEAT('02', 16), 'hex'), 'dev-key-1', 'AES-256-GCM', '1', 'ACTIVE')
                """,
                UUID.randomUUID(),
                ids.organizationOne(),
                ids.userOne(),
                ids.userOne());

        assertEquals(1, queryInt("SELECT COUNT(*) FROM crewscope.audit_event"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM crewscope.credential_secret"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM crewscope.event_projection_checkpoint"));
    }

    @Test
    void upgradesSeededVersionOneDatabaseWithoutChangingBusinessRows() throws SQLException {
        Flyway versionOne = flyway(VERSION_1);
        versionOne.migrate();
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID domainEventId = UUID.randomUUID();
        UUID legacyPrincipalId = UUID.randomUUID();

        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'CrewScope', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Platform', 'ACTIVE')
                """,
                teamId,
                organizationId);
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, owner_principal_id, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'Platform Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId,
                legacyPrincipalId);
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, 'CRW', 'CrewScope')
                """,
                projectId,
                organizationId,
                teamId,
                workspaceId);
        execute(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) VALUES (?, ?, ?, ?, ?, 'CRW-1', 'TASK', 'Keep this row', 'BACKLOG', 'MEDIUM')
                """,
                workItemId,
                organizationId,
                teamId,
                workspaceId,
                projectId);
        execute(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    workspace_id, subject_type, subject_id, actor_type, actor_id, correlation_id,
                    occurred_at, payload
                ) VALUES (?, 'WORK_ITEM_CREATED', '1', ?, ?, ?, 'WORK_ITEM', ?,
                    'USER', ?, ?, CURRENT_TIMESTAMP, CAST('{}' AS JSONB))
                """,
                domainEventId,
                organizationId,
                teamId,
                workspaceId,
                workItemId,
                legacyPrincipalId,
                UUID.randomUUID());
        execute(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key, delivery_status
                ) VALUES (?, ?, 'work-item', 'CRW-1', 'PENDING')
                """,
                UUID.randomUUID(),
                domainEventId);

        Flyway latest = versionTwoFlyway();
        assertEquals(1, latest.info().pending().length);
        MigrateResult result = latest.migrate();

        assertTrue(result.success);
        assertEquals(1, result.migrationsExecuted);
        assertEquals("2", latest.info().current().getVersion().getVersion());
        assertEquals(
                "Keep this row",
                queryString("SELECT title FROM crewscope.work_item WHERE id = ?", workItemId));
        assertEquals(0, queryInt("SELECT version FROM crewscope.outbox_event"));
        assertNotNull(queryString("SELECT updated_at::TEXT FROM crewscope.outbox_event"));
        assertEquals(
                "USER:DISABLED",
                queryString(
                        """
                        SELECT principal_type || ':' || status
                        FROM crewscope.principal
                        WHERE id = ? AND organization_id = ?
                        """,
                        legacyPrincipalId,
                        organizationId));
        assertEquals(
                legacyPrincipalId.toString(),
                queryString(
                        "SELECT owner_principal_id::TEXT FROM crewscope.workspace WHERE id = ?",
                        workspaceId));
        for (String table : V2_TABLES) {
            int expectedRows = table.equals("principal") ? 1 : 0;
            assertEquals(expectedRows, queryInt("SELECT COUNT(*) FROM crewscope." + table), table);
        }
    }

    private static BaselineIds seedIdentityGraph() throws SQLException {
        BaselineIds ids = BaselineIds.create();
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org One', 'ACTIVE')",
                ids.organizationOne());
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org Two', 'ACTIVE')",
                ids.organizationTwo());
        execute(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Team One', 'ACTIVE')
                """,
                ids.teamOne(),
                ids.organizationOne());
        execute(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Team Two', 'ACTIVE')
                """,
                ids.teamTwo(),
                ids.organizationTwo());
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name,
                    identity_provider, external_subject, status
                ) VALUES (?, ?, 'USER', 'User One', 'oidc', 'user-one', 'ACTIVE')
                """,
                ids.userOne(),
                ids.organizationOne());
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name,
                    identity_provider, external_subject, status
                ) VALUES (?, ?, 'USER', 'User Two', 'oidc', 'user-two', 'ACTIVE')
                """,
                ids.userTwo(),
                ids.organizationTwo());
        execute(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'OIDC', CURRENT_TIMESTAMP)
                """,
                ids.memberOne(),
                ids.organizationOne(),
                ids.teamOne(),
                ids.userOne());
        execute(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name,
                    built_in, permissions, status
                ) VALUES (?, ?, ?, 'MEMBER', 'Member', TRUE,
                    CAST('["workitem:read"]' AS JSONB), 'ACTIVE')
                """,
                ids.roleOne(),
                ids.organizationOne(),
                ids.teamOne());
        return ids;
    }

    private static Flyway versionTwoFlyway() {
        return flyway(VERSION_2);
    }

    private static Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .schemas(APPLICATION_SCHEMA)
                        .defaultSchema(APPLICATION_SCHEMA)
                        .createSchemas(true)
                        .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private static int queryInt(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                int result = resultSet.getInt(1);
                assertFalse(resultSet.next());
                return result;
            }
        }
    }

    private static String indexDefinition(String indexName) throws SQLException {
        return queryString(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                APPLICATION_SCHEMA,
                indexName);
    }

    private static String queryString(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                String result = resultSet.getString(1);
                assertFalse(resultSet.next());
                return result;
            }
        }
    }

    private static Set<String> queryStrings(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> values = new HashSet<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        }
    }

    private static Set<String> columnsOf(String table) throws SQLException {
        return queryStrings(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                """,
                APPLICATION_SCHEMA,
                table);
    }

    private static int tableCount(String schema, String table) throws SQLException {
        return queryInt(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = ? AND table_name = ?
                """,
                schema,
                table);
    }

    private static void bind(PreparedStatement statement, Object... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static void assertSqlState(String sqlState, String sql, Object... parameters) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql, parameters));
        assertEquals(sqlState, exception.getSQLState());
    }

    private record BaselineIds(
            UUID organizationOne,
            UUID organizationTwo,
            UUID teamOne,
            UUID teamTwo,
            UUID userOne,
            UUID userTwo,
            UUID memberOne,
            UUID roleOne,
            UUID memberRole,
            UUID domainEvent,
            UUID auditEvent,
            UUID credential,
            UUID correlation) {

        private static BaselineIds create() {
            return new BaselineIds(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
    }
}
