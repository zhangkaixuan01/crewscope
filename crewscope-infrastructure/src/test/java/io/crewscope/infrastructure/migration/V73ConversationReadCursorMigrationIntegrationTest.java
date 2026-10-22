package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testcontainers.SchemaTemplates;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the V73 read-cursor column on {@code conversation_participant} (M13b-A07): one
 * NOT NULL bigint defaulting to 0, added without backfill so existing participation reads
 * as "everything unread" until its viewer posts a receipt. The upgrade test also pins the
 * separate migration review discipline: the column must appear exactly when V73 runs on a
 * database that stopped at the pre-cursor chain.
 */
class V73ConversationReadCursorMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String SCHEMA = "crewscope";

    /** Private per-method database: every test method leases and releases its own. */
    private static SchemaTemplates.Lease currentLease;

    @AfterEach
    void releaseLease() {
        if (currentLease != null) {
            currentLease.close();
            currentLease = null;
        }
    }

    @Test
    void addsANonNullCursorColumnDefaultingToZero() throws SQLException {
        currentLease = SchemaTemplates.forPostgres().lease(SchemaTemplates.TIP);
        flyway().migrate();

        Map<String, String> column = queryString(
                """
                SELECT data_type || '|' || is_nullable || '|' || coalesce(column_default, '')
                FROM information_schema.columns
                WHERE table_schema = 'crewscope'
                  AND table_name = 'conversation_participant'
                  AND column_name = 'last_read_sequence'
                """);
        assertEquals("bigint|NO|0", column.get("value"));
    }

    @Test
    void upgradesAPreCursorDatabaseWithoutTouchingExistingShapes() throws SQLException {
        // V1..V70 is the last stretch where every version number has a file, so the
        // numeric lease is legal there and hands over a database resting at exactly 70;
        // V71 remains a permanent gap in the chain.
        currentLease = SchemaTemplates.forPostgres().lease("70");
        assertFalse(cursorColumnExists());

        Flyway upgrader = flyway();
        upgrader.migrate();

        assertTrue(cursorColumnExists());
        assertEquals(
                "73",
                queryString(
                                """
                                SELECT version FROM crewscope.flyway_schema_history
                                WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                                """)
                        .get("value"));
        // The added column leaves every pre-existing constraint intact.
        int participantConstraints =
                Integer.parseInt(
                        queryString(
                                        """
                                        SELECT count(*) FROM information_schema.table_constraints
                                        WHERE constraint_schema = 'crewscope'
                                          AND table_name = 'conversation_participant'
                                        """)
                                .get("value"));
        assertTrue(participantConstraints >= 10);
    }

    private boolean cursorColumnExists() throws SQLException {
        return queryString(
                        """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'crewscope'
                          AND table_name = 'conversation_participant'
                          AND column_name = 'last_read_sequence'
                        """)
                .get("value")
                .equals("1");
    }

    private static Flyway flyway() {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(currentLease.jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .validateMigrationNaming(true);
        return configuration.load();
    }

    private static Map<String, String> queryString(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        currentLease.jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return Map.of("value", resultSet.getString(1));
        }
    }
}
