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
 * Locks the V53 category taxonomy: pre-V53 rows backfill to OTHER, the CHECK closes the
 * value set at the database boundary, and the default keeps applying to new rows that
 * omit the column.
 */
class V53KnowledgeCategoryMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_52 = MigrationVersion.fromVersion("52");
    private static final String NOW = "TIMESTAMPTZ '2026-10-01 09:00:00+00'";

    private record Scope(UUID organizationId, UUID teamId, UUID principalId) {}

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void migratesAnEmptySchemaToV53() {
        Flyway target = flyway(MigrationVersion.fromVersion("53"));

        assertTrue(target.migrate().migrationsExecuted >= 53);
        target.validate();
        assertEquals("53", target.info().current().getVersion().getVersion());
    }

    @Test
    void upgradesV52RowsToOtherAndKeepsAcceptingExplicitCategories() throws SQLException {
        flyway(VERSION_52).migrate();
        Scope scope = seedScope();
        UUID entryId = UUID.randomUUID();
        execute(entryInsertV52(scope, entryId));

        Flyway target = flyway(MigrationVersion.fromVersion("53"));
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("OTHER", textScalar(categoryQuery(scope, entryId)));

        // A V53 writer may classify explicitly, and the default still applies on omission.
        UUID classified = UUID.randomUUID();
        execute(entryInsert(scope, classified, "'RUNBOOK'"));
        assertEquals("RUNBOOK", textScalar(categoryQuery(scope, classified)));
        UUID defaulted = UUID.randomUUID();
        execute(entryInsert(scope, defaulted, "DEFAULT"));
        assertEquals("OTHER", textScalar(categoryQuery(scope, defaulted)));
    }

    @Test
    void rejectsCategoriesOutsideTheFrozenTaxonomy() throws SQLException {
        flyway(MigrationVersion.fromVersion("53")).migrate();
        Scope scope = seedScope();

        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'MYSTERY'")));
        UUID legal = UUID.randomUUID();
        execute(entryInsert(scope, legal, "'GUIDE'"));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.knowledge_entry SET category = 'runbook'
                WHERE organization_id = '%s' AND id = '%s'
                """.formatted(scope.organizationId(), legal)));
    }

    private static Scope seedScope() throws SQLException {
        Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V53 Org', 'ACTIVE')
                """.formatted(scope.organizationId()));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V53 Team', 'ACTIVE')
                """.formatted(scope.teamId(), scope.organizationId()));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V53 User', 'ACTIVE')
                """.formatted(scope.principalId(), scope.organizationId()));
        return scope;
    }

    /** A bare V52-shaped insert; the row predates the category column entirely. */
    private static String entryInsertV52(Scope scope, UUID entryId) {
        return """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', 'v53-entry-%s', 'DRAFT', 0, 0, %s, '%s', %s, '%s')
                """.formatted(
                entryId, scope.organizationId(), scope.teamId(),
                entryId.toString().substring(0, 8),
                NOW, scope.principalId(), NOW, scope.principalId());
    }

    /** A V53-shaped insert; {@code category} is a SQL literal or the DEFAULT keyword. */
    private static String entryInsert(Scope scope, UUID entryId, String category) {
        return """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, category, status,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', 'v53-entry-%s', %s, 'DRAFT', 0, 0, %s, '%s', %s, '%s')
                """.formatted(
                entryId, scope.organizationId(), scope.teamId(),
                entryId.toString().substring(0, 8),
                category,
                NOW, scope.principalId(), NOW, scope.principalId());
    }

    private static String categoryQuery(Scope scope, UUID entryId) {
        return """
                SELECT category FROM crewscope.knowledge_entry
                WHERE organization_id = '%s' AND id = '%s'
                """.formatted(scope.organizationId(), entryId);
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
}
