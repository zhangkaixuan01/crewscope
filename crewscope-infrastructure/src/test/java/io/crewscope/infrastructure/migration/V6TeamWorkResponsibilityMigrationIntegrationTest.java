package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V6 Team, WorkItem child-resource and responsibility persistence contract. */
class V6TeamWorkResponsibilityMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_5 = MigrationVersion.fromVersion("5");
    private static final MigrationVersion VERSION_6 = MigrationVersion.fromVersion("6");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void createsTheM1TablesColumnsConstraintsAndIndexesFromAnEmptyDatabase()
            throws SQLException {
        Flyway flyway = flyway(VERSION_6);
        flyway.migrate();

        assertEquals("6", flyway.info().current().getVersion().getVersion());
        Set<String> tables = queryStrings(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'crewscope'
                """);
        assertTrue(tables.containsAll(Set.of(
                "agent_profile",
                "work_item_comment",
                "work_item_resource_link",
                "responsibility_assignment")));

        Set<String> teamColumns = columns("team");
        assertTrue(teamColumns.containsAll(Set.of("owner_member_id", "default_workspace_id")));
        Set<String> workItemColumns = columns("work_item");
        assertTrue(workItemColumns.containsAll(Set.of("labels", "due_at")));
        assertTrue(columns("work_project").contains("status"));

        Set<String> constraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name IN (
                    'team', 'agent_profile', 'work_item',
                    'work_item_comment', 'work_item_resource_link',
                    'responsibility_assignment'
                  )
                """);
        assertTrue(constraints.containsAll(Set.of(
                "fk_team_owner_member",
                "fk_team_default_workspace",
                "fk_agent_profile_workspace",
                "ck_work_item_labels",
                "fk_work_item_comment_work_item",
                "fk_work_item_resource_link_work_item",
                "fk_responsibility_assignment_work_item",
                "fk_responsibility_assignment_actor_member",
                "ck_responsibility_assignment_release")));
        assertEquals(
                "YES",
                queryString(
                        """
                        SELECT is_deferrable
                        FROM information_schema.table_constraints
                        WHERE constraint_schema = 'crewscope'
                          AND table_name = 'team'
                          AND constraint_name = 'fk_team_owner_member'
                        """));
        assertEquals(
                "YES",
                queryString(
                        """
                        SELECT initially_deferred
                        FROM information_schema.table_constraints
                        WHERE constraint_schema = 'crewscope'
                          AND table_name = 'team'
                          AND constraint_name = 'fk_team_default_workspace'
                        """));

        Set<String> indexes = queryStrings(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'crewscope'
                """);
        assertTrue(indexes.containsAll(Set.of(
                "ux_agent_profile_active_default_personal",
                "ix_work_item_labels",
                "ux_work_item_comment_external",
                "ux_responsibility_assignment_active_owner",
                "ux_responsibility_assignment_active_role_actor",
                "ix_responsibility_assignment_subject_role_status",
                "ix_responsibility_assignment_actor_status")));
    }

    @Test
    void upgradesV5RowsWithoutInventingAnOwnerAndBackfillsM1Defaults() throws SQLException {
        Flyway versionFive = flyway(VERSION_5);
        versionFive.migrate();
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Team', 'ACTIVE')
                """,
                teamId,
                organizationId);
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId);
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, 'CRW', 'Project')
                """,
                projectId,
                organizationId,
                teamId,
                workspaceId);
        execute(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) VALUES (?, ?, ?, ?, ?, 'CRW-1', 'TASK', 'Existing item', 'BACKLOG', 'MEDIUM')
                """,
                workItemId,
                organizationId,
                teamId,
                workspaceId,
                projectId);

        Flyway versionSix = flyway(VERSION_6);
        assertEquals(1, versionSix.migrate().migrationsExecuted);

        assertEquals(
                "ACTIVE",
                queryString("SELECT status FROM crewscope.work_project WHERE id = ?", projectId));
        assertEquals(
                "[]",
                queryString("SELECT labels::TEXT FROM crewscope.work_item WHERE id = ?", workItemId));
        assertNull(queryNullableString(
                "SELECT due_at::TEXT FROM crewscope.work_item WHERE id = ?", workItemId));
        assertNull(queryNullableString(
                "SELECT owner_member_id::TEXT FROM crewscope.team WHERE id = ?", teamId));
        assertNull(queryNullableString(
                "SELECT default_workspace_id::TEXT FROM crewscope.team WHERE id = ?", teamId));
        assertEquals(
                "false",
                queryString(
                        """
                        SELECT convalidated::TEXT
                        FROM pg_constraint
                        WHERE conname = 'ck_work_item_type_values'
                        """));
    }

    @Test
    void enforcesDeferredTeamReferencesAndUniqueDefaultPersonalProfile()
            throws SQLException {
        flyway(VERSION_6).migrate();
        Fixture fixture = seedFixture("CRW");
        execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, owner_member_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'PERSONAL', TRUE, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.agentPrincipalId(),
                fixture.memberId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());

        UUID secondAgentId = UUID.randomUUID();
        insertPersonalAgent(fixture, secondAgentId, "Second Personal Agent");
        assertSqlState(
                "23505",
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, owner_member_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'PERSONAL', TRUE, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                secondAgentId,
                fixture.memberId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertSqlState(
                "23514",
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', TRUE, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                secondAgentId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());

        assertThrows(
                SQLException.class,
                () -> updateTeamReferences(
                        fixture.organizationId(),
                        fixture.teamId(),
                        fixture.memberId(),
                        UUID.randomUUID()));
        assertEquals(
                fixture.workspaceId().toString(),
                queryString(
                        "SELECT default_workspace_id::TEXT FROM crewscope.team WHERE id = ?",
                        fixture.teamId()));
    }

    @Test
    void enforcesWorkItemChildrenActorQualificationAndActiveResponsibilityUniqueness()
            throws SQLException {
        flyway(VERSION_6).migrate();
        Fixture fixture = seedFixture("CRW");
        UUID secondUserId = UUID.randomUUID();
        UUID secondMemberId = UUID.randomUUID();
        insertUserAndMember(fixture, secondUserId, secondMemberId, "Second User");

        execute(
                """
                INSERT INTO crewscope.work_item_comment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    author_principal_id, content, source_provider,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'Native comment', 'CREWSCOPE', ?, ?)
                """,
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertSqlState(
                "23514",
                """
                INSERT INTO crewscope.work_item_comment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    author_principal_id, content, source_provider,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'External comment', 'JIRA', ?, ?)
                """,
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertSqlState(
                "23503",
                """
                INSERT INTO crewscope.work_item_resource_link (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    resource_type, resource_reference,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'PULL_REQUEST', 'github:pull:1', ?, ?)
                """,
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                UUID.randomUUID(),
                fixture.projectId(),
                fixture.workItemId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());

        UUID firstOwnerAssignmentId = UUID.randomUUID();
        insertAssignment(
                firstOwnerAssignmentId,
                fixture,
                "OWNER",
                fixture.userPrincipalId(),
                "USER",
                fixture.memberId());
        assertSqlState(
                "23505",
                assignmentInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                "OWNER",
                secondUserId,
                "USER",
                secondMemberId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertSqlState(
                "23503",
                assignmentInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                "EXECUTOR",
                fixture.userPrincipalId(),
                "USER",
                secondMemberId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertSqlState(
                "23514",
                assignmentInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                "OWNER",
                fixture.agentPrincipalId(),
                "PERSONAL_AGENT",
                null,
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());

        insertAssignment(
                UUID.randomUUID(),
                fixture,
                "EXECUTOR",
                fixture.agentPrincipalId(),
                "PERSONAL_AGENT",
                null);
        assertSqlState(
                "23505",
                assignmentInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                "EXECUTOR",
                fixture.agentPrincipalId(),
                "PERSONAL_AGENT",
                null,
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
        assertSqlState(
                "23514",
                """
                UPDATE crewscope.responsibility_assignment
                SET status = 'RELEASED', version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                firstOwnerAssignmentId);

        execute(
                """
                UPDATE crewscope.responsibility_assignment
                SET status = 'RELEASED',
                    released_by_principal_id = ?,
                    released_at = CURRENT_TIMESTAMP,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by_principal_id = ?
                WHERE id = ?
                """,
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                firstOwnerAssignmentId);
        insertAssignment(
                UUID.randomUUID(), fixture, "OWNER", secondUserId, "USER", secondMemberId);
        assertEquals(
                1,
                queryInt(
                        """
                        SELECT COUNT(*)
                        FROM crewscope.responsibility_assignment
                        WHERE work_item_id = ? AND role = 'OWNER' AND status = 'ACTIVE'
                        """,
                        fixture.workItemId()));
    }

    private static Fixture seedFixture(String projectKey) throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userPrincipalId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID agentPrincipalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Team', 'ACTIVE')
                """,
                teamId,
                organizationId);
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Owner User', 'ACTIVE')
                """,
                userPrincipalId,
                organizationId);
        execute(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                memberId,
                organizationId,
                teamId,
                userPrincipalId);
        Fixture fixture = new Fixture(
                organizationId,
                teamId,
                workspaceId,
                userPrincipalId,
                memberId,
                agentPrincipalId,
                projectId,
                workItemId);
        insertPersonalAgent(fixture, agentPrincipalId, "Personal Agent");
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id,
                    project_key, name, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'Project', ?, ?)
                """,
                projectId,
                organizationId,
                teamId,
                workspaceId,
                projectKey,
                userPrincipalId,
                userPrincipalId);
        execute(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Work item', 'BACKLOG', 'MEDIUM', ?, ?)
                """,
                workItemId,
                organizationId,
                teamId,
                workspaceId,
                projectId,
                projectKey + "-1",
                userPrincipalId,
                userPrincipalId);
        updateTeamReferences(organizationId, teamId, memberId, workspaceId);
        return fixture;
    }

    private static void insertPersonalAgent(Fixture fixture, UUID agentId, String name)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    owner_principal_id, display_name, visibility, status
                ) VALUES (?, ?, ?, 'PERSONAL_AGENT', ?, ?, 'PRIVATE', 'ACTIVE')
                """,
                agentId,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.userPrincipalId(),
                name);
    }

    private static void insertUserAndMember(
            Fixture fixture, UUID userId, UUID memberId, String name) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                userId,
                fixture.organizationId(),
                name);
        execute(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'INVITATION', CURRENT_TIMESTAMP)
                """,
                memberId,
                fixture.organizationId(),
                fixture.teamId(),
                userId);
    }

    private static void insertAssignment(
            UUID assignmentId,
            Fixture fixture,
            String role,
            UUID actorPrincipalId,
            String actorType,
            UUID actorMemberId)
            throws SQLException {
        execute(
                assignmentInsertSql(),
                assignmentId,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                role,
                actorPrincipalId,
                actorType,
                actorMemberId,
                fixture.userPrincipalId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static String assignmentInsertSql() {
        return """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """;
    }

    private static void updateTeamReferences(
            UUID organizationId, UUID teamId, UUID memberId, UUID workspaceId)
            throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        UPDATE crewscope.team
                        SET owner_member_id = ?, default_workspace_id = ?
                        WHERE organization_id = ? AND id = ?
                        """)) {
            connection.setAutoCommit(false);
            bind(statement, memberId, workspaceId, organizationId, teamId);
            statement.executeUpdate();
            connection.commit();
        }
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
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

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int execute(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    private static void assertSqlState(String state, String sql, Object... values) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql, values));
        assertEquals(state, exception.getSQLState());
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private static Set<String> columns(String table) throws SQLException {
        return queryStrings(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = '%s'
                """
                        .formatted(table));
    }

    private static int queryInt(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static String queryString(String sql, Object... values) throws SQLException {
        return queryNullableString(sql, values);
    }

    private static String queryNullableString(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    private record Fixture(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID userPrincipalId,
            UUID memberId,
            UUID agentPrincipalId,
            UUID projectId,
            UUID workItemId) {}
}
