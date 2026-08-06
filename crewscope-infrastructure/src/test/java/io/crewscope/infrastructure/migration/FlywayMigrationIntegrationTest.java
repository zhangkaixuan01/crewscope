package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks down CrewScope's forward-only Flyway migration contract against a real PostgreSQL.
 *
 * <p>The V1-to-latest test intentionally discovers pending migrations at runtime. When M0-D03
 * adds V2, the same test will exercise an actual V1-to-V2 upgrade without being rewritten.
 */
class FlywayMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String APPLICATION_SCHEMA = "crewscope";
    private static final String ALTERNATE_SCHEMA = "migration_probe";
    private static final String PUBLIC_SCHEMA = "public";
    private static final MigrationVersion VERSION_1 = MigrationVersion.fromVersion("1");
    private static final Set<String> V1_TABLES =
            Set.of(
                    "organization",
                    "team",
                    "workspace",
                    "work_project",
                    "work_item",
                    "domain_event",
                    "outbox_event");

    @BeforeEach
    void resetSchemas() throws SQLException {
        // Every case starts from a deterministic database while retaining the class-level container.
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS migration_probe CASCADE");
            statement.execute("CREATE SCHEMA migration_probe");
        }
    }

    @Test
    void migratesEmptyDatabaseToLatestVersion() throws SQLException {
        Flyway flyway = latestFlyway(POSTGRES.getJdbcUrl());
        int pendingBeforeMigration = flyway.info().pending().length;

        MigrateResult result = flyway.migrate();

        assertTrue(result.success);
        assertTrue(pendingBeforeMigration > 0, "An empty database must have pending migrations");
        assertEquals(pendingBeforeMigration, result.migrationsExecuted);
        assertEquals(0, flyway.info().pending().length);
        assertNotNull(flyway.info().current());
        flyway.validate();
        assertV1TablesExistOnlyInApplicationSchema();
        assertEquals(1, tableCount(APPLICATION_SCHEMA, "flyway_schema_history"));
    }

    @Test
    void upgradesFromVersionOneToLatestVersion() throws SQLException {
        Flyway versionOneFlyway = flyway(POSTGRES.getJdbcUrl(), VERSION_1);
        MigrateResult versionOneResult = versionOneFlyway.migrate();

        assertTrue(versionOneResult.success);
        assertEquals(1, versionOneResult.migrationsExecuted);
        assertEquals("1", versionOneFlyway.info().current().getVersion().getVersion());

        Flyway latestFlyway = latestFlyway(POSTGRES.getJdbcUrl());
        int pendingUpgradeCount = latestFlyway.info().pending().length;
        MigrateResult upgradeResult = latestFlyway.migrate();

        assertTrue(upgradeResult.success);
        assertEquals(pendingUpgradeCount, upgradeResult.migrationsExecuted);
        assertEquals(0, latestFlyway.info().pending().length);
        assertNotNull(latestFlyway.info().current());
        latestFlyway.validate();
        assertV1TablesExistOnlyInApplicationSchema();
    }

    @Test
    void migratesWhenConnectionUsesNonDefaultSearchPath() throws SQLException {
        String alternateSearchPathUrl = jdbcUrlWithCurrentSchema(ALTERNATE_SCHEMA);
        assertEquals(ALTERNATE_SCHEMA, currentSchema(alternateSearchPathUrl));

        Flyway flyway = latestFlyway(alternateSearchPathUrl);
        MigrateResult result = flyway.migrate();

        assertTrue(result.success);
        assertEquals(0, flyway.info().pending().length);
        assertV1TablesExistOnlyInApplicationSchema();
        assertEquals(1, tableCount(APPLICATION_SCHEMA, "flyway_schema_history"));
        assertEquals(0, tableCount(ALTERNATE_SCHEMA, "flyway_schema_history"));
    }

    private static Flyway latestFlyway(String jdbcUrl) {
        return flyway(jdbcUrl, null);
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        FluentConfiguration configuration =
                Flyway.configure()
                        .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                        .locations(MIGRATION_LOCATION)
                        .schemas(APPLICATION_SCHEMA)
                        .defaultSchema(APPLICATION_SCHEMA)
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

    private static String jdbcUrlWithCurrentSchema(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
    }

    private static String currentSchema(String jdbcUrl) throws SQLException {
        try (Connection connection = openConnection(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT current_schema()")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static void assertV1TablesExistOnlyInApplicationSchema() throws SQLException {
        for (String table : V1_TABLES) {
            assertEquals(1, tableCount(APPLICATION_SCHEMA, table), table);
            assertEquals(0, tableCount(ALTERNATE_SCHEMA, table), table);
            assertEquals(0, tableCount(PUBLIC_SCHEMA, table), table);
        }
    }

    private static int tableCount(String schema, String table) throws SQLException {
        String sql =
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = ?";
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                int count = resultSet.getInt(1);
                assertFalse(resultSet.next());
                return count;
            }
        }
    }
}
