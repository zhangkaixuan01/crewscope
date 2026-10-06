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
 * Locks the V65 budget inbox lane and notification template seed (M10-F03): the
 * rebuilt CHECK admits exactly EXCEPTION×BUDGET among the new combinations, the
 * seeded template is the one PUBLISHED 'team-budget-alert' row with the five text
 * variables the fixed renderer labels cover, and no shipped variable claims a
 * trusted origin a migration cannot know.
 */
class V65InboxBudgetSourceTypeMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_TIP = MigrationVersion.fromVersion("65");
    private static final String NOW = "TIMESTAMPTZ '2026-10-05 09:00:00+00'";
    private static final UUID TEMPLATE_ID = UUID.fromString(
            "88460269-6f59-5eac-9f8e-ad1c71201c6f");

    private UUID organizationId;
    private UUID teamId;
    private UUID memberId;

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
        organizationId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        memberId = UUID.randomUUID();
    }

    @Test
    void migratesToTheChainTip() throws SQLException {
        Flyway target = flyway(VERSION_TIP);

        assertTrue(target.migrate().migrationsExecuted >= 64);
        target.validate();
        assertEquals("65", target.info().current().getVersion().getVersion());
    }

    @Test
    void theBudgetLaneAdmitsExceptionBudgetAndStillRejectsEveryOtherCombination()
            throws SQLException {
        flyway(VERSION_TIP).migrate();
        seedTenant();
        seedProjectionGeneration();

        execute(inboxItem("EXCEPTION", "BUDGET"));
        // The CHECK stays closed outside the one new pair: REVIEW never rides BUDGET,
        // and EXCEPTION still rejects source types outside its four lanes.
        assertSqlState("23514", () -> execute(inboxItem("REVIEW", "BUDGET")));
        assertSqlState("23514", () -> execute(inboxItem("CONFIRMATION", "BUDGET")));
        assertSqlState("23514", () -> execute(inboxItem("OWNERSHIP", "BUDGET")));
        assertSqlState("23514", () -> execute(inboxItem("EXCEPTION", "TEAM_EXECUTIONX")));
        // The pre-V65 lanes keep working untouched.
        execute(inboxItem("EXCEPTION", "TASK_EXECUTION"));
        execute(inboxItem("REVIEW", "REVIEW_REQUEST"));
        assertEquals(2L, longScalar(
                "SELECT count(*) FROM crewscope.inbox_item WHERE item_type = 'EXCEPTION'"));
        assertEquals(1L, longScalar(
                "SELECT count(*) FROM crewscope.inbox_item WHERE item_type = 'REVIEW'"));
    }

    @Test
    void seedsExactlyOnePublishedBudgetTemplateWithFiveTextVariables() throws SQLException {
        flyway(VERSION_TIP).migrate();

        assertEquals(1L, longScalar("""
                SELECT count(*) FROM crewscope.notification_template
                WHERE server_template_key = 'team-budget-alert' AND status = 'PUBLISHED'
                  AND template_id = '%s' AND template_version = 1
                """.formatted(TEMPLATE_ID)));
        assertEquals(5L, longScalar("""
                SELECT count(*) FROM crewscope.notification_template_variable
                WHERE template_id = '%s' AND template_version = 1
                """.formatted(TEMPLATE_ID)));
        // Every shipped variable is plain TEXT: a trusted link would pin a deployment
        // origin the migration cannot know.
        assertEquals(0L, longScalar("""
                SELECT count(*) FROM crewscope.notification_template_variable
                WHERE template_id = '%s' AND variable_type = 'TRUSTED_LINK'
                """.formatted(TEMPLATE_ID)));
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT variable_name FROM crewscope.notification_template_variable
                        WHERE template_id = '%s' ORDER BY variable_name
                        """.formatted(TEMPLATE_ID))) {
            assertTrue(result.next());
            assertEquals("itemType", result.getString(1));
            assertTrue(result.next());
            assertEquals("priority", result.getString(1));
            assertTrue(result.next());
            assertEquals("sourceId", result.getString(1));
            assertTrue(result.next());
            assertEquals("sourceRevision", result.getString(1));
            assertTrue(result.next());
            assertEquals("sourceType", result.getString(1));
            assertFalse(result.next());
        }
    }

    // ------------------------------------------------------------------ seeds and helpers

    private void seedTenant() throws SQLException {
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V65 Org', 'ACTIVE')
                """.formatted(organizationId));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V65 Team', 'ACTIVE')
                """.formatted(teamId, organizationId));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status)
                VALUES ('%s', '%s', 'USER', 'V65 Member', 'ORGANIZATION', 'ACTIVE')
                """.formatted(memberId, organizationId));
        execute("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status,
                    join_method, joined_at)
                VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 'IMPORT', %s)
                """.formatted(memberId, organizationId, teamId, memberId, NOW));
    }

    private void seedProjectionGeneration() throws SQLException {
        // One implicit transaction: the pointer invariant is a DEFERRED trigger, so the
        // ACTIVE generation and its pointer must commit together.
        execute("""
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES ('member-inbox', 1, 1, 'inbox.canonical-v1', 'inbox.expected-v1');
                INSERT INTO crewscope.projection_generation (
                    organization_id, projection_name, generation, definition_version,
                    status, fencing_token, created_at, updated_at
                ) VALUES ('%s', 'member-inbox', 1, 1, 'ACTIVE', 1, %s, %s);
                INSERT INTO crewscope.projection_pointer (
                    organization_id, projection_name, active_generation, updated_at
                ) VALUES ('%s', 'member-inbox', 1, %s);
                """.formatted(organizationId, NOW, NOW, organizationId, NOW));
    }

    private String inboxItem(String itemType, String sourceType) {
        return """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at,
                    source_status, close_reason, closed_at
                ) VALUES (
                    '%s', '%s', '%s', 'member-inbox', 1,
                    '%s', 1, '%s', '%s',
                    '%s', 0, 'HIGH', NULL, %s,
                    'OPEN', NULL, NULL)
                """.formatted(organizationId, teamId, memberId,
                UUID.randomUUID(), itemType, sourceType, UUID.randomUUID(), NOW);
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
