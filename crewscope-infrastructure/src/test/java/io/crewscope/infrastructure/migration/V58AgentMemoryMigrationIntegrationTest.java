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
 * Locks the V58 agent memory shape (M10-I02a): the migration stays pure PostgreSQL, the
 * frozen entry contract (key shape, 1KB UTF-8 byte bound, positive policy version) closes
 * at the database boundary, the space key keeps one value per (owner x policy version x
 * key) while different policy versions coexist, and the foreign keys RESTRICT.
 */
class V58AgentMemoryMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    // The chain tip this test rides (M10-Q02 added V66 on top of the V58 tables).
    private static final MigrationVersion VERSION_TIP = MigrationVersion.fromVersion("66");
    private static final String NOW = "TIMESTAMPTZ '2026-10-04 09:00:00+00'";
    private static final UUID POLICY_ID = UUID.fromString(
            "7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20");

    private record Scope(
            UUID organizationId, UUID teamId, UUID workspaceId, UUID agentPrincipalId,
            UUID agentProfileId, UUID ownerPrincipalId) {}

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void migratesAnEmptySchemaToTheChainTipWithoutAnyExtension() throws SQLException {
        Flyway target = flyway(VERSION_TIP);

        assertTrue(target.migrate().migrationsExecuted >= 60);
        target.validate();
        assertEquals("66", target.info().current().getVersion().getVersion());
        // The default chain stays pure PostgreSQL: no vector extension may appear.
        assertEquals(0L, longScalar(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'"));
    }

    @Test
    void rejectsEntriesOutsideTheFrozenContract() throws SQLException {
        flyway(VERSION_TIP).migrate();
        Scope scope = seedScope();
        execute(ownerInsert(scope));

        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'Reply_Language'", "'中文'", 1)));
        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'reply-language'", "'  '", 1)));
        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'reply-language'",
                "'%s'".formatted("a".repeat(1025)), 1)));
        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'reply-language'", "'中文'", 0)));
        assertSqlState("23514", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'reply-language'", "'中文'", -1)));
        // 1024 UTF-8 bytes exactly (341 CJK chars x 3 bytes + one ASCII) is legal.
        UUID legal = UUID.randomUUID();
        execute(entryInsert(
                scope, legal, "'reply-language'",
                "'%s'".formatted("汉".repeat(341) + "a"), 1));
        assertEquals("汉".repeat(341) + "a", textScalar(
                "SELECT value FROM crewscope.agent_memory_entry WHERE id = '%s'"
                        .formatted(legal)));
    }

    @Test
    void oneValuePerSpaceWhilePolicyVersionsCoexist() throws SQLException {
        flyway(VERSION_TIP).migrate();
        Scope scope = seedScope();
        execute(ownerInsert(scope));

        UUID first = UUID.randomUUID();
        execute(entryInsert(scope, first, "'reply-language'", "'中文'", 1));
        // The same space key twice is a conflict; a different policy version is a new space.
        assertSqlState("23505", () -> execute(entryInsert(
                scope, UUID.randomUUID(), "'reply-language'", "'English'", 1)));
        execute(entryInsert(scope, UUID.randomUUID(), "'reply-language'", "'Deutsch'", 2));
        assertEquals(2L, longScalar("""
                SELECT count(*) FROM crewscope.agent_memory_entry
                WHERE organization_id = '%s' AND memory_key = 'reply-language'
                """.formatted(scope.organizationId())));
    }

    @Test
    void foreignKeysRestrictCleanupUntilTheSweepDeletesEntries() throws SQLException {
        flyway(VERSION_TIP).migrate();
        Scope scope = seedScope();
        execute(ownerInsert(scope));
        execute(entryInsert(scope, UUID.randomUUID(), "'reply-language'", "'中文'", 1));

        // Neither the owning row nor the team behind it can vanish while entries reference them.
        assertSqlState("23503", () -> execute("""
                DELETE FROM crewscope.agent_memory_owner
                WHERE organization_id = '%s' AND owner_principal_id = '%s'
                """.formatted(scope.organizationId(), scope.ownerPrincipalId())));
        assertSqlState("23503", () -> execute("""
                DELETE FROM crewscope.team WHERE id = '%s'
                """.formatted(scope.teamId())));
    }

    // ------------------------------------------------------------------ seeds and helpers

    private Scope seedScope() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID agentPrincipalId = UUID.randomUUID();
        UUID ownerPrincipalId = UUID.randomUUID();
        UUID agentProfileId = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V58 Org', 'ACTIVE')
                """.formatted(organizationId));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V58 Team', 'ACTIVE')
                """.formatted(teamId, organizationId));
        execute("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES ('%s', '%s', '%s', 'TEAM', 'V58 Workspace', 'ACTIVE')
                """.formatted(workspaceId, organizationId, teamId));
        // A TEAM profile keeps the seed minimal: no owner_member_id requirement.
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', '%s', 'TEAM_AGENT', 'V58 Agent', 'ACTIVE')
                """.formatted(agentPrincipalId, organizationId, teamId));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V58 Member', 'ACTIVE')
                """.formatted(ownerPrincipalId, organizationId));
        execute("""
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    profile_type, status, created_by_principal_id, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 'TEAM', 'ACTIVE', '%s', '%s')
                """.formatted(agentProfileId, organizationId, teamId, workspaceId,
                agentPrincipalId, ownerPrincipalId, ownerPrincipalId));
        return new Scope(organizationId, teamId, workspaceId, agentPrincipalId,
                agentProfileId, ownerPrincipalId);
    }

    private static String ownerInsert(Scope scope) {
        return """
                INSERT INTO crewscope.agent_memory_owner (
                    id, organization_id, team_id, agent_profile_id, owner_principal_id,
                    clearance_generation, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 0, %s, '%s', %s, '%s')
                """.formatted(UUID.randomUUID(), scope.organizationId(), scope.teamId(),
                scope.agentProfileId(), scope.ownerPrincipalId(),
                NOW, scope.ownerPrincipalId(), NOW, scope.ownerPrincipalId());
    }

    /** {@code value} and {@code key} are SQL literals; generation and policy version are ints. */
    private static String entryInsert(
            Scope scope, UUID entryId, String memoryKey, String value, long policyVersion) {
        return """
                INSERT INTO crewscope.agent_memory_entry (
                    id, organization_id, team_id, agent_profile_id, owner_principal_id,
                    policy_id, policy_version, memory_key, value, clearance_generation,
                    version, expires_at, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', %d, %s, %s, 0, 0,
                    TIMESTAMPTZ '2026-12-31 09:00:00+00', %s, '%s', %s, '%s')
                """.formatted(entryId, scope.organizationId(), scope.teamId(),
                scope.agentProfileId(), scope.ownerPrincipalId(),
                POLICY_ID, policyVersion, memoryKey, value,
                NOW, scope.ownerPrincipalId(), NOW, scope.ownerPrincipalId());
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

    private static long longScalar(String sql) throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            long value = result.getLong(1);
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
