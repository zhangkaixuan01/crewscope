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

/** V25 proof for one independently verified GitHub profile per Connection revision. */
class V25GitHubConnectionProfileRevisionMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_24 = MigrationVersion.fromVersion("24");
    private static final MigrationVersion VERSION_25 = MigrationVersion.fromVersion("25");

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void preservesOldProfileWhileConnectionAndNewVerificationAdvance() throws SQLException {
        flyway(VERSION_24).migrate();
        Fixture fixture = seedVersionZero();

        Flyway target = flyway(VERSION_25);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("25", target.info().current().getVersion().getVersion());

        execute("UPDATE crewscope.connection SET version = 1 WHERE id = ?", fixture.connectionId);
        insertProfile(fixture, UUID.randomUUID(), 1);

        assertEquals(2, count(
                "SELECT COUNT(*) FROM crewscope.github_connection_profile WHERE connection_id = ?",
                fixture.connectionId));
        insertRate(fixture, 1);
        assertThrows(SQLException.class, () -> insertRate(fixture, 99));
    }

    private Fixture seedVersionZero() throws SQLException {
        Fixture fixture = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'M5 I08', 'ACTIVE')",
                fixture.organizationId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'M5 I08 Actor', 'ORGANIZATION', 'ACTIVE')
                """,
                fixture.actorId, fixture.organizationId);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                execute(connection,
                        """
                        INSERT INTO crewscope.connection (
                            id, organization_id, owner_type, owner_id, owner_team_id,
                            connector_key, external_account_reference, credential_id,
                            status, version, created_at, created_by_principal_id,
                            updated_at, updated_by_principal_id
                        ) VALUES (?, ?, 'TEAM', ?, ?, 'github-source-code', '4815', ?,
                            'ACTIVE', 0, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                        """,
                        fixture.connectionId, fixture.organizationId, fixture.teamId,
                        fixture.teamId, UUID.randomUUID(), fixture.actorId, fixture.actorId);
                insertProfile(connection, fixture, UUID.randomUUID(), 0);
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
        return fixture;
    }

    private void insertProfile(Fixture fixture, UUID id, long version) throws SQLException {
        try (Connection connection = open()) {
            insertProfile(connection, fixture, id, version);
        }
    }

    private void insertProfile(Connection connection, Fixture fixture, UUID id, long version)
            throws SQLException {
        execute(connection,
                """
                    INSERT INTO crewscope.github_connection_profile (
                        id, organization_id, connection_id, connection_version,
                        connection_owner_type, connection_owner_id, external_identity,
                        authentication_type, external_account_id, external_account_login,
                        granted_permissions, repository_allowlist_hash, status, version,
                        created_at, created_by_principal_id, updated_at,
                        updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, 'TEAM', ?, 'TEAM_SERVICE_ACCOUNT',
                        'APP_INSTALLATION', '4815', 'crewscope',
                        '{"REPOSITORY_METADATA_READ":true,"CONTENTS_READ":true,
                          "CONTENTS_WRITE":true,"PULL_REQUESTS_WRITE":true}'::jsonb,
                        ?, 'ACTIVE', 0, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """,
                id, fixture.organizationId, fixture.connectionId, version, fixture.teamId,
                "a".repeat(64), fixture.actorId, fixture.actorId);
    }

    private void insertRate(Fixture fixture, long connectionVersion) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.github_rate_limit_snapshot (
                    id, organization_id, connection_id, connection_version, resource,
                    rate_limit, remaining, used, resets_at, observed_at, created_at,
                    created_by_principal_id
                ) VALUES (?, ?, ?, ?, 'core', 5000, 4993, 7,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, ?)
                """,
                UUID.randomUUID(), fixture.organizationId, fixture.connectionId,
                connectionVersion, fixture.actorId);
    }

    private int count(String sql, UUID id) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection connection = open()) {
            execute(connection, sql, values);
        }
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
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

    private record Fixture(
            UUID organizationId, UUID teamId, UUID actorId, UUID connectionId) {}
}
