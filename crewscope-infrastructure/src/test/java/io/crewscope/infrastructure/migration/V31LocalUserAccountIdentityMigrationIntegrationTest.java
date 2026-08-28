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
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Locks down V31 deployment accounts, login identities and Organization authorization bindings. */
class V31LocalUserAccountIdentityMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_30 = MigrationVersion.fromVersion("30");
    private static final MigrationVersion VERSION_31 = MigrationVersion.fromVersion("31");
    private static final String NOW = "TIMESTAMPTZ '2026-08-28 10:00:00+00'";
    private static final String LATER = "TIMESTAMPTZ '2026-08-28 10:01:00+00'";

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS v31_probe CASCADE");
            statement.execute("DROP ROLE IF EXISTS v31_metadata_reader");
            statement.execute("CREATE SCHEMA v31_probe");
        }
    }

    @Test
    void migratesEmptyDatabaseWithNonDefaultSearchPathAndConvergesAtV31()
            throws SQLException {
        String url = POSTGRES.getJdbcUrl() + "&currentSchema=v31_probe";
        Flyway target = flyway(url, VERSION_31);

        assertEquals(31, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("31", target.info().current().getVersion().getVersion());
        assertEquals(1, tableCount("crewscope", "user_account"));
        assertEquals(1, tableCount("crewscope", "login_identity"));
        assertEquals(1, tableCount("crewscope", "local_credential"));
        assertEquals(1, tableCount("crewscope", "account_organization_binding"));
        assertEquals(0, tableCount("v31_probe", "user_account"));
        assertEquals(0, target.migrate().migrationsExecuted);
    }

    @Test
    void upgradesV30WithoutChangingExistingIdentityRows() throws SQLException {
        Flyway source = flyway(POSTGRES.getJdbcUrl(), VERSION_30);
        assertEquals(30, source.migrate().migrationsExecuted);
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'Existing Organization', 'ACTIVE')
                """.formatted(organizationId));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES ('%s', '%s', 'USER', 'Existing User', 'ORGANIZATION', 'ACTIVE')
                """.formatted(principalId, organizationId));

        Flyway target = flyway(POSTGRES.getJdbcUrl(), VERSION_31);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();

        assertEquals(1, scalar("SELECT COUNT(*) FROM crewscope.organization"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM crewscope.principal"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM crewscope.user_account"));
    }

    @Test
    void enforcesCanonicalIdentityCredentialAndBindingConstraints() throws SQLException {
        migrateLatest();
        UUID accountId = insertAccount("Alice", "alice", "Alice@Example.com", "alice@example.com");
        UUID secondAccountId =
                insertAccount("Bob", "bob", "Bob@Example.com", "bob@example.com");
        UUID organizationId = insertOrganization("Primary Organization");
        UUID secondOrganizationId = insertOrganization("Other Organization");
        UUID principalId = insertPrincipal(organizationId, "USER", null);
        UUID secondPrincipalId = insertPrincipal(secondOrganizationId, "USER", null);

        insertLocalIdentity(accountId);
        insertCredential(accountId);
        insertBinding(accountId, organizationId, principalId);

        assertSqlState("23505", () -> insertAccount(
                "Alice Alias", "alice", "alias@example.com", "alias@example.com"));
        assertSqlState("23505", () -> insertAccount(
                "Mail Alias", "mail-alias", "ALICE@example.com", "alice@example.com"));
        assertSqlState("23505", () -> execute("""
                INSERT INTO crewscope.login_identity (
                    id, account_id, provider, subject, status, created_at, updated_at
                ) VALUES ('%s', '%s', 'local', '%s', 'ACTIVE', %s, %s)
                """.formatted(
                UUID.randomUUID(), accountId, accountId, NOW, NOW)));
        assertSqlState("23514", () -> execute("""
                INSERT INTO crewscope.login_identity (
                    id, account_id, provider, subject, status, created_at, updated_at
                ) VALUES ('%s', '%s', 'local', '%s', 'ACTIVE', %s, %s)
                """.formatted(
                UUID.randomUUID(), secondAccountId, UUID.randomUUID(), NOW, NOW)));
        assertSqlState("23505", () -> insertCredential(accountId));
        UUID alternativePrincipal = insertPrincipal(organizationId, "USER", null);
        assertSqlState("23505", () -> insertBinding(
                accountId, organizationId, alternativePrincipal));
        assertSqlState("23505", () -> insertBinding(secondAccountId, organizationId, principalId));
        assertSqlState("23503", () -> insertBinding(
                secondAccountId, organizationId, secondPrincipalId));

        UUID servicePrincipal = insertPrincipal(organizationId, "SERVICE", null);
        assertSqlState("23514", () -> insertBinding(
                secondAccountId, organizationId, servicePrincipal));
        UUID teamId = insertTeam(organizationId);
        UUID teamPrincipal = insertPrincipal(organizationId, "USER", teamId);
        assertSqlState("23514", () -> insertBinding(
                secondAccountId, organizationId, teamPrincipal));
    }

    @Test
    void enforcesVersionsForwardOnlyStatesAndBoundPrincipalShape() throws SQLException {
        migrateLatest();
        UUID accountId = insertAccount("Alice", "alice", "alice@example.com", "alice@example.com");
        UUID identityId = insertLocalIdentity(accountId);
        UUID credentialId = insertCredential(accountId);
        UUID organizationId = insertOrganization("Primary Organization");
        UUID principalId = insertPrincipal(organizationId, "USER", null);
        UUID bindingId = insertBinding(accountId, organizationId, principalId);

        execute("""
                UPDATE crewscope.user_account
                SET display_name = 'Alice Updated', version = 1, updated_at = %s
                WHERE id = '%s'
                """.formatted(LATER, accountId));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.user_account
                SET display_name = 'Skipped Version', version = 3,
                    updated_at = TIMESTAMPTZ '2026-08-28 10:02:00+00'
                WHERE id = '%s'
                """.formatted(accountId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.login_identity
                SET subject = '%s', version = 1, updated_at = %s
                WHERE id = '%s'
                """.formatted(UUID.randomUUID(), LATER, identityId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.local_credential
                SET password_hash = '{argon2id}$new-printable-hash-body-0123456789',
                    credential_version = 3, version = 1,
                    password_changed_at = %s, updated_at = %s
                WHERE id = '%s'
                """.formatted(LATER, LATER, credentialId)));
        execute("""
                UPDATE crewscope.account_organization_binding
                SET status = 'DISABLED', version = 1, updated_at = %s
                WHERE id = '%s'
                """.formatted(LATER, bindingId));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.account_organization_binding
                SET status = 'DISABLED', version = 2,
                    updated_at = TIMESTAMPTZ '2026-08-28 10:02:00+00'
                WHERE id = '%s'
                """.formatted(bindingId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.principal
                SET principal_type = 'SERVICE', version = version + 1, updated_at = %s
                WHERE id = '%s'
                """.formatted(LATER, principalId)));
        assertSqlState("23514", () -> execute(
                "DELETE FROM crewscope.user_account WHERE id = '" + accountId + "'"));
    }

    @Test
    void exposesOnlyCredentialMetadataToGenericReader() throws SQLException {
        migrateLatest();
        UUID accountId = insertAccount("Alice", "alice", "alice@example.com", "alice@example.com");
        insertCredential(accountId);
        execute("CREATE ROLE v31_metadata_reader NOLOGIN");
        execute("GRANT USAGE ON SCHEMA crewscope TO v31_metadata_reader");
        execute("GRANT SELECT ON crewscope.local_credential_metadata TO v31_metadata_reader");

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE v31_metadata_reader");
            assertEquals(1, queryScalar(statement,
                    "SELECT COUNT(*) FROM crewscope.local_credential_metadata"));
            SQLException denied = assertThrows(SQLException.class, () -> queryScalar(
                    statement, "SELECT password_hash FROM crewscope.local_credential"));
            assertEquals("42501", denied.getSQLState());
            statement.execute("RESET ROLE");
        }

        assertFalse(viewHasColumn("local_credential_metadata", "password_hash"));
        assertFalse(publicHasColumnPrivilege("local_credential", "password_hash", "SELECT"));
    }

    @Test
    void rollsBackWholeMigrationWhenAReservedRelationConflicts() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_30).migrate();
        execute("CREATE TABLE crewscope.login_identity (collision INTEGER PRIMARY KEY)");

        assertThrows(
                FlywayException.class,
                () -> flyway(POSTGRES.getJdbcUrl(), VERSION_31).migrate());

        assertEquals(0, tableCount("crewscope", "user_account"));
        assertEquals(1, tableCount("crewscope", "login_identity"));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = 'login_identity'
                  AND column_name = 'collision'
                """));
        assertEquals("30", flyway(POSTGRES.getJdbcUrl(), VERSION_31)
                .info().current().getVersion().getVersion());
    }

    private static UUID insertAccount(
            String displayName, String username, String email, String normalizedEmail)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized,
                    display_name, status, platform_role, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s',
                    'ACTIVE', 'USER', %s, %s)
                """.formatted(
                id, username, username, email, normalizedEmail, displayName, NOW, NOW));
        return id;
    }

    private static UUID insertLocalIdentity(UUID accountId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.login_identity (
                    id, account_id, provider, subject, status, created_at, updated_at
                ) VALUES ('%s', '%s', 'local', '%s', 'ACTIVE', %s, %s)
                """.formatted(id, accountId, accountId, NOW, NOW));
        return id;
    }

    private static UUID insertCredential(UUID accountId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.local_credential (
                    id, account_id, password_hash, algorithm, credential_version,
                    password_changed_at, version, created_at, updated_at
                ) VALUES ('%s', '%s', '{argon2id}$printable-hash-body-0123456789',
                    'argon2id', 1, %s, 0, %s, %s)
                """.formatted(id, accountId, NOW, NOW, NOW));
        return id;
    }

    private static UUID insertOrganization(String name) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', '%s', 'ACTIVE')
                """.formatted(id, name));
        return id;
    }

    private static UUID insertTeam(UUID organizationId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'Identity Team', 'ACTIVE')
                """.formatted(id, organizationId));
        return id;
    }

    private static UUID insertPrincipal(UUID organizationId, String type, UUID teamId)
            throws SQLException {
        UUID id = UUID.randomUUID();
        String teamValue = teamId == null ? "NULL" : "'" + teamId + "'";
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status
                ) VALUES ('%s', '%s', %s, '%s', 'Identity Principal',
                    'ORGANIZATION', 'ACTIVE')
                """.formatted(id, organizationId, teamValue, type));
        return id;
    }

    private static UUID insertBinding(UUID accountId, UUID organizationId, UUID principalId)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.account_organization_binding (
                    id, account_id, organization_id, principal_id,
                    status, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', %s, %s)
                """.formatted(id, accountId, organizationId, principalId, NOW, NOW));
        return id;
    }

    private static void migrateLatest() {
        flyway(POSTGRES.getJdbcUrl(), VERSION_31).migrate();
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
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

    private static int queryScalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            int value = result.getInt(1);
            assertFalse(result.next());
            return value;
        }
    }

    private static int scalar(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            return queryScalar(statement, sql);
        }
    }

    private static int tableCount(String schema, String table) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = '%s' AND table_name = '%s'
                """.formatted(schema, table));
    }

    private static boolean viewHasColumn(String view, String column) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = '%s' AND column_name = '%s'
                """.formatted(view, column)) > 0;
    }

    private static boolean publicHasColumnPrivilege(
            String table, String column, String privilege) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM information_schema.column_privileges
                WHERE table_schema = 'crewscope' AND table_name = '%s'
                  AND column_name = '%s' AND grantee = 'PUBLIC'
                  AND privilege_type = '%s'
                """.formatted(table, column, privilege)) > 0;
    }

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException exception = assertThrows(SQLException.class, action::execute);
        assertEquals(expected, exception.getSQLState());
    }

    @FunctionalInterface
    private interface SqlAction {
        void execute() throws SQLException;
    }
}
