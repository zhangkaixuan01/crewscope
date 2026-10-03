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
 * Locks the V54 origin pair: pre-V54 rows upgrade to fully NULL, the CHECK closes the
 * same-present/same-absent shape with attempt >= 1 at the database boundary, and the
 * partial index answers execution lookups without widening the hot team listing.
 */
class V54KnowledgeOriginMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_53 = MigrationVersion.fromVersion("53");
    private static final String NOW = "TIMESTAMPTZ '2026-10-02 09:00:00+00'";

    private record Scope(UUID organizationId, UUID teamId, UUID principalId) {}

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void migratesAnEmptySchemaToV54() {
        Flyway target = flyway(MigrationVersion.fromVersion("54"));

        assertTrue(target.migrate().migrationsExecuted >= 54);
        target.validate();
        assertEquals("54", target.info().current().getVersion().getVersion());
    }

    @Test
    void upgradesV53RowsToANullOriginAndKeepsAcceptingFullPairs() throws SQLException {
        flyway(VERSION_53).migrate();
        Scope scope = seedScope();
        UUID legacy = UUID.randomUUID();
        execute(entryInsertV53(scope, legacy));

        Flyway target = flyway(MigrationVersion.fromVersion("54"));
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();
        assertTrue(textScalar(executionQuery(scope, legacy)).isEmpty());
        assertTrue(textScalar(attemptQuery(scope, legacy)).isEmpty());

        // A V54 writer stores the pair fully present and can query it back through the
        // partial index predicate (execution id present).
        UUID distilled = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        execute(entryInsert(scope, distilled,
                "'" + executionId + "'", "3"));
        assertEquals(executionId.toString(), textScalar(executionQuery(scope, distilled)));
        assertEquals("3", textScalar(attemptQuery(scope, distilled)));
    }

    @Test
    void rejectsHalfPairsAndNonPositiveAttempts() throws SQLException {
        flyway(MigrationVersion.fromVersion("54")).migrate();
        Scope scope = seedScope();
        UUID executionId = UUID.randomUUID();

        // Attempt without an execution id.
        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "NULL", "1")));
        // Execution id without an attempt.
        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'" + executionId + "'", "NULL")));
        // The attempt counts from one; zero is not a real execution attempt.
        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'" + executionId + "'", "0")));
        // The pair is immutable in shape only via inserts here: updates into a half pair
        // are equally rejected by the same CHECK.
        UUID legal = UUID.randomUUID();
        execute(entryInsert(scope, legal, "'" + executionId + "'", "2"));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.knowledge_entry SET source_execution_attempt = NULL
                WHERE organization_id = '%s' AND id = '%s'
                """.formatted(scope.organizationId(), legal)));
    }

    @Test
    void createsTheExecutionLookupAsAPartialIndex() throws SQLException {
        flyway(MigrationVersion.fromVersion("54")).migrate();

        String definition = textScalar("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'crewscope' AND indexname = 'ix_knowledge_entry_source_execution'
                """);
        assertTrue(definition.contains("ON crewscope.knowledge_entry"), definition);
        // PostgreSQL renders the predicate wrapped in parentheses; match on the bare clause.
        assertTrue(definition.contains("source_task_execution_id IS NOT NULL"), definition);
        assertFalse(definition.contains("CREATE UNIQUE"), definition);
    }

    private static Scope seedScope() throws SQLException {
        Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V54 Org', 'ACTIVE')
                """.formatted(scope.organizationId()));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V54 Team', 'ACTIVE')
                """.formatted(scope.teamId(), scope.organizationId()));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V54 User', 'ACTIVE')
                """.formatted(scope.principalId(), scope.organizationId()));
        return scope;
    }

    /** A bare V53-shaped insert; the row predates the origin columns entirely. */
    private static String entryInsertV53(Scope scope, UUID entryId) {
        return """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, category, status,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', 'v54-entry-%s', 'RUNBOOK', 'DRAFT',
                    0, 0, %s, '%s', %s, '%s')
                """.formatted(
                entryId, scope.organizationId(), scope.teamId(),
                entryId.toString().substring(0, 8),
                NOW, scope.principalId(), NOW, scope.principalId());
    }

    /** {@code execution} and {@code attempt} are SQL literals or the NULL keyword. */
    private static String entryInsert(
            Scope scope, UUID entryId, String execution, String attempt) {
        return """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, category, status,
                    source_task_execution_id, source_execution_attempt,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', 'v54-entry-%s', 'RUNBOOK', 'DRAFT',
                    %s, %s, 0, 0, %s, '%s', %s, '%s')
                """.formatted(
                entryId, scope.organizationId(), scope.teamId(),
                entryId.toString().substring(0, 8),
                execution, attempt,
                NOW, scope.principalId(), NOW, scope.principalId());
    }

    private static String executionQuery(Scope scope, UUID entryId) {
        return """
                SELECT COALESCE(source_task_execution_id::text, '')
                FROM crewscope.knowledge_entry
                WHERE organization_id = '%s' AND id = '%s'
                """.formatted(scope.organizationId(), entryId);
    }

    private static String attemptQuery(Scope scope, UUID entryId) {
        return """
                SELECT COALESCE(source_execution_attempt::text, '')
                FROM crewscope.knowledge_entry
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
