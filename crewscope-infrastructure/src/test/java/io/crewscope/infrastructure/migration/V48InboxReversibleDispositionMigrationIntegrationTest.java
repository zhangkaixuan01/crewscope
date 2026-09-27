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

/**
 * Locks down the V48 reversible Inbox disposition contract: persisted UNREAD rows are legal, the
 * V27 non-monotonic protections (no DELETE, immutable scope and creation facts, version + 1,
 * updated_at never backwards) survive verbatim, and the monotonic rank guard is gone.
 */
class V48InboxReversibleDispositionMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_47 = MigrationVersion.fromVersion("47");
    private static final String NOW = "TIMESTAMPTZ '2026-09-26 09:00:00+00'";
    private static final String LATER = "TIMESTAMPTZ '2026-09-26 09:05:00+00'";
    private static final String LATEST = "TIMESTAMPTZ '2026-09-26 09:10:00+00'";

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void migratesAnEmptySchemaToV48() {
        Flyway target = flyway(MigrationVersion.fromVersion("48"));

        assertTrue(target.migrate().migrationsExecuted >= 48);
        target.validate();
        assertEquals("48", target.info().current().getVersion().getVersion());
    }

    @Test
    void upgradesV47RowsAndAcceptsNewPersistedUnreadRows() throws SQLException {
        flyway(VERSION_47).migrate();
        Scope scope = seedScope();
        UUID itemId = UUID.randomUUID();

        // A pre-V48 READ row at version 1 must survive the migration untouched.
        execute(dispositionInsert(scope, itemId, "READ", 1, NOW, NOW));

        Flyway target = flyway(MigrationVersion.fromVersion("48"));
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("READ", textScalar(statusQuery(scope, itemId)));
        assertEquals(1, scalar(versionQuery(scope, itemId)));

        // The relaxed CHECK accepts a persisted UNREAD row.
        UUID unreadItemId = UUID.randomUUID();
        execute(dispositionInsert(scope, unreadItemId, "UNREAD", 4, NOW, LATER));
        assertEquals("UNREAD", textScalar(statusQuery(scope, unreadItemId)));
        assertEquals(4, scalar(versionQuery(scope, unreadItemId)));
    }

    @Test
    void admitsReversibleTransitionsAndBlocksIllegalValues() throws SQLException {
        migrateLatest();
        Scope scope = seedScope();
        UUID itemId = UUID.randomUUID();
        execute(dispositionInsert(scope, itemId, "ARCHIVED", 3, NOW, LATER));

        // Reverse transitions the old rank guard rejected are now admitted at version + 1.
        execute(update(scope, itemId, "UNREAD", 4, LATEST));
        execute(update(scope, itemId, "READ", 5, LATEST));
        execute(update(scope, itemId, "UNREAD", 6, LATEST));
        assertEquals("UNREAD", textScalar(statusQuery(scope, itemId)));
        assertEquals(6, scalar(versionQuery(scope, itemId)));

        // The V27 protections that are not about monotonicity all survive.
        UUID otherItemId = UUID.randomUUID();
        execute(dispositionInsert(scope, otherItemId, "READ", 1, NOW, LATER));
        assertSqlState("23514", () -> execute("""
                DELETE FROM crewscope.inbox_disposition
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(scope.organizationId(), otherItemId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.inbox_disposition
                SET member_id = '%s'
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(UUID.randomUUID(), scope.organizationId(), otherItemId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.inbox_disposition
                SET created_at = %s
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(LATEST, scope.organizationId(), otherItemId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.inbox_disposition
                SET status = 'READ', version = 3
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(scope.organizationId(), otherItemId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.inbox_disposition
                SET status = 'ACTED', version = 2, updated_at = %s
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(NOW, scope.organizationId(), otherItemId)));

        // The CHECK still closes the value set and version floor.
        UUID freshItemId = UUID.randomUUID();
        assertSqlState("23514", () -> execute(
                dispositionInsert(scope, freshItemId, "DELETED", 1, NOW, LATER)));
        assertSqlState("23514", () -> execute(
                dispositionInsert(scope, freshItemId, "READ", 0, NOW, LATER)));
        assertFalse(exists(freshItemId));
    }

    private static void migrateLatest() {
        flyway(MigrationVersion.fromVersion("48")).migrate();
    }

    private static Scope seedScope() throws SQLException {
        Scope scope = new Scope(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V48 Org', 'ACTIVE')
                """.formatted(scope.organizationId()));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V48 Team', 'ACTIVE')
                """.formatted(scope.teamId(), scope.organizationId()));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V48 User', 'ACTIVE')
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

    private static String dispositionInsert(
            Scope scope, UUID itemId, String status, long version, String createdAt,
            String updatedAt) {
        return """
                INSERT INTO crewscope.inbox_disposition (
                    organization_id, team_id, member_id, inbox_item_id,
                    status, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', %d, %s, '%s', %s, '%s')
                """.formatted(
                scope.organizationId(), scope.teamId(), scope.memberId(), itemId,
                status, version, createdAt, scope.principalId(),
                updatedAt, scope.principalId());
    }

    private static String update(Scope scope, UUID itemId, String status, long version,
            String updatedAt) {
        return """
                UPDATE crewscope.inbox_disposition
                SET status = '%s', version = %d, updated_at = %s, updated_by_principal_id = '%s'
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(
                status, version, updatedAt, scope.principalId(),
                scope.organizationId(), itemId);
    }

    private static String statusQuery(Scope scope, UUID itemId) {
        return """
                SELECT status FROM crewscope.inbox_disposition
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(scope.organizationId(), itemId);
    }

    private static String versionQuery(Scope scope, UUID itemId) {
        return """
                SELECT version FROM crewscope.inbox_disposition
                WHERE organization_id = '%s' AND inbox_item_id = '%s'
                """.formatted(scope.organizationId(), itemId);
    }

    private static boolean exists(UUID itemId) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM crewscope.inbox_disposition
                WHERE inbox_item_id = '%s'
                """.formatted(itemId)) == 1;
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

    private static void execute(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
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

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException exception = assertThrows(SQLException.class, action::execute);
        assertEquals(expected, exception.getSQLState());
    }

    @FunctionalInterface
    private interface SqlAction {
        void execute() throws SQLException;
    }

    private record Scope(
            UUID organizationId, UUID teamId, UUID principalId, UUID memberId) {}
}
