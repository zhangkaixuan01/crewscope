package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** V22-to-V23 proof for independent envelope and plaintext credential revisions. */
class V23CredentialSecretBusinessVersionMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_22 = MigrationVersion.fromVersion("22");
    private static final MigrationVersion VERSION_23 = MigrationVersion.fromVersion("23");
    private static final String PROVIDER_HASH = "a".repeat(64);

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void upgradesExistingBindingsAndDefersAtomicSecretRotationForeignKey() throws SQLException {
        flyway(VERSION_22).migrate();
        Fixture fixture = seedV22Connection();

        Flyway target = flyway(VERSION_23);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals(2L, scalarLong(
                "SELECT secret_version FROM crewscope.credential_secret WHERE id = ?",
                fixture.credentialId()));
        assertTrue(scalarBoolean(
                "SELECT condeferrable AND condeferred FROM pg_constraint "
                        + "WHERE conname = 'fk_model_connection_credential'"));

        // During rotation the credential row temporarily has no version 2 target. The deferred FK
        // validates only after the connection advances to the same business revision.
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(connection,
                    "UPDATE crewscope.credential_secret SET version = version + 1, "
                            + "secret_version = secret_version + 1 WHERE id = ?",
                    fixture.credentialId());
            execute(connection,
                    "UPDATE crewscope.model_connection SET credential_version = 3, "
                            + "health_credential_version = 3 WHERE id = ?",
                    fixture.connectionId());
            connection.commit();
        }

        // Envelope-only rewrap advances optimistic locking without invalidating the connection.
        execute("UPDATE crewscope.credential_secret SET version = version + 1 WHERE id = ?",
                fixture.credentialId());
        assertEquals(4L, scalarLong(
                "SELECT version FROM crewscope.credential_secret WHERE id = ?",
                fixture.credentialId()));
        assertEquals(3L, scalarLong(
                "SELECT secret_version FROM crewscope.credential_secret WHERE id = ?",
                fixture.credentialId()));
        assertEquals(3L, scalarLong(
                "SELECT credential_version FROM crewscope.model_connection WHERE id = ?",
                fixture.connectionId()));

        assertThrows(SQLException.class, () -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                execute(connection,
                        "UPDATE crewscope.model_connection SET credential_version = 4, "
                                + "health_credential_version = 4 WHERE id = ?",
                        fixture.connectionId());
                connection.commit();
            }
        });
    }

    private Fixture seedV22Connection() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        execute("INSERT INTO crewscope.organization (id, name, status) "
                        + "VALUES (?, 'V23 Organization', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'V23 Actor', 'ORGANIZATION', 'ACTIVE')
                """,
                actorId, organizationId);
        execute(
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, subject_type, subject_id, credential_key,
                    provider_key, connection_ref, credential_type, ciphertext, nonce,
                    authentication_tag, key_id, algorithm, aad_version, metadata, status,
                    created_by_principal_id, updated_by_principal_id, version
                ) VALUES (?, ?, 'ORGANIZATION', ?, 'deepseek-v23', 'deepseek', ?,
                    'MODEL_API_KEY', decode('01', 'hex'),
                    decode('000000000000000000000000', 'hex'),
                    decode('00000000000000000000000000000000', 'hex'),
                    'key-v23', 'AES-256-GCM', '1', '{}'::jsonb, 'ACTIVE', ?, ?, 2)
                """,
                credentialId, organizationId, organizationId, connectionId, actorId, actorId);
        execute(
                """
                INSERT INTO crewscope.model_provider_definition (
                    provider_key, display_name, adapter_key, default_endpoint,
                    available_regions, retention_mode, training_usage_policy,
                    content_hash, status, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('deepseek', 'DeepSeek', 'deepseek', 'https://api.deepseek.com',
                    '["global"]'::jsonb, 'NONE', 'PROHIBITED', ?, 'ACTIVE',
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """,
                PROVIDER_HASH, actorId, actorId);
        execute(
                """
                INSERT INTO crewscope.model_connection (
                    id, organization_id, provider_key, provider_definition_hash,
                    owner_type, owner_id, endpoint, region, credential_id,
                    credential_subject_type, credential_subject_id, credential_version,
                    billing_subject_type, billing_subject_id, status, health_status,
                    health_credential_version, consecutive_failures, created_at,
                    created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, 'deepseek', ?, 'ORGANIZATION', ?,
                    'https://api.deepseek.com', 'global', ?, 'ORGANIZATION', ?, 2,
                    'ORGANIZATION', ?, 'ACTIVE', 'UNKNOWN', 2, 0,
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """,
                connectionId, organizationId, PROVIDER_HASH, organizationId,
                credentialId, organizationId, organizationId, actorId, actorId);
        return new Fixture(credentialId, connectionId);
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

    private long scalarLong(String sql, UUID value) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private boolean scalarBoolean(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection connection = connection()) {
            execute(connection, sql, values);
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    private record Fixture(UUID credentialId, UUID connectionId) {}
}
