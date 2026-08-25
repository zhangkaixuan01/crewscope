package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.collaboration.LarkExternalTenantId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Locks down exact Lark member identity and deterministic Team Observer V28 persistence. */
class V28LarkMappingTeamObserverMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_27 = MigrationVersion.fromVersion("27");
    private static final MigrationVersion VERSION_28 = MigrationVersion.fromVersion("28");
    private static final String NOW = "TIMESTAMPTZ '2026-08-25 12:00:00+00'";
    private static final String LATER = "TIMESTAMPTZ '2026-08-25 12:01:00+00'";

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS v28_probe CASCADE");
            statement.execute("CREATE SCHEMA v28_probe");
        }
    }

    @Test
    void migratesEmptyAndNonDefaultSchemaAndConvergesAtV28() throws SQLException {
        String url = POSTGRES.getJdbcUrl() + "&currentSchema=v28_probe";
        Flyway target = flyway(url, VERSION_28);

        assertEquals(28, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("28", target.info().current().getVersion().getVersion());
        assertEquals(1, tableCount("crewscope", "lark_external_tenant"));
        assertEquals(1, tableCount("crewscope", "lark_member_verification_proof"));
        assertEquals(1, tableCount("crewscope", "lark_member_mapping"));
        assertEquals(0, tableCount("v28_probe", "lark_member_mapping"));
        assertEquals(0, target.migrate().migrationsExecuted);
    }

    @Test
    void upgradesOnlyCompleteTeamsWithJavaExactIdsHashesAndDisabledPair() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_27).migrate();
        Scope ready = seedReadyScope();
        UUID partialTeam = seedPartialTeam(ready.organizationId());
        int modelConnections = scalar("SELECT COUNT(*) FROM crewscope.model_connection");
        int configurations = scalar("SELECT COUNT(*) FROM crewscope.agent_configuration_version");

        Flyway target = flyway(POSTGRES.getJdbcUrl(), VERSION_28);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();

        UUID expectedPrincipal = TeamObserverInitialization
                .stablePrincipalId(new TeamId(ready.teamId())).value();
        UUID expectedProfile = TeamObserverInitialization
                .stableProfileId(new TeamId(ready.teamId())).value();
        var definition = TeamObserverTemplate.create(
                new OrganizationId(ready.organizationId()),
                new PrincipalId(ready.principalId()),
                UtcTimestamp.parse("2026-08-25T12:00:00Z"));

        assertEquals(expectedPrincipal.toString(), textScalar("""
                SELECT id::TEXT FROM crewscope.principal
                WHERE organization_id = '%s' AND team_id = '%s'
                  AND principal_type = 'TEAM_AGENT'
                """.formatted(ready.organizationId(), ready.teamId())));
        assertEquals(expectedProfile.toString(), textScalar("""
                SELECT id::TEXT FROM crewscope.agent_profile
                WHERE organization_id = '%s' AND team_id = '%s'
                  AND template_key = 'team-observer' AND template_version = 1
                """.formatted(ready.organizationId(), ready.teamId())));
        assertEquals(definition.capabilities().capabilityHash().toString(), textScalar("""
                SELECT capability_hash FROM crewscope.agent_template_version
                WHERE organization_id = '%s' AND template_key = 'team-observer'
                """.formatted(ready.organizationId())));
        assertEquals(definition.policy().policyHash().toString(), textScalar("""
                SELECT policy_hash FROM crewscope.agent_template_version
                WHERE organization_id = '%s' AND template_key = 'team-observer'
                """.formatted(ready.organizationId())));
        assertEquals(definition.contentHash().toString(), textScalar("""
                SELECT content_hash FROM crewscope.agent_template_version
                WHERE organization_id = '%s' AND template_key = 'team-observer'
                """.formatted(ready.organizationId())));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM crewscope.agent_profile profile
                JOIN crewscope.principal principal
                  ON principal.organization_id = profile.organization_id
                 AND principal.id = profile.agent_principal_id
                WHERE profile.id = '%s' AND profile.workspace_id = '%s'
                  AND profile.profile_type = 'TEAM' AND NOT profile.default_profile
                  AND profile.ownership_type = 'TEAM'
                  AND profile.ownership_team_id = '%s'
                  AND profile.runtime_role = 'TEAM_COORDINATOR'
                  AND profile.status = 'DISABLED' AND principal.status = 'DISABLED'
                  AND principal.owner_principal_id = '%s'
                """.formatted(
                expectedProfile, ready.workspaceId(), ready.teamId(), ready.principalId())));
        assertEquals(0, scalar("""
                SELECT COUNT(*) FROM crewscope.agent_profile
                WHERE team_id = '%s' AND template_key = 'team-observer'
                """.formatted(partialTeam)));
        assertEquals(modelConnections, scalar("SELECT COUNT(*) FROM crewscope.model_connection"));
        assertEquals(configurations, scalar(
                "SELECT COUNT(*) FROM crewscope.agent_configuration_version"));

        assertSqlState("23505", () -> execute("""
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id,
                    ownership_type, ownership_team_id, runtime_role,
                    template_key, template_version
                ) SELECT '%s', organization_id, team_id, workspace_id, agent_principal_id,
                    'TEAM', FALSE, 'DISABLED', created_by_principal_id, updated_by_principal_id,
                    'TEAM', team_id, 'TEAM_COORDINATOR', 'team-observer', 1
                  FROM crewscope.agent_profile WHERE id = '%s'
                """.formatted(UUID.randomUUID(), expectedProfile)));
        assertSqlState("23514", () -> execute(
                "DELETE FROM crewscope.agent_profile WHERE id = '" + expectedProfile + "'"));
    }

    @Test
    void enforcesLarkScopeIdentityUniquenessAndTerminalHistory() throws SQLException {
        migrateLatest();
        Scope scope = seedReadyScope();
        LarkGraph graph = seedLarkGraph(scope);
        UUID tenantId = LarkExternalTenantId.derive(
                new OrganizationId(scope.organizationId()),
                new ConnectionId(graph.connectionId())).value();
        insertExternalTenant(scope, graph, tenantId);
        UUID proofId = insertVerificationProof(scope, graph, tenantId, "ou_alpha");
        UUID mappingId = insertMapping(scope, graph, tenantId, "ou_alpha");

        UUID secondMember = seedTeamMember(scope);
        assertSqlState("23505", () -> insertMapping(
                scope, graph, tenantId, "ou_second", UUID.randomUUID(), scope.memberId()));
        assertSqlState("23505", () -> insertMapping(
                scope, graph, tenantId, "ou_alpha", UUID.randomUUID(), secondMember));
        Scope second = seedReadyScope();
        LarkGraph secondGraph = seedLarkGraph(second);
        UUID secondTenant = LarkExternalTenantId.derive(
                new OrganizationId(second.organizationId()),
                new ConnectionId(secondGraph.connectionId())).value();
        insertExternalTenant(second, secondGraph, secondTenant);
        assertSqlState("23503", () -> execute("""
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
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', 0, '%s', 0, '%s', 0,
                    '%s', 0, 'tenant_alpha', 'ou_cross', 'on_cross', 'pv-1',
                    'LARK_OPEN_API_EXACT_OPEN_ID', %s, '%s', 'ACTIVE', 0,
                    %s, '%s', %s, '%s'
                )
                """.formatted(
                UUID.randomUUID(), scope.organizationId(), scope.teamId(), UUID.randomUUID(),
                secondGraph.bindingId(), secondGraph.connectionId(), secondGraph.grantId(),
                secondTenant, NOW, scope.principalId(), NOW, scope.principalId(), NOW,
                scope.principalId())));

        execute("""
                UPDATE crewscope.lark_member_mapping
                SET status = 'REVOKED', terminal_reason = 'ADMIN_REVOKED',
                    version = 1, updated_at = %s, updated_by_principal_id = '%s'
                WHERE id = '%s'
                """.formatted(LATER, scope.principalId(), mappingId));
        assertEquals("REVOKED", textScalar(
                "SELECT status FROM crewscope.lark_member_mapping WHERE id = '" + mappingId + "'"));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.lark_member_mapping SET status = 'ACTIVE',
                    terminal_reason = NULL, version = 2, updated_at = %s
                WHERE id = '%s'
                """.formatted("TIMESTAMPTZ '2026-08-25 12:02:00+00'", mappingId)));
        assertSqlState("23514", () -> execute(
                "DELETE FROM crewscope.lark_member_mapping WHERE id = '" + mappingId + "'"));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.lark_member_verification_proof SET open_id = 'ou_changed'
                WHERE id = '%s'
                """.formatted(proofId)));

        // A terminal row releases both partial unique keys while retaining immutable evidence.
        insertMapping(scope, graph, tenantId, "ou_alpha", UUID.randomUUID(), scope.memberId());
        assertFalse(constraintValidated("fk_notification_action_mapping_v28"));
    }

    @Test
    void rollsBackV28WhenDeterministicPrincipalCollides() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_27).migrate();
        Scope scope = seedReadyScope();
        UUID collision = TeamObserverInitialization
                .stablePrincipalId(new TeamId(scope.teamId())).value();
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES ('%s', '%s', 'SERVICE', 'Collision', 'ORGANIZATION', 'ACTIVE')
                """.formatted(collision, scope.organizationId()));

        assertThrows(FlywayException.class,
                () -> flyway(POSTGRES.getJdbcUrl(), VERSION_28).migrate());
        assertEquals(0, tableCount("crewscope", "lark_member_mapping"));
        assertEquals("27", flyway(POSTGRES.getJdbcUrl(), VERSION_28)
                .info().current().getVersion().getVersion());
    }

    @Test
    void rollsBackWhenReservedTemplateConflictsWithoutAnEligibleTeam() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_27).migrate();
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        transaction(statement -> {
            statement.executeUpdate("""
                    INSERT INTO crewscope.organization (id, name, status)
                    VALUES ('%s', 'No Team Org', 'ACTIVE')
                    """.formatted(organizationId));
            statement.executeUpdate("""
                    INSERT INTO crewscope.principal (
                        id, organization_id, principal_type, display_name, status
                    ) VALUES ('%s', '%s', 'USER', 'No Team Owner', 'ACTIVE')
                    """.formatted(principalId, organizationId));
            statement.executeUpdate("""
                    INSERT INTO crewscope.agent_template_version (
                        organization_id, publisher_type, publisher_id,
                        template_key, template_version, runtime_role,
                        allowed_ownership_types, allowed_execution_scopes,
                        declared_capabilities, required_model_capabilities,
                        capability_hash, system_prompt_baseline, allowed_tools,
                        approved_skill_keys, member_configurable_slots,
                        administrator_configurable_slots, policy_hash, content_hash,
                        status, lifecycle_version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (
                        '%s', 'ORGANIZATION', '%s', 'team-observer', 1, 'SPECIALIST',
                        '["TEAM"]'::JSONB, '["TEAM"]'::JSONB,
                        '["custom.summary"]'::JSONB, '[]'::JSONB,
                        '%s', 'Conflicting custom template', '[]'::JSONB,
                        '[]'::JSONB, '[]'::JSONB, '[]'::JSONB, '%s', '%s',
                        'ACTIVE', 0, %s, '%s', %s, '%s'
                    )
                    """.formatted(
                    organizationId, organizationId, "1".repeat(64), "2".repeat(64),
                    "3".repeat(64), NOW, principalId, NOW, principalId));
        });

        assertEquals(0, scalar("SELECT COUNT(*) FROM crewscope.team"));
        assertThrows(FlywayException.class,
                () -> flyway(POSTGRES.getJdbcUrl(), VERSION_28).migrate());
        assertEquals(0, tableCount("crewscope", "lark_member_mapping"));
        assertEquals("27", flyway(POSTGRES.getJdbcUrl(), VERSION_28)
                .info().current().getVersion().getVersion());
    }

    private static void migrateLatest() {
        flyway(POSTGRES.getJdbcUrl(), VERSION_28).migrate();
    }

    private static Scope seedReadyScope() throws SQLException {
        Scope scope = new Scope(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        transaction(statement -> {
            statement.executeUpdate("""
                    INSERT INTO crewscope.organization (id, name, status)
                    VALUES ('%s', 'V28 Org', 'ACTIVE')
                    """.formatted(scope.organizationId()));
            statement.executeUpdate("""
                    INSERT INTO crewscope.team (id, organization_id, name, status)
                    VALUES ('%s', '%s', 'V28 Team', 'ACTIVE')
                    """.formatted(scope.teamId(), scope.organizationId()));
            statement.executeUpdate("""
                    INSERT INTO crewscope.principal (
                        id, organization_id, principal_type, display_name, status
                    ) VALUES ('%s', '%s', 'USER', 'V28 Owner', 'ACTIVE')
                    """.formatted(scope.principalId(), scope.organizationId()));
            statement.executeUpdate("""
                    INSERT INTO crewscope.team_member (
                        id, organization_id, team_id, user_principal_id,
                        status, join_method, joined_at
                    ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 'BOOTSTRAP', %s)
                    """.formatted(
                    scope.memberId(), scope.organizationId(), scope.teamId(),
                    scope.principalId(), NOW));
            statement.executeUpdate("""
                    INSERT INTO crewscope.workspace (
                        id, organization_id, team_id, workspace_type,
                        owner_principal_id, name, status
                    ) VALUES ('%s', '%s', '%s', 'TEAM', '%s', 'Default', 'ACTIVE')
                    """.formatted(
                    scope.workspaceId(), scope.organizationId(), scope.teamId(),
                    scope.principalId()));
            statement.executeUpdate("""
                    UPDATE crewscope.team SET owner_member_id = '%s', default_workspace_id = '%s'
                    WHERE id = '%s'
                    """.formatted(scope.memberId(), scope.workspaceId(), scope.teamId()));
        });
        return scope;
    }

    private static UUID seedPartialTeam(UUID organizationId) throws SQLException {
        UUID teamId = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'Partial Team', 'ACTIVE')
                """.formatted(teamId, organizationId));
        return teamId;
    }

    private static UUID seedTeamMember(Scope scope) throws SQLException {
        UUID principalId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'Second Member', 'ACTIVE')
                """.formatted(principalId, scope.organizationId()));
        execute("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 'INVITATION', %s)
                """.formatted(
                memberId, scope.organizationId(), scope.teamId(), principalId, NOW));
        return memberId;
    }

    private static LarkGraph seedLarkGraph(Scope scope) throws SQLException {
        LarkGraph graph = new LarkGraph(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute("""
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type,
                    ciphertext, nonce, authentication_tag,
                    key_id, algorithm, aad_version, status
                ) VALUES ('%s', '%s', 'ORGANIZATION', '%s', 'lark-token-%s',
                    'lark', 'APP_SECRET', DECODE('01','hex'), DECODE('%s','hex'),
                    DECODE('%s','hex'), 'test-key', 'AES-256-GCM', 'v1', 'ACTIVE')
                """.formatted(
                graph.credentialId(), scope.organizationId(), scope.organizationId(),
                graph.credentialId(), "00".repeat(12), "00".repeat(16)));
        execute("""
                INSERT INTO crewscope.provider_definition (
                    id, organization_id, provider_key, provider_type,
                    interface_version, display_name, capabilities, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', 'lark-collaboration', 'COLLABORATION', 'v1',
                    'Lark', '["member.mapping","notification.delivery"]'::JSONB,
                    'ACTIVE', '%s', '%s')
                """.formatted(
                graph.definitionId(), scope.organizationId(), scope.principalId(),
                scope.principalId()));
        execute("""
                INSERT INTO crewscope.provider_implementation (
                    id, organization_id, provider_definition_id, provider_type,
                    definition_interface_version, implementation_key,
                    implementation_version, capabilities, connection_requirement,
                    connector_key, status, created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', 'COLLABORATION', 'v1', 'lark-openapi', '1',
                    '["member.mapping","notification.delivery"]'::JSONB,
                    'REQUIRED', 'lark', 'ACTIVE', '%s', '%s')
                """.formatted(
                graph.implementationId(), scope.organizationId(), graph.definitionId(),
                scope.principalId(), scope.principalId()));
        execute("""
                INSERT INTO crewscope.connection (
                    id, organization_id, owner_type, owner_id, connector_key,
                    external_account_reference, credential_id, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', 'ORGANIZATION', '%s', 'lark', 'tenant_alpha', '%s',
                    'ACTIVE', '%s', '%s')
                """.formatted(
                graph.connectionId(), scope.organizationId(), scope.organizationId(),
                graph.credentialId(), scope.principalId(), scope.principalId()));
        execute("""
                INSERT INTO crewscope.connection_grant (
                    id, organization_id, connection_id,
                    connection_owner_type, connection_owner_id,
                    grantee_type, grantee_id, grantee_team_id,
                    granted_capabilities, resource_unrestricted, granted_resources,
                    valid_from, status, created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', 'ORGANIZATION', '%s', 'TEAM', '%s', '%s',
                    '["member.mapping","notification.delivery"]'::JSONB,
                    TRUE, '[]'::JSONB, %s, 'ACTIVE', '%s', '%s')
                """.formatted(
                graph.grantId(), scope.organizationId(), graph.connectionId(),
                scope.organizationId(), scope.teamId(), scope.teamId(), NOW,
                scope.principalId(), scope.principalId()));
        execute("""
                INSERT INTO crewscope.provider_binding (
                    id, organization_id, team_id, workspace_id, target_type,
                    owner_type, owner_id, owner_team_id,
                    provider_definition_id, provider_definition_version, provider_type,
                    provider_implementation_id, provider_implementation_version,
                    connection_requirement, connection_id, connection_version,
                    connection_grant_id, connection_grant_version, execution_identity,
                    effective_capabilities, resource_unrestricted, effective_resources,
                    default_usage, status, created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', 'WORKSPACE', 'TEAM', '%s', '%s',
                    '%s', 0, 'COLLABORATION', '%s', 0, 'REQUIRED', '%s', 0, '%s', 0,
                    'TEAM_SERVICE_ACCOUNT', '["member.mapping","notification.delivery"]'::JSONB,
                    TRUE, '[]'::JSONB, TRUE, 'ACTIVE', '%s', '%s')
                """.formatted(
                graph.bindingId(), scope.organizationId(), scope.teamId(), scope.workspaceId(),
                scope.teamId(), scope.teamId(), graph.definitionId(), graph.implementationId(),
                graph.connectionId(), graph.grantId(), scope.principalId(), scope.principalId()));
        return graph;
    }

    private static void insertExternalTenant(Scope scope, LarkGraph graph, UUID tenantId)
            throws SQLException {
        execute("""
                INSERT INTO crewscope.lark_external_tenant (
                    id, organization_id, connection_id, connection_version,
                    connection_grant_id, connection_grant_version,
                    tenant_key, provider_version, status, verified_at,
                    version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', 0, '%s', 0, 'tenant_alpha', 'pv-1',
                    'VERIFIED', %s, 0, %s, %s)
                """.formatted(
                tenantId, scope.organizationId(), graph.connectionId(), graph.grantId(),
                NOW, NOW, NOW));
    }

    private static UUID insertVerificationProof(
            Scope scope, LarkGraph graph, UUID tenantId, String openId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.lark_member_verification_proof (
                    id, organization_id, team_id,
                    provider_binding_id, provider_binding_version,
                    connection_id, connection_version,
                    connection_grant_id, connection_grant_version,
                    external_tenant_id, external_tenant_version,
                    tenant_key, open_id, union_id, provider_version,
                    verification_source, status, verified_at, valid_until, created_at
                ) VALUES ('%s', '%s', '%s', '%s', 0, '%s', 0, '%s', 0, '%s', 0,
                    'tenant_alpha', '%s', 'on_alpha', 'pv-1',
                    'LARK_OPEN_API_EXACT_OPEN_ID', 'VERIFIED', %s,
                    TIMESTAMPTZ '2026-08-25 12:15:00+00', %s)
                """.formatted(
                id, scope.organizationId(), scope.teamId(), graph.bindingId(),
                graph.connectionId(), graph.grantId(), tenantId, openId, NOW, NOW));
        return id;
    }

    private static UUID insertMapping(
            Scope scope, LarkGraph graph, UUID tenantId, String openId) throws SQLException {
        return insertMapping(scope, graph, tenantId, openId, UUID.randomUUID(), scope.memberId());
    }

    private static UUID insertMapping(
            Scope scope, LarkGraph graph, UUID tenantId, String openId,
            UUID mappingId, UUID memberId) throws SQLException {
        execute("""
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
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 0, '%s', 0, '%s', 0,
                    '%s', 0, 'tenant_alpha', '%s', 'on_alpha', 'pv-1',
                    'LARK_OPEN_API_EXACT_OPEN_ID', %s, '%s', 'ACTIVE', 0,
                    %s, '%s', %s, '%s')
                """.formatted(
                mappingId, scope.organizationId(), scope.teamId(), memberId,
                graph.bindingId(), graph.connectionId(), graph.grantId(), tenantId,
                openId, NOW, scope.principalId(), NOW, scope.principalId(), NOW,
                scope.principalId()));
        return mappingId;
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

    private static boolean constraintValidated(String name) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = '%s' AND convalidated
                """.formatted(name)) == 1;
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
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID principalId,
            UUID memberId) {}

    private record LarkGraph(
            UUID credentialId,
            UUID definitionId,
            UUID implementationId,
            UUID connectionId,
            UUID grantId,
            UUID bindingId) {}
}
