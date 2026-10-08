package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks down the V66 coding-specialist TASK session contract at the database level: the shape
 * check's TASK branch must accept the SPECIALIST identity pair — the insert the first real
 * coding execution died on — while every V51 branch survives verbatim. Domain-level tests
 * cannot see these CHECKs, which is exactly how the same seam slipped through twice.
 */
class V66CodingSpecialistTaskSessionMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_66 = MigrationVersion.fromVersion("66");
    private static final String HASH_A = "a".repeat(64);

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void migratesAnEmptySchemaToV66() {
        Flyway target = flyway(VERSION_66);

        assertTrue(target.migrate().migrationsExecuted >= 66);
        target.validate();
        assertEquals("66", target.info().current().getVersion().getVersion());
    }

    @Test
    void admitsACodingSpecialistTaskSessionBesideTheTeamExecutorShape() throws SQLException {
        flyway(VERSION_66).migrate();
        Facts facts = seedFacts();

        execute(sessionInsertSql(), sessionValues(facts,
                "SPECIALIST_AGENT", "SPECIALIST", facts.specialistPrincipalId(),
                facts.specialistProfileId(), UUID.randomUUID()));
        execute(sessionInsertSql(), sessionValues(facts,
                "TEAM_AGENT", "TEAM", facts.teamAgentPrincipalId(),
                facts.teamAgentProfileId(), UUID.randomUUID()));

        assertEquals(2, queryInt(
                "SELECT COUNT(*) FROM crewscope.agent_runtime_session"
                        + " WHERE task_execution_id = ? AND session_purpose = 'TASK'",
                facts.executionId()));
    }

    @Test
    void rejectsMismatchedTaskIdentityPairs() throws SQLException {
        flyway(VERSION_66).migrate();
        Facts facts = seedFacts();

        // The TASK branch widened by identity pair, not by shape: mismatched pairs stay out.
        assertSqlState("23514", sessionInsertSql(), sessionValues(facts,
                "TEAM_AGENT", "SPECIALIST", facts.teamAgentPrincipalId(),
                facts.specialistProfileId(), UUID.randomUUID()));
        assertSqlState("23514", sessionInsertSql(), sessionValues(facts,
                "SPECIALIST_AGENT", "TEAM", facts.specialistPrincipalId(),
                facts.teamAgentProfileId(), UUID.randomUUID()));
    }

    /** One TASK-shaped session row; only the identity-varying columns are parameters. */
    private static Object[] sessionValues(
            Facts facts,
            String principalType,
            String profileType,
            UUID principalId,
            UUID profileId,
            UUID id) {
        return new Object[] {
            id,
            facts.organizationId(), facts.teamId(), facts.workspaceId(), null,
            "TASK", facts.projectId(), facts.taskId(), facts.executionId(), null,
            principalId, principalType,
            profileId, profileType,
            "crewscope:v1:user:" + principalId,
            "crewscope:v1:session:" + id,
            "crewscope:agent-state:v1:" + id,
            facts.userPrincipalId(), facts.userPrincipalId()
        };
    }

    private static String sessionInsertSql() {
        return """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    session_purpose, project_id, task_id, task_execution_id, step_execution_id,
                    agent_principal_id, agent_principal_type,
                    agent_profile_id, agent_profile_type, agent_profile_version,
                    agent_scope_user_id, agent_scope_session_id, state_reference,
                    status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'ACTIVE', ?, ?)
                """;
    }

    /** Seeds one open task with an attempt — the coordinates a TASK session pins. */
    private static Facts seedFacts() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userPrincipalId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID teamAgentPrincipalId = UUID.randomUUID();
        UUID teamAgentProfileId = UUID.randomUUID();
        UUID specialistPrincipalId = UUID.randomUUID();
        UUID specialistProfileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID ownerAssignmentId = UUID.randomUUID();
        UUID executorAssignmentId = UUID.randomUUID();

        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, 'Team', 'ACTIVE')",
                teamId, organizationId);
        execute(
                """
                INSERT INTO crewscope.workspace (id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId, teamId);
        execute(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Owner', 'ACTIVE')
                """,
                userPrincipalId, organizationId);
        execute(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                memberId, organizationId, teamId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    owner_principal_id, display_name, visibility, status
                ) VALUES (?, ?, ?, 'TEAM_AGENT', ?, 'Team Agent', 'TEAM', 'ACTIVE')
                """,
                teamAgentPrincipalId, organizationId, teamId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    owner_principal_id, display_name, visibility, status
                ) VALUES (?, ?, ?, 'SPECIALIST_AGENT', ?, 'Coding Specialist', 'TEAM', 'ACTIVE')
                """,
                specialistPrincipalId, organizationId, teamId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, owner_member_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, NULL, 'TEAM', FALSE, 'ACTIVE', ?, ?)
                """,
                teamAgentProfileId, organizationId, teamId, workspaceId, teamAgentPrincipalId,
                userPrincipalId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, owner_member_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, NULL, 'SPECIALIST', FALSE, 'ACTIVE', ?, ?)
                """,
                specialistProfileId, organizationId, teamId, workspaceId, specialistPrincipalId,
                userPrincipalId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'v66', 'Project', ?, ?)
                """,
                projectId, organizationId, teamId, workspaceId, userPrincipalId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'v66-1', 'TASK', 'Item', 'BACKLOG', 'MEDIUM', ?, ?)
                """,
                workItemId, organizationId, teamId, workspaceId, projectId,
                userPrincipalId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                ownerAssignmentId, organizationId, teamId, workspaceId, projectId, workItemId,
                "OWNER", userPrincipalId, "USER", memberId, userPrincipalId,
                userPrincipalId, userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'ACTIVE', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                executorAssignmentId, organizationId, teamId, workspaceId, projectId, workItemId,
                "EXECUTOR", specialistPrincipalId, "SPECIALIST_AGENT", userPrincipalId,
                userPrincipalId, userPrincipalId);

        UUID snapshotId = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                snapshotId, organizationId, teamId, workspaceId, projectId, workItemId,
                HASH_A, userPrincipalId, userPrincipalId);
        insertSnapshotEntry(snapshotId, organizationId, teamId, workspaceId, projectId,
                workItemId, ownerAssignmentId, "OWNER", userPrincipalId, "USER", memberId);
        insertSnapshotEntry(snapshotId, organizationId, teamId, workspaceId, projectId,
                workItemId, executorAssignmentId, "EXECUTOR", specialistPrincipalId,
                "SPECIALIST_AGENT", null);

        UUID taskId = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    status, objective, acceptance_criteria,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'Objective',
                          CAST('[]' AS jsonb), ?, ?)
                """,
                taskId, organizationId, teamId, workspaceId, projectId, workItemId, snapshotId,
                userPrincipalId, userPrincipalId);
        UUID executionId = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, parent_execution_id, priority, not_before, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 3, NULL, 100, CURRENT_TIMESTAMP, 'CREATED', ?, ?)
                """,
                executionId, organizationId, teamId, workspaceId, projectId, taskId,
                userPrincipalId, userPrincipalId);

        return new Facts(organizationId, teamId, workspaceId, userPrincipalId, memberId,
                teamAgentPrincipalId, teamAgentProfileId, specialistPrincipalId,
                specialistProfileId, projectId, workItemId, taskId, executionId);
    }

    private static void insertSnapshotEntry(
            UUID snapshotId,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            UUID assignmentId,
            String role,
            UUID principalId,
            String principalType,
            UUID memberId)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.task_responsibility_snapshot_entry (
                    snapshot_id, organization_id, team_id, workspace_id, project_id,
                    work_item_id, assignment_id, assignment_version, role,
                    principal_id, principal_type, member_id, assigned_at, accepted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                snapshotId, organizationId, teamId, workspaceId, projectId, workItemId,
                assignmentId, role, principalId, principalType, memberId);
    }

    private static Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
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

    private static Connection openConnection(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int execute(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static void assertSqlState(String state, String sql, Object... values) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql, values));
        assertEquals(state, exception.getSQLState());
    }

    private static int queryInt(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private record Facts(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID userPrincipalId,
            UUID memberId,
            UUID teamAgentPrincipalId,
            UUID teamAgentProfileId,
            UUID specialistPrincipalId,
            UUID specialistProfileId,
            UUID projectId,
            UUID workItemId,
            UUID taskId,
            UUID executionId) {}
}
