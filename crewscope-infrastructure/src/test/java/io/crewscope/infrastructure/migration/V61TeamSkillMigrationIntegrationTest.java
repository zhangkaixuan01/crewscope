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
 * Locks down the M10-A03a team skill catalog at the database level: the two-table head/
 * version shape with the effective-pointer and disable-reason CHECKs, the chain rule,
 * and the deliberate absence of a content-hash uniqueness — a rollback republishes
 * historical content as a new revision, so equal digests must coexist — plus the
 * TEAM_ADMIN SKILL_MANAGE backfill. All on plain PostgreSQL with no extensions.
 */
class V61TeamSkillMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_60 = MigrationVersion.fromVersion("60");
    private static final MigrationVersion VERSION_61 = MigrationVersion.fromVersion("61");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String DOCUMENT = """
            ---
            name: deploy-runbook-v2
            description: Standard rollback drill for the staging deploy.
            ---

            Step one: drain the pool.
            """;

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
    void migratesAnEmptySchemaToV61OnPlainPostgreSQL() throws SQLException {
        Flyway target = flyway(VERSION_61);

        assertTrue(target.migrate().migrationsExecuted >= 61);
        target.validate();
        assertEquals("61", target.info().current().getVersion().getVersion());
        assertEquals(
                0,
                queryInt(
                        "SELECT COUNT(*) FROM pg_extension"
                                + " WHERE extname IN ('vector', 'pgvector')"),
                "the skill catalog must install on plain PostgreSQL");
    }

    @Test
    void backfillsSkillManageOntoTeamAdminOnly() throws SQLException {
        flyway(VERSION_60).migrate();
        seedBaseFacts();
        // Rows as V52 left them: the owner already carries both M10 permissions, the
        // admin got KNOWLEDGE_MANAGE only — SKILL_MANAGE was owner-only until A03a.
        seedBuiltInRole("TEAM_OWNER", "[\"KNOWLEDGE_MANAGE\",\"SKILL_MANAGE\"]");
        seedBuiltInRole("TEAM_ADMIN", "[\"KNOWLEDGE_MANAGE\"]");
        seedBuiltInRole("MEMBER", "[]");
        UUID customRoleId = seedCustomRole();

        flyway(VERSION_61).migrate();

        // The V61 statement widens skill authorship to the admin; the owner keeps a
        // single copy and custom roles are untouched.
        assertEquals(1, queryInt(
                permissionCount("TEAM_OWNER"), "SKILL_MANAGE"));
        assertEquals(1, queryInt(permissionCount("TEAM_ADMIN"), "SKILL_MANAGE"));
        assertEquals(0, queryInt(permissionCount("MEMBER"), "SKILL_MANAGE"));
        assertEquals(0, queryInt(permissionCount("RELEASE_MANAGER"), "SKILL_MANAGE"));
        assertEquals(0, queryInt(
                "SELECT version FROM crewscope.team_role WHERE id = '" + customRoleId + "'"),
                "custom roles keep their exact permissions and version");
    }

    @Test
    void enforcesHeadShapeChecks() throws SQLException {
        flyway(VERSION_61).migrate();
        seedBaseFacts();

        assertSqlState("23514", headInsert(),
                row("Bad_Key", "DRAFT", null, 0L, DOCUMENT, null, null, null).bind());
        assertSqlState("23514", headInsert(),
                row("valid-key", "ARCHIVED", null, 0L, DOCUMENT, null, null, null).bind());
        assertSqlState("23514", headInsert(),
                row("valid-key", "DRAFT", 1L, 1L, DOCUMENT, null, null, null).bind());
        assertSqlState("23514", headInsert(),
                row("valid-key", "PUBLISHED", null, 1L, null, null, null, null).bind());
        assertSqlState("23514", headInsert(),
                row("valid-key", "PUBLISHED", 2L, 1L, null, null, null, null).bind());
        // A non-blank draft is required whenever a draft is carried.
        assertSqlState("23514", headInsert(),
                row("valid-key", "DRAFT", null, 0L, "   ", null, null, null).bind());
        // The disable reason only rides on a DISABLED head.
        assertSqlState("23514", headInsert(),
                row("valid-key", "DRAFT", null, 0L, DOCUMENT, "Scrapped.", null, null).bind());
        // Overlength reasons are truncated by the varchar column itself (22001).
        assertSqlState("22001", headInsert(),
                row("valid-key", "DRAFT", null, 0L, DOCUMENT, null, null, null)
                        .withDisableReason("x".repeat(201)).bind());
        // The origin pair is all-or-nothing and counts attempts from one.
        assertSqlState("23514", headInsert(),
                row("origin-half-a", "DRAFT", null, 0L, DOCUMENT, null, UUID.randomUUID(), null)
                        .bind());
        assertSqlState("23514", headInsert(),
                row("origin-half-b", "DRAFT", null, 0L, DOCUMENT, null, null, 1).bind());
        assertSqlState("23514", headInsert(),
                row("origin-zero", "DRAFT", null, 0L, DOCUMENT, null, UUID.randomUUID(), 0)
                        .bind());

        // A published head may then retire, keeping its pointer as evidence — with or
        // without a reason — and a DRAFT head may be scrapped with no pointer at all.
        // (The reserved built-in name java-spring-v1 matches the slug regex by design;
        // refusing it is a domain rule, not a database one.)
        UUID publishedId = UUID.randomUUID();
        execute(headInsert(), row(publishedId, "published-key", "PUBLISHED", 1L, 1L, null,
                null, null, null).bind());
        execute("UPDATE crewscope.team_skill SET status = 'DISABLED', disable_reason = 'Retired.'"
                + " WHERE id = '" + publishedId + "'");
        execute(headInsert(), row("scrapped-key", "DISABLED", null, 0L, DOCUMENT, null, null, null)
                .bind());

        // The tenant key is the 409 boundary of the catalog.
        assertSqlState("23505", headInsert(),
                row("published-key", "DRAFT", null, 0L, DOCUMENT, null, null, null).bind());
    }

    @Test
    void enforcesVersionChainAndAcceptsRollbackDigests() throws SQLException {
        flyway(VERSION_61).migrate();
        seedBaseFacts();
        UUID skillId = UUID.randomUUID();
        execute(headInsert(), row(skillId, "deploy-runbook-v2", "PUBLISHED", 1L, 1L, null,
                null, null, null).bind());
        execute(versionInsert(), versionRow(skillId, 1L, null, HASH_A, DOCUMENT));

        assertSqlState("23505", versionInsert(),
                versionRow(skillId, 1L, null, HASH_B, DOCUMENT),
                "the (tenant, skill, revision) primary key keeps revisions append-only");
        assertSqlState("23514", versionInsert(),
                versionRow(skillId, 2L, null, HASH_B, DOCUMENT));
        assertSqlState("23514", versionInsert(),
                versionRow(skillId, 3L, 1L, HASH_B, DOCUMENT));
        assertSqlState("23514", versionInsert(),
                versionRow(skillId, 0L, null, HASH_B, DOCUMENT));
        assertSqlState("23514", versionInsert(),
                versionRow(skillId, 2L, 1L, "not-a-hash", DOCUMENT));
        assertSqlState("23514", versionInsert(),
                versionRow(skillId, 2L, 1L, HASH_B, "   "));

        // The A03a divergence from knowledge: rolling back republishes historical
        // content as a new revision, so two rows of one skill may share a digest.
        execute(versionInsert(), versionRow(skillId, 2L, 1L, HASH_B, DOCUMENT));
        execute(versionInsert(), versionRow(skillId, 3L, 2L, HASH_A, DOCUMENT));
        assertEquals(
                2,
                queryInt("SELECT COUNT(*) FROM crewscope.team_skill_version"
                        + " WHERE skill_id = '" + skillId + "' AND content_hash = '" + HASH_A
                        + "'"),
                "identical digests across revisions are legal by design");
    }

    @Test
    void rejectsCrossTenantVersionRows() throws SQLException {
        flyway(VERSION_61).migrate();
        seedBaseFacts();
        UUID skillId = UUID.randomUUID();
        execute(headInsert(), row(skillId, "deploy-runbook-v2", "PUBLISHED", 1L, 1L, null,
                null, null, null).bind());
        execute(versionInsert(), versionRow(skillId, 1L, null, HASH_A, DOCUMENT));

        assertSqlState(
                "23503",
                versionInsert(),
                new Object[] {
                    organizationId, UUID.randomUUID(), skillId, 1L, null,
                    DOCUMENT, HASH_B, principalId
                });
    }

    @Test
    void effectivePartialIndexServesTheLoadingGate() throws SQLException {
        flyway(VERSION_61).migrate();
        seedBaseFacts();
        execute(headInsert(), row("draft-key", "DRAFT", null, 0L, DOCUMENT, null, null, null)
                .bind());
        execute(headInsert(), row("published-key", "PUBLISHED", 1L, 1L, null, null, null, null)
                .bind());
        execute(headInsert(), row("disabled-key", "PUBLISHED", 1L, 1L, null, null, null, null)
                .bind());
        execute("UPDATE crewscope.team_skill SET status = 'DISABLED', disable_reason = 'Retired.'"
                + " WHERE skill_key = 'disabled-key' AND organization_id = '" + organizationId
                + "' AND team_id = '" + teamId + "'");

        assertEquals(
                1,
                queryInt("SELECT COUNT(*) FROM crewscope.team_skill"
                        + " WHERE organization_id = '" + organizationId
                        + "' AND team_id = '" + teamId
                        + "' AND status = 'PUBLISHED' AND effective_revision IS NOT NULL"),
                "only PUBLISHED heads are loadable by later executions");
        assertTrue(
                explainText("SELECT skill_key FROM crewscope.team_skill"
                                + " WHERE organization_id = ? AND team_id = ?"
                                + " AND status = 'PUBLISHED'",
                        organizationId, teamId)
                        .contains("ix_team_skill_effective"),
                "the loading gate must be served by the partial effective index");
    }

    private String permissionCount(String roleKey) {
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

    private void seedBuiltInRole(String roleKey, String permissions) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in,
                    permissions, scope_type, status
                ) VALUES (?, ?, ?, ?, ?, TRUE, CAST(? AS jsonb), 'TEAM', 'ACTIVE')
                """,
                UUID.randomUUID(), organizationId, teamId, roleKey, roleKey, permissions);
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

    private static String headInsert() {
        return """
                INSERT INTO crewscope.team_skill (
                    id, organization_id, team_id, skill_key, status,
                    effective_revision, latest_revision, disable_reason, draft_content,
                    source_task_execution_id, source_execution_attempt,
                    version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, ?,
                          CURRENT_TIMESTAMP, ?)
                """;
    }

    /** Head row with the full shape coordinate as explicit parameters. */
    private HeadRow row(
            String skillKey,
            String status,
            Long effectiveRevision,
            Long latestRevision,
            String draftContent,
            String disableReason,
            UUID sourceExecutionId,
            Integer sourceAttempt) {
        return new HeadRow(UUID.randomUUID(), skillKey, status, effectiveRevision,
                latestRevision, draftContent, disableReason, sourceExecutionId,
                sourceAttempt);
    }

    private HeadRow row(
            UUID id,
            String skillKey,
            String status,
            Long effectiveRevision,
            Long latestRevision,
            String draftContent,
            String disableReason,
            UUID sourceExecutionId,
            Integer sourceAttempt) {
        return new HeadRow(id, skillKey, status, effectiveRevision, latestRevision,
                draftContent, disableReason, sourceExecutionId, sourceAttempt);
    }

    /**
     * Small mutable carrier: most assertions vary the disable reason independently of
     * the positional overload ladder, which the fixed-arity {@code Object[]} of the
     * V52 template cannot express.
     */
    private final class HeadRow {
        private final UUID id;
        private final String skillKey;
        private final String status;
        private final Long effectiveRevision;
        private final Long latestRevision;
        private String draftContent;
        private final String disableReason;
        private final UUID sourceExecutionId;
        private final Integer sourceAttempt;

        private HeadRow(
                UUID id,
                String skillKey,
                String status,
                Long effectiveRevision,
                Long latestRevision,
                String draftContent,
                String disableReason,
                UUID sourceExecutionId,
                Integer sourceAttempt) {
            this.id = id;
            this.skillKey = skillKey;
            this.status = status;
            this.effectiveRevision = effectiveRevision;
            this.latestRevision = latestRevision;
            this.draftContent = draftContent;
            this.disableReason = disableReason;
            this.sourceExecutionId = sourceExecutionId;
            this.sourceAttempt = sourceAttempt;
        }

        private HeadRow withDisableReason(String reason) {
            return new HeadRow(id, skillKey, status, effectiveRevision, latestRevision,
                    draftContent, reason, sourceExecutionId, sourceAttempt);
        }

        private Object[] bind() {
            return new Object[] {
                id, organizationId, teamId, skillKey, status, effectiveRevision,
                latestRevision, disableReason, draftContent, sourceExecutionId,
                sourceAttempt, principalId, principalId
            };
        }
    }

    private static String versionInsert() {
        return """
                INSERT INTO crewscope.team_skill_version (
                    organization_id, team_id, skill_id, revision, previous_revision,
                    content, content_hash, created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """;
    }

    private Object[] versionRow(
            UUID skillId, Long revision, Long previousRevision, String contentHash,
            String content) {
        return new Object[] {
            organizationId, teamId, skillId, revision, previousRevision,
            content, contentHash, principalId
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

    private static void assertSqlState(
            String state, String sql, Object[] values, String message) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql, values));
        assertEquals(state, exception.getSQLState(), message);
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
