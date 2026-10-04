package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Locks the V59 injection manifest shape (M10-I02b): the migration stays pure
 * PostgreSQL, the (execution_id, attempt) unique key is the durable idempotency
 * boundary, the four evidence columns refuse non-jsonb-array/object shapes, attempt
 * must be positive, and the composite task_execution foreign key RESTRICTs.
 */
class V59InjectionManifestMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_59 = MigrationVersion.fromVersion("59");
    private static final String NOW = "TIMESTAMPTZ '2026-10-04 09:00:00+00'";

    private record Scope(
            UUID organizationId, UUID teamId, UUID workspaceId, UUID projectId,
            UUID taskId, UUID executionId) {}

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void migratesAnEmptySchemaToV59WithoutAnyExtension() throws SQLException {
        Flyway target = flyway(VERSION_59);

        assertTrue(target.migrate().migrationsExecuted >= 59);
        target.validate();
        assertEquals("59", target.info().current().getVersion().getVersion());
        // The default chain stays pure PostgreSQL: no vector extension may appear.
        assertEquals(0L, longScalar(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'"));
    }

    @Test
    void locksTheFrozenRowContract() throws SQLException {
        flyway(VERSION_59).migrate();
        Scope scope = seedScope();

        assertSqlState("23514", () -> execute(manifestInsert(scope, 0)));
        // Shape locks: each evidence column refuses the wrong jsonb container type.
        assertSqlState("23514", () -> execute(manifestInsert(
                scope, 1, "{\"type\":\"WRONG\"}", TRIMS, BUDGET, DEGRADATIONS)));
        assertSqlState("23514", () -> execute(manifestInsert(
                scope, 1, SOURCES, "{}", BUDGET, DEGRADATIONS)));
        assertSqlState("23514", () -> execute(manifestInsert(
                scope, 1, SOURCES, TRIMS, "[]", DEGRADATIONS)));
        assertSqlState("23514", () -> execute(manifestInsert(
                scope, 1, SOURCES, TRIMS, BUDGET, "{}")));
        // A well-shaped row passes and keeps its evidence (jsonb re-orders keys and
        // spacing on output, so the proof checks content, not byte layout).
        execute(manifestInsert(scope, 1));
        String storedSources = textScalar("""
                SELECT source_references::text FROM crewscope.injection_manifest
                WHERE execution_id = '%s' AND attempt = 1
                """.formatted(scope.executionId()));
        assertTrue(storedSources.contains("KNOWLEDGE_ENTRY")
                && storedSources.contains("INJECTED")
                && storedSources.contains("a".repeat(64)));
    }

    @Test
    void oneManifestPerExecutionAttempt() throws SQLException {
        flyway(VERSION_59).migrate();
        Scope scope = seedScope();
        execute(manifestInsert(scope, 1));

        assertSqlState("23505", () -> execute(manifestInsert(scope, 1)),
                "the same (execution, attempt) may never seal twice");
        // A new attempt seals its own manifest next to the first one.
        execute(manifestInsert(scope, 2));
        assertEquals(2L, longScalar("""
                SELECT count(*) FROM crewscope.injection_manifest
                WHERE execution_id = '%s'
                """.formatted(scope.executionId())));
    }

    @Test
    void foreignKeysRestrictCleanupUntilTheManifestIsGone() throws SQLException {
        flyway(VERSION_59).migrate();
        Scope scope = seedScope();
        execute(manifestInsert(scope, 1));

        assertSqlState("23503", () -> execute("""
                DELETE FROM crewscope.task_execution WHERE id = '%s'
                """.formatted(scope.executionId())));
    }

    // ------------------------------------------------------------------ seeds and helpers

    private static final String SOURCES =
            "[{\"type\":\"KNOWLEDGE_ENTRY\",\"sourceId\":\"entry-1\",\"version\":3,"
                    + "\"contentHash\":\"%s\",\"stage\":\"INJECTED\"}]".formatted(
                    "a".repeat(64));
    private static final String TRIMS =
            "[{\"layer\":\"MEMORY_PREFERENCE\",\"trimmedCount\":1,"
                    + "\"reason\":\"layer budget exceeded\"}]";
    private static final String BUDGET =
            "{\"totalTokens\":8192,\"knowledgeTokens\":6,\"chunkTokens\":0,"
                    + "\"memoryTokens\":0}";
    private static final String DEGRADATIONS = "[\"RETRIEVAL_DISABLED\"]";

    /**
     * Seeds the M0-M3 parent chain down to one task_execution (the same chain the M4D09
     * coding persistence test seeds) and returns its six scope coordinates.
     */
    private Scope seedScope() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID responsibilitySnapshotId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        String responsibilityHash = "b".repeat(64);

        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V59 Org', 'ACTIVE')
                """.formatted(organizationId));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V59 Team', 'ACTIVE')
                """.formatted(teamId, organizationId));
        execute("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES ('%s', '%s', '%s', 'TEAM', 'V59 Workspace', 'ACTIVE')
                """.formatted(workspaceId, organizationId, teamId));
        execute("""
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 'V59 Project')
                """.formatted(projectId, organizationId, teamId, workspaceId,
                "V59-" + UUID.randomUUID().toString().substring(0, 6)));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V59 Creator', 'ACTIVE')
                """.formatted(actorId, organizationId));
        execute("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 'BOOTSTRAP',
                          CURRENT_TIMESTAMP)
                """.formatted(memberId, organizationId, teamId, actorId));
        execute("""
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'TASK',
                          'V59 work item', 'READY', 'MEDIUM')
                """.formatted(workItemId, organizationId, teamId, workspaceId, projectId,
                "V59-" + UUID.randomUUID().toString().substring(0, 6)));
        execute("""
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'EXECUTOR', '%s', 'USER',
                          '%s', 'ACTIVE', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          '%s', '%s')
                """.formatted(assignmentId, organizationId, teamId, workspaceId, projectId,
                workItemId, actorId, memberId, actorId, actorId, actorId));
        execute("""
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, '%s', '%s')
                """.formatted(responsibilitySnapshotId, organizationId, teamId, workspaceId,
                projectId, workItemId, responsibilityHash, actorId, actorId));
        execute("""
                INSERT INTO crewscope.task_responsibility_snapshot_entry (
                    snapshot_id, organization_id, team_id, workspace_id, project_id,
                    work_item_id, assignment_id, assignment_version, role,
                    principal_id, principal_type, member_id, assigned_at, accepted_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', 0, 'EXECUTOR',
                          '%s', 'USER', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(responsibilitySnapshotId, organizationId, teamId, workspaceId,
                projectId, workItemId, assignmentId, actorId, memberId));
        execute("""
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    objective, acceptance_criteria, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'WORK_ITEM', 0, '%s',
                          'Implement the endpoint', '["Tests pass"]'::jsonb, 'CREATED',
                          '%s', '%s')
                """.formatted(taskId, organizationId, teamId, workspaceId, projectId,
                workItemId, responsibilitySnapshotId, actorId, actorId));
        execute("""
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 1, 3, 50,
                          CURRENT_TIMESTAMP, 'CREATED', '%s', '%s')
                """.formatted(executionId, organizationId, teamId, workspaceId, projectId,
                taskId, actorId, actorId));
        return new Scope(organizationId, teamId, workspaceId, projectId, taskId, executionId);
    }

    private static String manifestInsert(Scope scope, int attempt) {
        return manifestInsert(scope, attempt, SOURCES, TRIMS, BUDGET, DEGRADATIONS);
    }

    private static String manifestInsert(
            Scope scope, int attempt, String sources, String trims, String budget,
            String degradations) {
        return """
                INSERT INTO crewscope.injection_manifest (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    execution_id, attempt, source_references, trims, budget, degradations,
                    created_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %d,
                          '%s'::jsonb, '%s'::jsonb, '%s'::jsonb, '%s'::jsonb, %s)
                """.formatted(UUID.randomUUID(), scope.organizationId(), scope.teamId(),
                scope.workspaceId(), scope.projectId(), scope.taskId(),
                scope.executionId(), attempt, sources, trims, budget, degradations, NOW);
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
            assertTrue(!result.next());
            return value;
        }
    }

    private static long longScalar(String sql) throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            long value = result.getLong(1);
            assertTrue(!result.next());
            return value;
        }
    }

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException exception = assertThrows(SQLException.class, action::execute);
        assertEquals(expected, exception.getSQLState());
    }

    private static void assertSqlState(String expected, SqlAction action, String message) {
        SQLException exception = assertThrows(SQLException.class, action::execute, message);
        assertEquals(expected, exception.getSQLState(), message);
    }

    @FunctionalInterface
    private interface SqlAction {
        void execute() throws SQLException;
    }
}
