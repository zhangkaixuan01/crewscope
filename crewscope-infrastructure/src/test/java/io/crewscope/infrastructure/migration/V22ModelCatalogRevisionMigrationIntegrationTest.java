package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Upgrade evidence for the stable ModelCatalogEntry ID and per-catalog price stream fix. */
class V22ModelCatalogRevisionMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_21 = MigrationVersion.fromVersion("21");
    private static final MigrationVersion VERSION_22 = MigrationVersion.fromVersion("22");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void upgradesV21AndAllowsStableEntryIdentityAcrossCatalogAndPriceRevisions()
            throws SQLException {
        Flyway source = flyway(VERSION_21);
        source.migrate();
        Fixture fixture = seedV21RevisionOne();

        Flyway target = flyway(VERSION_22);
        assertEquals(1, target.migrate().migrationsExecuted);
        assertEquals("22", target.info().current().getVersion().getVersion());
        target.validate();

        execute(
                """
                INSERT INTO crewscope.model_catalog_entry (
                    id, provider_key, provider_definition_hash, model_id,
                    catalog_revision, previous_catalog_revision, model_revision,
                    display_name, context_window_tokens, maximum_output_tokens,
                    capabilities, available_regions, content_hash, status,
                    lifecycle_version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, 'provider-v22', ?, 'model-v22', 2, 1, 'revision-2',
                    'Model v2', 160000, 12000, '["tool-calling"]'::jsonb,
                    '["global"]'::jsonb, ?, 'ACTIVE', 0,
                    TIMESTAMPTZ '2026-08-23 08:02:00+00', ?,
                    TIMESTAMPTZ '2026-08-23 08:02:00+00', ?)
                """,
                fixture.catalogEntryId(), HASH_A, HASH_B, fixture.actorId(), fixture.actorId());
        execute(
                """
                INSERT INTO crewscope.model_price_revision (
                    catalog_entry_id, provider_key, model_id, catalog_revision,
                    price_revision, effective_from, input_per_million_tokens,
                    output_per_million_tokens, currency_code, price_source,
                    content_hash, created_at, created_by_principal_id
                ) VALUES (?, 'provider-v22', 'model-v22', 2, 1,
                    TIMESTAMPTZ '2026-08-23 08:00:00+00', 0.2, 0.4, 'USD',
                    'migration-test', ?, TIMESTAMPTZ '2026-08-23 08:02:00+00', ?)
                """,
                fixture.catalogEntryId(), HASH_B, fixture.actorId());

        assertThrows(SQLException.class, () -> execute(
                """
                INSERT INTO crewscope.model_catalog_entry (
                    id, provider_key, provider_definition_hash, model_id,
                    catalog_revision, previous_catalog_revision, model_revision,
                    display_name, context_window_tokens, maximum_output_tokens,
                    capabilities, available_regions, content_hash, status,
                    lifecycle_version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, 'provider-v22', ?, 'forged-model', 3, 2, 'revision-3',
                    'Forged model', 160000, 12000, '["tool-calling"]'::jsonb,
                    '["global"]'::jsonb, ?, 'ACTIVE', 0,
                    TIMESTAMPTZ '2026-08-23 08:03:00+00', ?,
                    TIMESTAMPTZ '2026-08-23 08:03:00+00', ?)
                """,
                fixture.catalogEntryId(), HASH_A, HASH_B,
                fixture.actorId(), fixture.actorId()));

        assertEquals(2, count("model_catalog_entry"));
        assertEquals(2, count("model_price_revision"));
    }

    private Fixture seedV21RevisionOne() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID catalogEntryId = UUID.randomUUID();
        execute("INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'V22 Org', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Actor', 'ORGANIZATION', 'ACTIVE')
                """,
                actorId, organizationId);
        execute(
                """
                INSERT INTO crewscope.model_provider_definition (
                    provider_key, display_name, adapter_key, default_endpoint,
                    available_regions, retention_mode, training_usage_policy,
                    content_hash, status, lifecycle_version, created_at,
                    created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('provider-v22', 'Provider V22', 'openai-compatible',
                    'https://api.example.com', '["global"]'::jsonb, 'NONE', 'PROHIBITED',
                    ?, 'ACTIVE', 0, TIMESTAMPTZ '2026-08-23 08:00:00+00', ?,
                    TIMESTAMPTZ '2026-08-23 08:00:00+00', ?)
                """,
                HASH_A, actorId, actorId);
        execute(
                """
                INSERT INTO crewscope.model_catalog_entry (
                    id, provider_key, provider_definition_hash, model_id,
                    catalog_revision, model_revision, display_name,
                    context_window_tokens, maximum_output_tokens, capabilities,
                    available_regions, content_hash, status, lifecycle_version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, 'provider-v22', ?, 'model-v22', 1, 'revision-1',
                    'Model v1', 128000, 8192, '["tool-calling"]'::jsonb,
                    '["global"]'::jsonb, ?, 'ACTIVE', 0,
                    TIMESTAMPTZ '2026-08-23 08:00:00+00', ?,
                    TIMESTAMPTZ '2026-08-23 08:00:00+00', ?)
                """,
                catalogEntryId, HASH_A, HASH_A, actorId, actorId);
        execute(
                """
                INSERT INTO crewscope.model_price_revision (
                    catalog_entry_id, provider_key, model_id, catalog_revision,
                    price_revision, effective_from, input_per_million_tokens,
                    output_per_million_tokens, currency_code, price_source,
                    content_hash, created_at, created_by_principal_id
                ) VALUES (?, 'provider-v22', 'model-v22', 1, 1,
                    TIMESTAMPTZ '2026-08-23 08:00:00+00', 0.1, 0.3, 'USD',
                    'migration-test', ?, TIMESTAMPTZ '2026-08-23 08:00:00+00', ?)
                """,
                catalogEntryId, HASH_A, actorId);
        return new Fixture(actorId, catalogEntryId);
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .target(target)
                .load();
    }

    private int count(String table) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM crewscope." + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record Fixture(UUID actorId, UUID catalogEntryId) {}
}
