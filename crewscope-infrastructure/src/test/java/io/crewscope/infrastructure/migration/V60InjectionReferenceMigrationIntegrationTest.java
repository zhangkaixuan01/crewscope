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
 * Locks the V60 reference evidence shape (M10-I02c): the migration stays pure
 * PostgreSQL, the feedback unique key (execution, source key, member) is the
 * structural idempotency boundary, the claimed receipt is unique per
 * (execution, attempt) with an array-only JSON column, scalar CHECKs refuse
 * non-positive versions, non-hash digests and unknown kinds, and both composite
 * task_execution foreign keys RESTRICT.
 */
class V60InjectionReferenceMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_60 = MigrationVersion.fromVersion("60");
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
    void migratesAnEmptySchemaToV60WithoutAnyExtension() throws SQLException {
        Flyway target = flyway(VERSION_60);

        assertTrue(target.migrate().migrationsExecuted >= 60);
        target.validate();
        assertEquals("60", target.info().current().getVersion().getVersion());
        // The default chain stays pure PostgreSQL: no vector extension may appear.
        assertEquals(0L, longScalar(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'"));
    }

    @Test
    void locksTheFrozenRowContract() throws SQLException {
        flyway(VERSION_60).migrate();
        Scope scope = seedScope();

        // Feedback CHECKs: version must be positive, the digest is 64 lowercase hex,
        // and the kind is a closed single-value set for now.
        assertSqlState("23514", () -> execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY",
                "entry-1", 0, "a".repeat(64), UUID.randomUUID())));
        assertSqlState("23514", () -> execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY",
                "entry-1", 3, "a".repeat(63), UUID.randomUUID())));
        assertSqlState("23514", () -> execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY",
                "entry-1", 3, "A".repeat(64), UUID.randomUUID())));
        assertSqlState("23514", () -> execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY",
                "entry-1", 3, "a".repeat(64), UUID.randomUUID(), "HELPFUL")));
        // Claimed CHECKs: attempt is positive and the column is an array.
        assertSqlState("23514", () -> execute(claimedInsert(scope, 0, CLAIMED)));
        assertSqlState("23514", () -> execute(claimedInsert(scope, 1, "{}")));
        // Well-shaped rows pass on both tables.
        UUID member = UUID.randomUUID();
        execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY", "entry-1", 3,
                "a".repeat(64), member));
        execute(claimedInsert(scope, 1, CLAIMED));
        assertEquals(1L, longScalar("""
                SELECT count(*) FROM crewscope.injection_reference_feedback
                WHERE execution_id = '%s' AND member_principal_id = '%s'
                """.formatted(scope.executionId(), member)));
        String storedClaimed = textScalar("""
                SELECT claimed::text FROM crewscope.injection_claimed_reference
                WHERE execution_id = '%s' AND attempt = 1
                """.formatted(scope.executionId()));
        assertTrue(storedClaimed.contains("KNOWLEDGE_ENTRY")
                && storedClaimed.contains("a".repeat(64)));
    }

    @Test
    void feedbackKeyIsTheStructuralIdempotencyBoundary() throws SQLException {
        flyway(VERSION_60).migrate();
        Scope scope = seedScope();
        UUID member = UUID.randomUUID();
        execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY", "entry-1", 3,
                "a".repeat(64), member));

        // The same (execution, source key, member) never writes a second row.
        assertSqlState("23505", () -> execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY",
                "entry-1", 3, "a".repeat(64), member)),
                "one feedback row per member and source key");
        // A different member, and a different source key, each open their own row.
        execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY", "entry-1", 3,
                "a".repeat(64), UUID.randomUUID()));
        execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY", "entry-1", 4,
                "a".repeat(64), member));
        assertEquals(3L, longScalar("""
                SELECT count(*) FROM crewscope.injection_reference_feedback
                WHERE execution_id = '%s'
                """.formatted(scope.executionId())));
    }

    @Test
    void claimedReceiptsAreUniquePerExecutionAttempt() throws SQLException {
        flyway(VERSION_60).migrate();
        Scope scope = seedScope();
        execute(claimedInsert(scope, 1, CLAIMED));

        assertSqlState("23505", () -> execute(claimedInsert(scope, 1, CLAIMED)),
                "the same (execution, attempt) may only claim once");
        // A new attempt files its own receipt next to the first one.
        execute(claimedInsert(scope, 2, "[]"));
        assertEquals(2L, longScalar("""
                SELECT count(*) FROM crewscope.injection_claimed_reference
                WHERE execution_id = '%s'
                """.formatted(scope.executionId())));
    }

    @Test
    void foreignKeysRestrictCleanupUntilTheEvidenceIsGone() throws SQLException {
        flyway(VERSION_60).migrate();
        Scope scope = seedScope();
        execute(feedbackInsert(scope, "KNOWLEDGE_ENTRY", "entry-1", 3,
                "a".repeat(64), UUID.randomUUID()));
        execute(claimedInsert(scope, 1, CLAIMED));

        assertSqlState("23503", () -> execute("""
                DELETE FROM crewscope.task_execution WHERE id = '%s'
                """.formatted(scope.executionId())));
    }

    // ------------------------------------------------------------------ seeds and helpers

    private static final String CLAIMED =
            "[{\"type\":\"KNOWLEDGE_ENTRY\",\"sourceId\":\"entry-1\",\"version\":3,"
                    + "\"contentHash\":\"%s\"}]".formatted("a".repeat(64));

    /**
     * Seeds the M0-M3 parent chain down to one task_execution (the same chain the V59
     * migration test seeds) and returns its six scope coordinates.
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
                VALUES ('%s', 'V60 Org', 'ACTIVE')
                """.formatted(organizationId));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V60 Team', 'ACTIVE')
                """.formatted(teamId, organizationId));
        execute("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES ('%s', '%s', '%s', 'TEAM', 'V60 Workspace', 'ACTIVE')
                """.formatted(workspaceId, organizationId, teamId));
        execute("""
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 'V60 Project')
                """.formatted(projectId, organizationId, teamId, workspaceId,
                "V60-" + UUID.randomUUID().toString().substring(0, 6)));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V60 Creator', 'ACTIVE')
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
                          'V60 work item', 'READY', 'MEDIUM')
                """.formatted(workItemId, organizationId, teamId, workspaceId, projectId,
                "V60-" + UUID.randomUUID().toString().substring(0, 6)));
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

    private static String feedbackInsert(
            Scope scope, String sourceType, String sourceId, long sourceVersion,
            String contentHash, UUID memberPrincipalId) {
        return feedbackInsert(scope, sourceType, sourceId, sourceVersion, contentHash,
                memberPrincipalId, "NOT_APPLICABLE");
    }

    private static String feedbackInsert(
            Scope scope, String sourceType, String sourceId, long sourceVersion,
            String contentHash, UUID memberPrincipalId, String feedbackKind) {
        return """
                INSERT INTO crewscope.injection_reference_feedback (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    execution_id, source_type, source_id, source_version,
                    source_content_hash, member_principal_id, feedback_kind, created_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', %d,
                          '%s', '%s', '%s', %s)
                """.formatted(UUID.randomUUID(), scope.organizationId(), scope.teamId(),
                scope.workspaceId(), scope.projectId(), scope.taskId(),
                scope.executionId(), sourceType, sourceId, sourceVersion, contentHash,
                memberPrincipalId, feedbackKind, NOW);
    }

    private static String claimedInsert(Scope scope, int attempt, String claimed) {
        return """
                INSERT INTO crewscope.injection_claimed_reference (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    execution_id, attempt, claimed, created_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %d, '%s'::jsonb, %s)
                """.formatted(UUID.randomUUID(), scope.organizationId(), scope.teamId(),
                scope.workspaceId(), scope.projectId(), scope.taskId(),
                scope.executionId(), attempt, claimed, NOW);
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
