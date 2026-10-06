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
 * Locks the V64 usage rollup and budget alert shapes (M10-F03): the migration stays
 * pure PostgreSQL, the grain closes at the database boundary (month format, role enum,
 * the 'XXX'-sentinel vs priced-row mutual exclusion), the COALESCE grain index rejects
 * a second row of the same grain, and the alert ledger dedups per (team, month, kind,
 * level) while its team foreign key RESTRICTs.
 */
class V64ModelUsageRollupMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    // The chain tip this test rides (V65 lands with the budget chain in F03b).
    private static final MigrationVersion VERSION_TIP = MigrationVersion.fromVersion("65");
    private static final String NOW = "TIMESTAMPTZ '2026-10-05 09:00:00+00'";

    /** Unpriced pricing shape: 'XXX' sentinel, all three costs and the triple NULL. */
    private static final String[] UNPRICED =
            {"'XXX'", "NULL", "NULL", "NULL", "NULL", "NULL", "NULL"};

    private UUID organizationId;
    private UUID teamId;

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
        organizationId = UUID.randomUUID();
        teamId = UUID.randomUUID();
    }

    @Test
    void migratesAnEmptySchemaToTheChainTipWithoutAnyExtension() throws SQLException {
        Flyway target = flyway(VERSION_TIP);

        assertTrue(target.migrate().migrationsExecuted >= 63);
        target.validate();
        assertEquals("65", target.info().current().getVersion().getVersion());
        assertEquals(0L, longScalar(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'"));
    }

    @Test
    void rejectsRollupRowsOutsideTheFrozenShapes() throws SQLException {
        flyway(VERSION_TIP).migrate();
        seedTenant();

        assertSqlState("23514", () -> execute(rollup(null, "2026-10", "WHISPER", UNPRICED)));
        assertSqlState("23514", () -> execute(rollup(null, "2026-13", "EMBEDDING", UNPRICED)));
        assertSqlState("23514", () -> execute(rollup(null, "2026-1", "EMBEDDING", UNPRICED)));
        assertSqlState("23514", () -> execute(rollup(null, "2026-10", "EMBEDDING",
                UNPRICED, 1, 2)));
        // An 'XXX' row must not carry costs; a priced row must carry the full triple.
        assertSqlState("23514", () -> execute(rollup(null, "2026-10", "EMBEDDING",
                priced("'XXX'", "0.5", "0.25", "NULL", "NULL", "NULL", "NULL"))));
        assertSqlState("23514", () -> execute(rollup(null, "2026-10", "EMBEDDING",
                priced("'USD'", "0.5", "0.25", "NULL", "NULL", "3", "1"))));
        // Both shape-legal extremes insert fine: organization-scoped unpriced and priced.
        execute(rollup(null, "2026-10", "EMBEDDING", UNPRICED));
        execute(rollup(teamIdLiteral(), "2026-10", "CHAT_PRIMARY",
                priced("'USD'", "9000000", "1000000", "NULL",
                        "'%s'".formatted(UUID.randomUUID()), "3", "1")));
        assertEquals(2L, longScalar(
                "SELECT count(*) FROM crewscope.model_usage_monthly_rollup"));
    }

    @Test
    void theCoalesceGrainIndexRejectsASecondRowOfTheSameGrain() throws SQLException {
        flyway(VERSION_TIP).migrate();
        seedTenant();

        execute(rollup(null, "2026-10", "EMBEDDING", UNPRICED));
        // The NULL team and the NULL price triple coerce onto the same sentinel grain.
        assertSqlState("23505", () -> execute(rollup(null, "2026-10", "EMBEDDING", UNPRICED)));
        execute(rollup(teamIdLiteral(), "2026-10", "EMBEDDING", UNPRICED));
        assertSqlState("23505", () -> execute(
                rollup(teamIdLiteral(), "2026-10", "EMBEDDING", UNPRICED)));
        // A different attempt is a different grain row by design: real retries stay
        // separately metered, never folded into attempt 1.
        execute(rollup(teamIdLiteral(), "2026-10", "EMBEDDING", UNPRICED, 2, null));
        assertEquals(3L, longScalar(
                "SELECT count(*) FROM crewscope.model_usage_monthly_rollup"));
    }

    @Test
    void budgetAlertsDedupPerTeamMonthKindLevelAndRestrictOnTeamDelete() throws SQLException {
        flyway(VERSION_TIP).migrate();
        seedTenant();

        execute(alert("TOKEN", "WARNING", "90000", "100000", "NULL"));
        assertSqlState("23505", () -> execute(
                alert("TOKEN", "WARNING", "95000", "100000", "NULL")));
        // A different level or kind is a different alert; shapes are closed sets.
        execute(alert("TOKEN", "EXCEEDED", "120000", "100000", "NULL"));
        assertSqlState("23514", () -> execute(
                alert("AMOUNT", "WARNING", "9.5", "10", "NULL")));
        assertSqlState("23514", () -> execute(alert("TOKEN", "INFO", "1", "10", "NULL")));
        assertSqlState("23514", () -> execute(alert("TOKEN", "WARNING", "0", "0", "NULL")));
        assertEquals(2L, longScalar("SELECT count(*) FROM crewscope.team_budget_alert"));
        assertSqlState("23503", () -> execute(
                "DELETE FROM crewscope.team WHERE id = '%s'".formatted(teamId)));
    }

    // ------------------------------------------------------------------ seeds and helpers

    private void seedTenant() throws SQLException {
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V64 Org', 'ACTIVE')
                """.formatted(organizationId));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V64 Team', 'ACTIVE')
                """.formatted(teamId, organizationId));
    }

    private String teamIdLiteral() {
        return "'%s'".formatted(teamId);
    }

    private static String[] priced(String currency, String inputCost, String outputCost,
            String cachedCost, String entry, String catalogRevision, String priceRevision) {
        return new String[] {currency, inputCost, outputCost, cachedCost, entry,
                catalogRevision, priceRevision};
    }

    private String rollup(String teamLiteral, String month, String role, String[] pricing) {
        return rollup(teamLiteral, month, role, pricing, null, null);
    }

    /**
     * Builds one rollup INSERT. The pricing array carries raw SQL literals for
     * {@code currency, input_cost, output_cost, cached_input_cost, catalog_entry_id,
     * catalog_revision, price_revision}.
     */
    private String rollup(String teamLiteral, String month, String role, String[] pricing,
            Integer attempt, Integer unreported) {
        return """
                INSERT INTO crewscope.model_usage_monthly_rollup (
                    organization_id, team_id, usage_month, role, provider_key, model_id,
                    currency_code, catalog_entry_id, catalog_revision, price_revision,
                    attempt, input_tokens, output_tokens, cached_input_tokens,
                    input_cost, output_cost, cached_input_cost,
                    fact_count, unreported_fact_count, first_fact_at, last_fact_at
                ) VALUES (
                    '%s', %s, '%s', '%s', 'DASHSCOPE', 'text-embedding-v4',
                    %s, %s, %s, %s,
                    %d, 100, 20, 0,
                    %s, %s, %s,
                    1, %d, %s, %s)
                """.formatted(organizationId,
                teamLiteral == null ? "NULL" : teamLiteral,
                month, role,
                pricing[0], pricing[4], pricing[5], pricing[6],
                attempt == null ? 1 : attempt,
                pricing[1], pricing[2], pricing[3],
                unreported == null ? 0 : unreported,
                NOW, NOW);
    }

    private String alert(String kind, String level, String metric, String threshold,
            String currency) {
        return """
                INSERT INTO crewscope.team_budget_alert (
                    id, organization_id, team_id, usage_month, kind, level,
                    metric_value, threshold, currency_code, detected_at, created_at
                ) VALUES (
                    '%s', '%s', '%s', '2026-10', '%s', '%s',
                    %s, %s, %s, %s, %s)
                """.formatted(UUID.randomUUID(), organizationId, teamId,
                kind, level, metric, threshold, currency, NOW, NOW);
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
