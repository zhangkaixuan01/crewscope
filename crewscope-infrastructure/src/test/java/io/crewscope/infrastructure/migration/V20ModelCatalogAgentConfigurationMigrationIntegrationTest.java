package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V20 model catalog, Agent template/configuration and compatibility upgrade. */
class V20ModelCatalogAgentConfigurationMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_19 = MigrationVersion.fromVersion("19");
    private static final MigrationVersion VERSION_20 = MigrationVersion.fromVersion("20");
    private static final String ALTERNATE_SCHEMA = "v20_probe";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final Set<String> V20_TABLES = Set.of(
            "model_provider_definition",
            "model_catalog_entry",
            "model_price_revision",
            "model_connection",
            "agent_template_version",
            "agent_configuration_version",
            "agent_configuration_model_binding",
            "agent_model_default");

    @BeforeEach
    void resetDatabase() throws SQLException {
        resetSchemas();
    }

    @Test
    void createsCompleteV20SchemaWithMutableRootsAndAppendOnlyFacts() throws SQLException {
        Flyway flyway = flyway(POSTGRES.getJdbcUrl(), VERSION_20);
        flyway.migrate();

        assertEquals("20", flyway.info().current().getVersion().getVersion());
        assertEquals(V20_TABLES, existingTables("crewscope", V20_TABLES));

        for (String table : Set.of("model_provider_definition", "model_catalog_entry",
                "model_connection", "agent_template_version")) {
            assertTrue(columns(table).containsAll(Set.of(
                    "created_at", "created_by_principal_id",
                    "updated_at", "updated_by_principal_id")), table);
        }
        for (String table : Set.of(
                "model_price_revision", "agent_configuration_version", "agent_model_default")) {
            Set<String> names = columns(table);
            assertTrue(names.containsAll(Set.of("created_at", "created_by_principal_id")), table);
            assertFalse(names.contains("updated_at"), table);
            assertFalse(names.contains("deleted"), table);
            assertFalse(names.contains("deleted_at"), table);
        }
        assertTrue(columns("agent_profile").containsAll(Set.of(
                "ownership_type", "ownership_team_id", "runtime_role",
                "template_key", "template_version")));
        assertTrue(columns("policy_snapshot").containsAll(Set.of(
                "schema_version", "agent_execution_configuration")));
        assertTrue(columns("agent_runtime_session").containsAll(Set.of(
                "agent_ownership_type", "agent_runtime_role", "agent_template_key",
                "agent_template_version", "agent_configuration_revision",
                "agent_configuration_hash")));
    }

    @Test
    void upgradesV19ToV20AndValidatesFlywayHistory() {
        Flyway source = flyway(POSTGRES.getJdbcUrl(), VERSION_19);
        source.migrate();
        assertEquals("19", source.info().current().getVersion().getVersion());

        Flyway target = flyway(POSTGRES.getJdbcUrl(), VERSION_20);
        assertEquals(1, target.migrate().migrationsExecuted);
        assertEquals("20", target.info().current().getVersion().getVersion());
        target.validate();
    }

    @Test
    void migratesIntoCrewscopeWithNonDefaultSearchPath() throws SQLException {
        String jdbcUrl = jdbcUrlWithCurrentSchema(ALTERNATE_SCHEMA);
        assertEquals(ALTERNATE_SCHEMA, currentSchema(jdbcUrl));

        Flyway flyway = flyway(jdbcUrl, VERSION_20);
        flyway.migrate();

        assertEquals(V20_TABLES, existingTables("crewscope", V20_TABLES));
        assertTrue(existingTables(ALTERNATE_SCHEMA, V20_TABLES).isEmpty());
        assertEquals(0, tableCount(ALTERNATE_SCHEMA, "flyway_schema_history"));
    }

    @Test
    void backfillsLegacyProfileSessionAndPolicyWithoutChangingStableFacts() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_19).migrate();
        LegacyFixture fixture = seedLegacyPersonalAgent();
        UUID policyId = insertLegacyPolicyFixture(fixture);

        flyway(POSTGRES.getJdbcUrl(), VERSION_20).migrate();

        assertRow(
                """
                SELECT ownership_type, ownership_team_id::text, runtime_role,
                       template_key, template_version, version,
                       agent_principal_id::text,
                       (created_at = TIMESTAMPTZ '2026-08-20 08:00:00+00')::text
                FROM crewscope.agent_profile WHERE id = ?
                """,
                fixture.agentProfileId(),
                "USER", fixture.teamId().toString(), "PERSONAL_ASSISTANT",
                "personal-assistant", "1", "7",
                fixture.agentPrincipalId().toString(), "true");
        assertRow(
                """
                SELECT agent_ownership_type, agent_runtime_role, agent_template_key,
                       agent_template_version, agent_configuration_revision,
                       agent_configuration_hash, state_reference
                FROM crewscope.agent_runtime_session WHERE id = ?
                """,
                fixture.sessionId(),
                "USER", "PERSONAL_ASSISTANT", "personal-assistant", "1",
                null, null, fixture.stateReference());
        assertRow(
                """
                SELECT schema_version, agent_execution_configuration, snapshot_hash
                FROM crewscope.policy_snapshot WHERE id = ?
                """,
                policyId, "1", null, HASH_A);
    }

    @Test
    void createsScopeRevisionConflictKeysAndQueryIndexes() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_20).migrate();

        Set<String> constraints = queryStrings(
                """
                SELECT conname FROM pg_constraint
                WHERE connamespace = 'crewscope'::regnamespace
                """);
        assertTrue(constraints.containsAll(Set.of(
                "uk_model_catalog_entry_coordinate",
                "uk_model_price_revision_effective",
                "uk_credential_secret_model_binding",
                "uk_model_connection_scope_id",
                "uk_agent_template_version_exact",
                "uk_agent_profile_organization_id",
                "uk_agent_configuration_version_hash",
                "uk_agent_model_default_hash",
                "fk_model_connection_credential",
                "fk_agent_configuration_model_binding_primary_connection",
                "fk_agent_configuration_model_binding_primary_catalog")));

        Set<String> indexes = queryStrings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'");
        assertTrue(indexes.containsAll(Set.of(
                "ix_model_provider_definition_status",
                "ix_model_catalog_entry_selectable",
                "ix_model_catalog_entry_capabilities",
                "ix_model_price_revision_effective_lookup",
                "ix_model_connection_owner_status",
                "ix_model_connection_provider_health",
                "ix_agent_template_version_catalog",
                "ix_agent_profile_ownership_status",
                "ix_agent_configuration_version_latest",
                "ix_agent_configuration_model_binding_connection",
                "ix_agent_model_default_latest",
                "ix_agent_runtime_session_configuration")));
    }

    @Test
    void rejectsDuplicateCatalogAndPriceRevisionsAndInvalidConnectionScope() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_20).migrate();
        ModelFixture fixture = seedModelFixture();

        insertCatalogEntry(fixture, UUID.randomUUID(), 1, null);
        assertSqlState("23505", () ->
                insertCatalogEntry(fixture, UUID.randomUUID(), 1, null));
        assertSqlState("23514", () ->
                insertCatalogEntry(fixture, UUID.randomUUID(), 3, 1L));

        insertPrice(fixture.catalogEntryId(), fixture, 1, "2026-08-23T00:00:00Z");
        assertSqlState("23505", () ->
                insertPrice(fixture.catalogEntryId(), fixture, 2, "2026-08-23T00:00:00Z"));

        assertSqlState("23514", () -> execute(
                modelConnectionInsertSql(),
                UUID.randomUUID(), fixture.organizationId(), fixture.providerKey(), HASH_A,
                "USER", fixture.teamId(), null, fixture.actorId(),
                "https://api.example.com/v1", "global", fixture.credentialId(),
                "PRINCIPAL", fixture.actorId(), 0L,
                "PRINCIPAL", fixture.actorId(), null, fixture.actorId(),
                fixture.actorId(), fixture.actorId()));
    }

    @Test
    void rejectsForgedProfilePolicyAndPartialSessionUpgradeShapes() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_20).migrate();
        LegacyFixture fixture = seedCurrentScope();

        assertSqlState("23514", () -> execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    owner_member_id, ownership_type, ownership_team_id,
                    runtime_role, template_key, template_version,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'USER', ?, 'SPECIALIST',
                          'coding', 1, 'PERSONAL', TRUE, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.agentPrincipalId(), fixture.memberId(),
                fixture.teamId(), fixture.actorId(), fixture.actorId()));

        assertSqlState("23514", () -> execute(
                """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    owner_member_id, owner_principal_id, personal_agent_principal_id,
                    agent_profile_id, agent_profile_version,
                    session_purpose, agent_principal_id, agent_principal_type,
                    agent_profile_type, agent_scope_user_id, agent_scope_session_id,
                    state_reference, status, agent_ownership_type,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 7, 'PERSONAL', ?,
                          'PERSONAL_AGENT', 'PERSONAL', 'user', 'session', 'state',
                          'ACTIVE', 'USER', ?, ?)
                """,
                UUID.randomUUID(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.conversationId(), fixture.memberId(),
                fixture.actorId(), fixture.agentPrincipalId(), fixture.agentProfileId(),
                fixture.agentPrincipalId(), fixture.actorId(), fixture.actorId()));

        assertSqlState("23514", () -> execute(
                """
                INSERT INTO crewscope.policy_snapshot (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, revision, change_reason,
                    execution_principal_id, execution_assignment_id,
                    execution_assignment_version, responsibility_snapshot_hash,
                    policy_pack_id, policy_pack_version, agent_profile_id,
                    agent_profile_version, capabilities, allowed_tools,
                    provider_binding_ids, max_tokens, max_model_calls,
                    max_tool_calls, max_duration_seconds, snapshot_hash,
                    schema_version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'TASK_CREATED', ?, ?, 0, ?,
                          ?, 0, ?, 7, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                          1000, 10, 10, 600, ?, 2, CURRENT_TIMESTAMP, ?,
                          CURRENT_TIMESTAMP, ?)
                """,
                UUID.randomUUID(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                fixture.agentPrincipalId(), UUID.randomUUID(), HASH_B, UUID.randomUUID(),
                fixture.agentProfileId(), HASH_A, fixture.actorId(), fixture.actorId()));
    }

    private static LegacyFixture seedLegacyPersonalAgent() throws SQLException {
        LegacyFixture fixture = seedScope(false);
        execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, owner_member_id,
                    profile_type, default_profile, status, version,
                    created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'PERSONAL', TRUE, 'ACTIVE', 7,
                          CAST(? AS TIMESTAMPTZ), ?, CAST(? AS TIMESTAMPTZ), ?)
                """,
                fixture.agentProfileId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.agentPrincipalId(), fixture.memberId(),
                fixture.profileCreatedAt(), fixture.actorId(), fixture.profileCreatedAt(),
                fixture.actorId());
        insertConversation(fixture);
        execute(
                """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    owner_member_id, owner_principal_id, personal_agent_principal_id,
                    agent_profile_id, agent_profile_version,
                    session_purpose, agent_principal_id, agent_principal_type,
                    agent_profile_type, agent_scope_user_id, agent_scope_session_id,
                    state_reference, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 7, 'PERSONAL', ?,
                          'PERSONAL_AGENT', 'PERSONAL', ?, ?, ?,
                          'ACTIVE', ?, ?)
                """,
                fixture.sessionId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.conversationId(), fixture.memberId(),
                fixture.actorId(), fixture.agentPrincipalId(), fixture.agentProfileId(),
                fixture.agentPrincipalId(), "crewscope:v1:user:" + fixture.memberId(),
                "crewscope:v1:session:" + fixture.sessionId(), fixture.stateReference(),
                fixture.actorId(), fixture.actorId());
        return fixture;
    }

    private static LegacyFixture seedCurrentScope() throws SQLException {
        LegacyFixture fixture = seedScope(true);
        execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    owner_member_id, ownership_type, ownership_team_id,
                    runtime_role, template_key, template_version,
                    profile_type, default_profile, status, version,
                    created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'USER', ?, 'PERSONAL_ASSISTANT',
                          'personal-assistant', 1, 'PERSONAL', TRUE, 'ACTIVE', 7,
                          CAST(? AS TIMESTAMPTZ), ?, CAST(? AS TIMESTAMPTZ), ?)
                """,
                fixture.agentProfileId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.agentPrincipalId(), fixture.memberId(),
                fixture.teamId(), fixture.profileCreatedAt(), fixture.actorId(),
                fixture.profileCreatedAt(), fixture.actorId());
        insertConversation(fixture);
        return fixture;
    }

    private static LegacyFixture seedScope(boolean latest) throws SQLException {
        UUID sessionId = UUID.randomUUID();
        LegacyFixture fixture = new LegacyFixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                sessionId, "crewscope:agent-state:v1:" + sessionId,
                "2026-08-20 08:00:00+00");
        execute("INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                fixture.organizationId());
        execute("INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Team', 'ACTIVE')",
                fixture.teamId(), fixture.organizationId());
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace', 'ACTIVE')
                """,
                fixture.workspaceId(), fixture.organizationId(), fixture.teamId());
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Owner', 'ACTIVE')
                """,
                fixture.actorId(), fixture.organizationId());
        execute(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                fixture.memberId(), fixture.organizationId(), fixture.teamId(), fixture.actorId());
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, owner_principal_id,
                    display_name, visibility, status
                ) VALUES (?, ?, ?, 'PERSONAL_AGENT', ?, 'Personal Agent', 'PRIVATE', 'ACTIVE')
                """,
                fixture.agentPrincipalId(), fixture.organizationId(), fixture.teamId(),
                fixture.actorId());
        return fixture;
    }

    private static void insertConversation(LegacyFixture fixture) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.conversation (
                    id, organization_id, team_id, workspace_id,
                    owner_member_id, owner_principal_id, personal_agent_principal_id,
                    title, visibility, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'Conversation', 'PRIVATE', 'ACTIVE', ?, ?)
                """,
                fixture.conversationId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.memberId(), fixture.actorId(),
                fixture.agentPrincipalId(), fixture.actorId(), fixture.actorId());
    }

    private static UUID insertLegacyPolicyFixture(LegacyFixture fixture) throws SQLException {
        UUID policyId = UUID.randomUUID();
        execute("ALTER TABLE crewscope.policy_snapshot DISABLE TRIGGER ALL");
        try {
            execute(
                    """
                    INSERT INTO crewscope.policy_snapshot (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, revision, change_reason,
                        execution_principal_id, execution_assignment_id,
                        execution_assignment_version, responsibility_snapshot_hash,
                        policy_pack_id, policy_pack_version, agent_profile_id,
                        agent_profile_version, capabilities, allowed_tools,
                        provider_binding_ids, max_tokens, max_model_calls,
                        max_tool_calls, max_duration_seconds, snapshot_hash,
                        created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'TASK_CREATED', ?, ?, 0, ?,
                              ?, 0, ?, 7, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                              1000, 10, 10, 600, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                    """,
                    policyId, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    fixture.agentPrincipalId(), UUID.randomUUID(), HASH_B, UUID.randomUUID(),
                    fixture.agentProfileId(), HASH_A, fixture.actorId(), fixture.actorId());
        } finally {
            execute("ALTER TABLE crewscope.policy_snapshot ENABLE TRIGGER ALL");
        }
        return policyId;
    }

    private static ModelFixture seedModelFixture() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID catalogEntryId = UUID.randomUUID();
        execute("INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute("INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Team', 'ACTIVE')",
                teamId, organizationId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Owner', 'ACTIVE')
                """,
                actorId, organizationId);
        execute(
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, principal_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type, ciphertext, nonce,
                    authentication_tag, key_id, algorithm, aad_version, metadata, status
                ) VALUES (?, ?, ?, 'PRINCIPAL', ?, 'model-key', 'deepseek', 'API_KEY',
                          decode('01', 'hex'), decode('000000000000000000000000', 'hex'),
                          decode('00000000000000000000000000000000', 'hex'), 'key-1',
                          'AES-256-GCM', 'v1', '{}'::jsonb, 'ACTIVE')
                """,
                credentialId, organizationId, actorId, actorId);
        execute(
                """
                INSERT INTO crewscope.model_provider_definition (
                    provider_key, display_name, adapter_key, default_endpoint,
                    available_regions, retention_mode, training_usage_policy,
                    content_hash, status, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('deepseek', 'DeepSeek', 'openai-compatible',
                          'https://api.example.com/v1', '["global"]'::jsonb,
                          'NONE', 'PROHIBITED', ?, 'ACTIVE', CURRENT_TIMESTAMP, ?,
                          CURRENT_TIMESTAMP, ?)
                """,
                HASH_A, actorId, actorId);
        return new ModelFixture(
                organizationId, teamId, actorId, credentialId,
                catalogEntryId, "deepseek");
    }

    private static void insertCatalogEntry(
            ModelFixture fixture, UUID id, long revision, Long previous) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.model_catalog_entry (
                    id, provider_key, provider_definition_hash, model_id,
                    catalog_revision, previous_catalog_revision, model_revision,
                    display_name, context_window_tokens, maximum_output_tokens,
                    capabilities, available_regions, content_hash, status,
                    created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, 'deepseek-v4-flash', ?, ?, 'DeepSeek-V4-Flash-0731',
                          'DeepSeek V4 Flash', 131072, 8192,
                          '["model.tool-calling"]'::jsonb, '["global"]'::jsonb,
                          ?, 'ACTIVE', CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """,
                id, fixture.providerKey(), HASH_A, revision, previous,
                HASH_B, fixture.actorId(), fixture.actorId());
        if (revision == 1) {
            fixture.catalogEntryId = id;
        }
    }

    private static void insertPrice(
            UUID catalogEntryId, ModelFixture fixture, long revision, String effectiveFrom)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.model_price_revision (
                    catalog_entry_id, provider_key, model_id, catalog_revision,
                    price_revision, effective_from, input_per_million_tokens,
                    output_per_million_tokens, currency_code, price_source,
                    content_hash, created_at, created_by_principal_id
                ) VALUES (?, ?, 'deepseek-v4-flash', 1, ?, CAST(? AS TIMESTAMPTZ),
                          1.000000000000, 2.000000000000, 'USD', 'fixture', ?,
                          CAST('2026-08-24T00:00:00Z' AS TIMESTAMPTZ), ?)
                """,
                catalogEntryId, fixture.providerKey(), revision,
                effectiveFrom, HASH_A, fixture.actorId());
    }

    private static String modelConnectionInsertSql() {
        return """
                INSERT INTO crewscope.model_connection (
                    id, organization_id, provider_key, provider_definition_hash,
                    owner_type, owner_id, owner_team_id, owner_user_principal_id,
                    endpoint, region, credential_id, credential_subject_type,
                    credential_subject_id, credential_version,
                    billing_subject_type, billing_subject_id,
                    billing_team_id, billing_principal_id,
                    status, health_status, health_credential_version, consecutive_failures,
                    created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'ACTIVE', 'UNKNOWN', 0, 0, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """;
    }

    private static void resetSchemas() throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + ALTERNATE_SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + ALTERNATE_SCHEMA);
        }
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true)
                .target(target);
        return configuration.load();
    }

    private static Set<String> columns(String table) throws SQLException {
        return queryStrings(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'crewscope' AND table_name = '" + table + "'");
    }

    private static Set<String> existingTables(String schema, Set<String> expected)
            throws SQLException {
        String placeholders = String.join(",", expected.stream().map(value -> "?").toList());
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name IN (" + placeholders + ")";
        Set<String> values = new HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            int index = 2;
            for (String table : expected) {
                statement.setString(index++, table);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        }
        return Set.copyOf(values);
    }

    private static int tableCount(String schema, String table) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return Set.copyOf(values);
    }

    private static void assertRow(String sql, UUID id, String... expected) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                for (int index = 0; index < expected.length; index++) {
                    assertEquals(expected[index], result.getString(index + 1), "column " + (index + 1));
                }
                assertFalse(result.next());
            }
        }
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException exception = assertThrows(SQLException.class, action::run);
        assertEquals(expected, exception.getSQLState());
    }

    private static Connection openConnection(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String jdbcUrlWithCurrentSchema(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
    }

    private static String currentSchema(String jdbcUrl) throws SQLException {
        try (Connection connection = openConnection(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT current_schema()")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private record LegacyFixture(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID actorId,
            UUID memberId,
            UUID agentPrincipalId,
            UUID agentProfileId,
            UUID conversationId,
            UUID sessionId,
            String stateReference,
            String profileCreatedAt) {}

    private static final class ModelFixture {
        private final UUID organizationId;
        private final UUID teamId;
        private final UUID actorId;
        private final UUID credentialId;
        private UUID catalogEntryId;
        private final String providerKey;

        private ModelFixture(
                UUID organizationId,
                UUID teamId,
                UUID actorId,
                UUID credentialId,
                UUID catalogEntryId,
                String providerKey) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.actorId = actorId;
            this.credentialId = credentialId;
            this.catalogEntryId = catalogEntryId;
            this.providerKey = providerKey;
        }

        UUID organizationId() { return organizationId; }
        UUID teamId() { return teamId; }
        UUID actorId() { return actorId; }
        UUID credentialId() { return credentialId; }
        UUID catalogEntryId() { return catalogEntryId; }
        String providerKey() { return providerKey; }
    }
}
