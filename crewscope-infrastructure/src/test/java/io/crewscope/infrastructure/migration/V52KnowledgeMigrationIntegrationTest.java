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
 * Locks down the M10-D01 knowledge foundation at the database level: the two-table head/
 * version shape with the two-half effective-pointer CHECK, the append-only uniqueness
 * (revision and content hash), the composite tenant foreign keys, the built-in role
 * permission backfill — all on plain PostgreSQL with no extensions (the pgvector proof
 * is the zero-row pg_extension count).
 */
class V52KnowledgeMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_51 = MigrationVersion.fromVersion("51");
    private static final MigrationVersion VERSION_52 = MigrationVersion.fromVersion("52");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private UUID organizationId;
    private UUID teamId;
    private UUID principalId;

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
        organizationId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        principalId = UUID.randomUUID();
    }

    @Test
    void migratesAnEmptySchemaToV52OnPlainPostgreSQL() throws SQLException {
        Flyway target = flyway(VERSION_52);

        assertTrue(target.migrate().migrationsExecuted >= 52);
        target.validate();
        assertEquals("52", target.info().current().getVersion().getVersion());
        assertEquals(
                0,
                queryInt(
                        "SELECT COUNT(*) FROM pg_extension"
                                + " WHERE extname IN ('vector', 'pgvector')"),
                "the knowledge foundation must install on plain PostgreSQL");
    }

    @Test
    void backfillsM10PermissionsOntoBuiltInRolesOnly() throws SQLException {
        flyway(VERSION_51).migrate();
        UUID otherTeamId = UUID.randomUUID();
        seedBaseFacts();
        execute(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, 'Other Team', 'ACTIVE')",
                otherTeamId, organizationId);
        seedBuiltInRole(teamId, "TEAM_OWNER", "[]");
        seedBuiltInRole(teamId, "TEAM_ADMIN", "[]");
        seedBuiltInRole(teamId, "MEMBER", "[]");
        seedBuiltInRole(otherTeamId, "TEAM_OWNER", "[\"KNOWLEDGE_MANAGE\"]");
        UUID customRoleId = seedCustomRole();

        flyway(VERSION_52).migrate();

        // Both backfill statements hit the TEAM_OWNER row: version advances by two.
        assertEquals(1, queryInt(
                permissionCount(teamId, "TEAM_OWNER"), "KNOWLEDGE_MANAGE"));
        assertEquals(1, queryInt(permissionCount(teamId, "TEAM_OWNER"), "SKILL_MANAGE"));
        assertEquals(1, queryInt(
                permissionCount(teamId, "TEAM_ADMIN"), "KNOWLEDGE_MANAGE"));
        assertEquals(0, queryInt(permissionCount(teamId, "TEAM_ADMIN"), "SKILL_MANAGE"));
        assertEquals(0, queryInt(permissionCount(teamId, "MEMBER"), "KNOWLEDGE_MANAGE"));
        assertEquals(0, queryInt(permissionCount(teamId, "MEMBER"), "SKILL_MANAGE"));
        assertEquals(0, queryInt(
                permissionCount(teamId, "RELEASE_MANAGER"), "KNOWLEDGE_MANAGE"));
        // The pre-seeded KNOWLEDGE_MANAGE survives without duplication: the backfill only
        // adds the missing SKILL_MANAGE, so the array holds two distinct elements.
        assertEquals(
                2,
                queryInt(
                        "SELECT jsonb_array_length(permissions)"
                                + " FROM crewscope.team_role"
                                + " WHERE team_id = ? AND role_key = 'TEAM_OWNER'",
                        otherTeamId));
        // Custom roles keep their exact permissions and version.
        assertEquals(0, queryInt(
                "SELECT version FROM crewscope.team_role WHERE id = ?", customRoleId));
    }

    @Test
    void enforcesHeadShapeChecks() throws SQLException {
        flyway(VERSION_52).migrate();
        seedBaseFacts();

        assertSqlState("23514", entryInsert(),
                row("Bad_Key", "DRAFT", null, 0L, "Draft", "Content"));
        assertSqlState("23514", entryInsert(),
                row("valid-key", "ARCHIVED", null, 0L, "Draft", "Content"));
        assertSqlState("23514", entryInsert(),
                row("valid-key", "DRAFT", 1L, 1L, "Draft", "Content"));
        assertSqlState("23514", entryInsert(),
                row("valid-key", "PUBLISHED", null, 1L, null, null));
        assertSqlState("23514", entryInsert(),
                row("valid-key", "PUBLISHED", 2L, 1L, null, null));
        assertSqlState("23514", entryInsert(),
                row("valid-key", "DRAFT", null, 0L, "Only title", null));
        // Overlength drafts are truncated by the varchar column itself (22001), which is
        // the outer of the two title-length fences.
        assertSqlState("22001", entryInsert(),
                row("valid-key", "DRAFT", null, 0L, "x".repeat(201), "Content"));

        // The two-half gate keeps RETIRED and DELETED rows with their retained pointer.
        execute(entryInsert(), row("retired-key", "RETIRED", 3L, 3L, null, null));
        execute(entryInsert(), row("deleted-key", "DELETED", 3L, 3L, null, null));
    }

    @Test
    void enforcesVersionAppendOnlyUniquenessAndChain() throws SQLException {
        flyway(VERSION_52).migrate();
        seedBaseFacts();
        UUID entryId = UUID.randomUUID();
        execute(entryInsert(), row(entryId, "deploy-runbook", "PUBLISHED", 1L, 1L, null, null));
        execute(versionInsert(), versionRow(entryId, 1L, null, HASH_A));

        assertSqlState("23505", entryInsert(),
                row("deploy-runbook", "DRAFT", null, 0L, "Draft", "Content"));
        assertSqlState("23505", versionInsert(), versionRow(entryId, 1L, null, HASH_B));
        assertSqlState("23505", versionInsert(), versionRow(entryId, 2L, 1L, HASH_A));
        assertSqlState("23514", versionInsert(), versionRow(entryId, 3L, null, HASH_B));
        assertSqlState("23514", versionInsert(), versionRow(entryId, 3L, 1L, HASH_B));
        assertSqlState("23514", versionInsert(), versionRow(entryId, 0L, null, HASH_B));
        assertSqlState("23514", versionInsert(), versionRow(entryId, 2L, 1L, "not-a-hash"));
        assertSqlState("23514", versionInsert(),
                versionRow(entryId, 2L, 1L, HASH_B, "  ", "Content"));
    }

    @Test
    void rejectsCrossTenantVersionRows() throws SQLException {
        flyway(VERSION_52).migrate();
        seedBaseFacts();
        UUID entryId = UUID.randomUUID();
        execute(entryInsert(), row(entryId, "deploy-runbook", "PUBLISHED", 1L, 1L, null, null));
        execute(versionInsert(), versionRow(entryId, 1L, null, HASH_A));

        assertSqlState(
                "23503",
                versionInsert(),
                versionRow(organizationId, UUID.randomUUID(), entryId, 1L, null, HASH_B,
                        "Deploy Runbook", "Step one: drain the pool."));
    }

    @Test
    void effectivePartialIndexServesTheRetrievalGate() throws SQLException {
        flyway(VERSION_52).migrate();
        seedBaseFacts();
        execute(entryInsert(), row("draft-key", "DRAFT", null, 0L, "Draft", "Content"));
        execute(entryInsert(), row("published-key", "PUBLISHED", 1L, 1L, null, null));
        execute(entryInsert(), row("retired-key", "RETIRED", 1L, 1L, null, null));

        assertEquals(
                1,
                queryInt(
                        "SELECT COUNT(*) FROM crewscope.knowledge_entry"
                                + " WHERE organization_id = ? AND team_id = ?"
                                + " AND status = 'PUBLISHED' AND effective_revision IS NOT NULL",
                        organizationId, teamId));
        assertTrue(
                explainText(
                                "SELECT entry_key FROM crewscope.knowledge_entry"
                                        + " WHERE organization_id = ? AND team_id = ?"
                                        + " AND status = 'PUBLISHED'",
                                organizationId, teamId)
                        .contains("ix_knowledge_entry_effective"),
                "the retrieval gate must be served by the partial effective index");
    }

    private static String permissionCount(UUID teamId, String roleKey) {
        return "SELECT COUNT(*) FROM crewscope.team_role"
                + " WHERE team_id = '" + teamId + "' AND role_key = '" + roleKey + "'"
                + " AND jsonb_exists(permissions, ?)";
    }

    private void seedBaseFacts() throws SQLException {
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, 'Team', 'ACTIVE')",
                teamId, organizationId);
        execute(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Author', 'ACTIVE')
                """,
                principalId, organizationId);
    }

    private void seedBuiltInRole(UUID scopeTeamId, String roleKey, String permissions)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in,
                    permissions, scope_type, status
                ) VALUES (?, ?, ?, ?, ?, TRUE, CAST(? AS jsonb), 'TEAM', 'ACTIVE')
                """,
                UUID.randomUUID(), organizationId, scopeTeamId, roleKey, roleKey, permissions);
    }

    private UUID seedCustomRole() throws SQLException {
        UUID roleId = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in,
                    permissions, scope_type, status
                ) VALUES (?, ?, ?, 'RELEASE_MANAGER', 'Release Manager', FALSE,
                          CAST('["WORK_CREATE"]' AS jsonb), 'WORK_PROJECT', 'ACTIVE')
                """,
                roleId, organizationId, teamId);
        return roleId;
    }

    private static String entryInsert() {
        return """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status,
                    effective_revision, latest_revision, draft_title, draft_content,
                    version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, ?,
                          CURRENT_TIMESTAMP, ?)
                """;
    }

    /** Head row with the full shape coordinate as explicit parameters. */
    private Object[] row(
            String entryKey,
            String status,
            Long effectiveRevision,
            Long latestRevision,
            String draftTitle,
            String draftContent) {
        return row(UUID.randomUUID(), entryKey, status, effectiveRevision, latestRevision,
                draftTitle, draftContent);
    }

    private Object[] row(
            UUID id,
            String entryKey,
            String status,
            Long effectiveRevision,
            Long latestRevision,
            String draftTitle,
            String draftContent) {
        return new Object[] {
            id, organizationId, teamId, entryKey, status, effectiveRevision, latestRevision,
            draftTitle, draftContent, principalId, principalId
        };
    }

    private static String versionInsert() {
        return """
                INSERT INTO crewscope.knowledge_entry_version (
                    organization_id, team_id, entry_id, revision, previous_revision,
                    title, content, content_hash, created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """;
    }

    private Object[] versionRow(
            UUID entryId, Long revision, Long previousRevision, String contentHash) {
        return versionRow(organizationId, teamId, entryId, revision, previousRevision,
                contentHash, "Deploy Runbook", "Step one: drain the pool.");
    }

    private Object[] versionRow(
            UUID entryId, Long revision, Long previousRevision, String contentHash,
            String title, String content) {
        return versionRow(organizationId, teamId, entryId, revision, previousRevision,
                contentHash, title, content);
    }

    private Object[] versionRow(
            UUID rowOrganizationId,
            UUID rowTeamId,
            UUID entryId,
            Long revision,
            Long previousRevision,
            String contentHash,
            String title,
            String content) {
        return new Object[] {
            rowOrganizationId, rowTeamId, entryId, revision, previousRevision,
            title, content, contentHash, principalId
        };
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
        return Integer.parseInt(queryString(sql, values).strip());
    }

    /** EXPLAIN text with sequential scans disabled, so the assertion pins the index choice. */
    private static String explainText(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement disable = connection.prepareStatement("SET enable_seqscan = off");
                PreparedStatement statement = connection.prepareStatement(
                        "EXPLAIN (COSTS OFF) " + sql)) {
            disable.executeUpdate();
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            StringBuilder lines = new StringBuilder();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    lines.append(resultSet.getString(1)).append('\n');
                }
            }
            return lines.toString();
        }
    }

    private static String queryString(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            StringBuilder lines = new StringBuilder();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    lines.append(resultSet.getString(1)).append('\n');
                }
            }
            return lines.toString();
        }
    }
}
