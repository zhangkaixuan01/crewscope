package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V24 lossless Decision authority and rebuildable Review projection schema. */
class V24ReviewPersistenceProjectionMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_23 = MigrationVersion.fromVersion("23");
    private static final MigrationVersion VERSION_24 = MigrationVersion.fromVersion("24");

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void upgradesV23AndAddsLosslessEligibilityAndProjectionIndexes() throws SQLException {
        Flyway source = flyway(VERSION_23);
        source.migrate();
        assertEquals("23", source.info().current().getVersion().getVersion());

        Flyway target = flyway(VERSION_24);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("24", target.info().current().getVersion().getVersion());

        assertTrue(columns("review_decision").containsAll(Set.of(
                "eligibility_conflicting_roles",
                "eligibility_policy_pack_id",
                "eligibility_policy_pack_version",
                "eligibility_override_reason")));
        assertTrue(tables().contains("review_request_projection"));
        assertTrue(indexes().containsAll(Set.of(
                "ix_review_request_projection_task_history",
                "ix_review_request_projection_execution")));
    }

    @Test
    void acceptsStrictAndCompleteOverrideAuthorityButRejectsPartialOverride() throws SQLException {
        flyway(VERSION_24).migrate();
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        executeReplica(decisionSql(
                UUID.randomUUID(), organizationId, teamId, workspaceId, projectId,
                workItemId, taskId, executionId, requestId, actorId, memberId,
                "INDEPENDENT_MEMBER", "[]", null, null, null));
        executeReplica(decisionSql(
                UUID.randomUUID(), organizationId, teamId, workspaceId, projectId,
                workItemId, taskId, executionId, UUID.randomUUID(), actorId, memberId,
                "EXPLICIT_SELF_REVIEW_OVERRIDE", "[\"OWNER\"]",
                UUID.randomUUID(), 1L, "Single-member team override"));

        assertEquals(2, count("review_decision"));
        assertSqlState("23514", () -> executeReplica(decisionSql(
                UUID.randomUUID(), organizationId, teamId, workspaceId, projectId,
                workItemId, taskId, executionId, UUID.randomUUID(), actorId, memberId,
                "EXPLICIT_SELF_REVIEW_OVERRIDE", "[]", null, null, null)));
    }

    private static String decisionSql(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            UUID taskId,
            UUID executionId,
            UUID requestId,
            UUID actorId,
            UUID memberId,
            String mode,
            String conflicts,
            UUID policyId,
            Long policyVersion,
            String overrideReason) {
        String policy = policyId == null ? "NULL" : "'" + policyId + "'";
        String version = policyVersion == null ? "NULL" : policyVersion.toString();
        String reason = overrideReason == null
                ? "NULL"
                : "'" + overrideReason.replace("'", "''") + "'";
        return """
                INSERT INTO crewscope.review_decision (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    revision, reviewer_mode, reviewer_principal_id, reviewer_member_id,
                    eligibility_mode, eligibility_reason, eligibility_conflicting_roles,
                    eligibility_policy_pack_id, eligibility_policy_pack_version,
                    eligibility_override_reason, decision_type, rationale, decision_hash,
                    created_at, created_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 1, '%s',
                    1, 0, '%s', 1, 'GATE', '%s', '%s', '%s', 'migration test',
                    '%s'::JSONB, %s, %s, %s, 'COMMENTED', 'safe rationale', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:00+00', '%s')
                """.formatted(
                id, organizationId, teamId, workspaceId, projectId, workItemId,
                taskId, executionId, requestId, "a".repeat(64), actorId, memberId,
                mode, conflicts, policy, version, reason, "b".repeat(64), actorId);
    }

    private static void executeReplica(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                statement.executeUpdate(sql);
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
    }

    private static void assertSqlState(String state, SqlAction action) {
        try {
            action.run();
            throw new AssertionError("Expected SQLSTATE " + state);
        } catch (SQLException exception) {
            assertEquals(state, exception.getSQLState());
        }
    }

    private static int count(String table) throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM crewscope." + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static Set<String> columns(String table) throws SQLException {
        return strings("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'crewscope' AND table_name = '" + table + "'");
    }

    private static Set<String> tables() throws SQLException {
        return strings("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'crewscope'");
    }

    private static Set<String> indexes() throws SQLException {
        return strings("SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'");
    }

    private static Set<String> strings(String sql) throws SQLException {
        java.util.HashSet<String> values = new java.util.HashSet<>();
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return Set.copyOf(values);
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

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
