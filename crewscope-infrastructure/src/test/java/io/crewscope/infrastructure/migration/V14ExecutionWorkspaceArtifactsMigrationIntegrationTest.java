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
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V14 Coding workspace, policy, artifact, evidence and checkpoint schema. */
class V14ExecutionWorkspaceArtifactsMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_14 = MigrationVersion.fromVersion("14");
    private static final String ALTERNATE_SCHEMA = "v14_probe";
    private static final Set<String> V14_TABLES = Set.of(
            "repository_binding",
            "coding_target_snapshot",
            "execution_workspace",
            "workspace_policy",
            "workspace_policy_overlay",
            "diff_artifact",
            "diff_file_entry",
            "command_evidence",
            "test_evidence",
            "test_evidence_command",
            "test_acceptance_result",
            "test_acceptance_evidence",
            "coding_checkpoint");

    @BeforeEach
    void resetDatabase() throws SQLException {
        resetSchemas();
    }

    @Test
    void createsCompleteCodingSchemaWithLayeredAuditAndNoHostPaths() throws SQLException {
        Flyway flyway = flyway(POSTGRES.getJdbcUrl(), VERSION_14);
        flyway.migrate();

        assertEquals("14", flyway.info().current().getVersion().getVersion());
        Set<String> tables = queryStrings(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'crewscope'");
        assertTrue(tables.containsAll(V14_TABLES));

        // Mutable business roots retain optimistic version and both sides of the audit trail.
        for (String table : Set.of("repository_binding", "execution_workspace")) {
            assertTrue(columns(table).containsAll(Set.of(
                    "version", "created_at", "created_by_principal_id",
                    "updated_at", "updated_by_principal_id")), table);
        }
        assertTrue(columns("workspace_policy_overlay").containsAll(Set.of(
                "overlay_version", "created_at", "created_by_principal_id",
                "updated_at", "updated_by_principal_id")));

        // Immutable authority facts record their producer once and cannot imply later mutation.
        for (String table : Set.of(
                "coding_target_snapshot", "workspace_policy", "diff_artifact",
                "command_evidence", "test_evidence", "coding_checkpoint")) {
            Set<String> names = columns(table);
            assertTrue(names.containsAll(Set.of("created_at", "created_by_principal_id")), table);
            assertFalse(names.contains("updated_at"), table);
            assertFalse(names.contains("updated_by_principal_id"), table);
            assertFalse(names.contains("deleted"), table);
            assertFalse(names.contains("deleted_at"), table);
        }

        Set<String> workspaceColumns = columns("execution_workspace");
        assertFalse(workspaceColumns.contains("host_path"));
        assertFalse(workspaceColumns.contains("repository_path"));
        assertFalse(workspaceColumns.contains("worktree_path"));
        assertTrue(workspaceColumns.containsAll(Set.of(
                "workspace_key", "managed_branch", "archive_reference",
                "workspace_fingerprint", "fencing_token")));
        assertTrue(columns("test_evidence").containsAll(Set.of(
                "diff_generation", "diff_manifest_hash")));
        assertTrue(columns("coding_checkpoint").containsAll(Set.of(
                "diff_generation", "diff_manifest_hash", "checkpoint_hash")));
    }

    @Test
    void createsScopeConstraintsConflictKeysAndQueryIndexes() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_14).migrate();

        Set<String> constraints = queryStrings(
                """
                SELECT conname
                FROM pg_constraint
                WHERE connamespace = 'crewscope'::regnamespace
                """);
        assertTrue(constraints.containsAll(Set.of(
                "uk_repository_binding_project_key",
                "uk_coding_target_snapshot_revision",
                "uk_execution_workspace_attempt",
                "uk_diff_artifact_workspace",
                "uk_command_evidence_workspace_sequence",
                "uk_test_evidence_workspace_sequence",
                "uk_coding_checkpoint_workspace_sequence",
                "fk_execution_workspace_owner",
                "fk_command_evidence_workspace",
                "fk_test_evidence_workspace",
                "fk_coding_checkpoint_snapshot",
                "fk_coding_checkpoint_evidence")));

        Set<String> indexes = queryStrings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'");
        assertTrue(indexes.containsAll(Set.of(
                "ix_repository_binding_project_status",
                "ix_coding_target_snapshot_task_revision",
                "ix_execution_workspace_task_execution",
                "ix_execution_workspace_worker_status",
                "ix_execution_workspace_retention",
                "ix_workspace_policy_task_execution",
                "ix_workspace_policy_overlay_current",
                "ix_diff_artifact_task_execution",
                "ix_command_evidence_task_execution",
                "ix_test_evidence_task_execution",
                "ix_coding_checkpoint_recovery",
                "ix_coding_checkpoint_run")));

        String artifactKinds = queryString(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE connamespace = 'crewscope'::regnamespace
                  AND conname = 'ck_runtime_artifact_kind'
                """);
        assertTrue(artifactKinds.contains("DIFF_PATCH"));
        assertTrue(artifactKinds.contains("COMMAND_LOG"));
        assertTrue(artifactKinds.contains("TEST_REPORT"));
    }

    @Test
    void upgradesV9V10AndV13ToV14() throws SQLException {
        for (int sourceVersion : new int[] {9, 10, 13}) {
            resetSchemas();
            Flyway source = flyway(
                    POSTGRES.getJdbcUrl(), MigrationVersion.fromVersion(Integer.toString(sourceVersion)));
            source.migrate();
            assertEquals(Integer.toString(sourceVersion),
                    source.info().current().getVersion().getVersion());

            Flyway target = flyway(POSTGRES.getJdbcUrl(), VERSION_14);
            assertEquals(14 - sourceVersion, target.migrate().migrationsExecuted);
            assertEquals("14", target.info().current().getVersion().getVersion());
            target.validate();
            assertEquals(V14_TABLES.size(), v14TableCount("crewscope"));
        }
    }

    @Test
    void migratesV14IntoCrewscopeWithNonDefaultSearchPath() throws SQLException {
        String jdbcUrl = jdbcUrlWithCurrentSchema(ALTERNATE_SCHEMA);
        assertEquals(ALTERNATE_SCHEMA, currentSchema(jdbcUrl));

        Flyway flyway = flyway(jdbcUrl, VERSION_14);
        flyway.migrate();

        assertEquals("14", flyway.info().current().getVersion().getVersion());
        assertEquals(V14_TABLES.size(), v14TableCount("crewscope"));
        assertEquals(0, v14TableCount(ALTERNATE_SCHEMA));
        assertEquals(0, tableCount(ALTERNATE_SCHEMA, "flyway_schema_history"));
    }

    @Test
    void rejectsDuplicateRepositoryKeyInvalidStateAndCrossScopeProject() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_14).migrate();
        ScopeFixture fixture = seedScope();

        insertRepositoryBinding(fixture, UUID.randomUUID(), "platform", "ACTIVE");
        assertSqlState("23505", () ->
                insertRepositoryBinding(fixture, UUID.randomUUID(), "platform", "ACTIVE"));
        assertSqlState("23514", () ->
                insertRepositoryBinding(fixture, UUID.randomUUID(), "invalid-state", "ARCHIVED"));

        ScopeFixture wrongWorkspace = new ScopeFixture(
                fixture.organizationId(), fixture.teamId(), UUID.randomUUID(),
                fixture.projectId(), fixture.actorId());
        assertSqlState("23503", () ->
                insertRepositoryBinding(wrongWorkspace, UUID.randomUUID(), "cross-scope", "ACTIVE"));
    }

    private static ScopeFixture seedScope() throws SQLException {
        ScopeFixture fixture = new ScopeFixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                fixture.organizationId());
        execute(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Team', 'ACTIVE')",
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
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id,
                    project_key, name, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'V14', 'Project', ?, ?)
                """,
                fixture.projectId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.actorId(), fixture.actorId());
        return fixture;
    }

    private static void insertRepositoryBinding(
            ScopeFixture fixture, UUID bindingId, String repositoryKey, String status)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.repository_binding (
                    id, organization_id, team_id, workspace_id, project_id,
                    repository_kind, repository_key, default_branch, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL_MANAGED', ?, 'main', ?, ?, ?)
                """,
                bindingId, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), repositoryKey, status, fixture.actorId(), fixture.actorId());
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

    private static int v14TableCount(String schema) throws SQLException {
        String placeholders = String.join(",", V14_TABLES.stream().map(value -> "?").toList());
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name IN (" + placeholders + ")";
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            int index = 2;
            for (String table : V14_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
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
        Set<String> values = new java.util.HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return Set.copyOf(values);
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
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

    private record ScopeFixture(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID actorId) {}
}
