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
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V3 aggregate ordering coordinate and durable Outbox routing constraints. */
class V3DomainEventMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String SCHEMA = "crewscope";
    private static final MigrationVersion VERSION_2 = MigrationVersion.fromVersion("2");
    private static final MigrationVersion VERSION_3 = MigrationVersion.fromVersion("3");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void createsARequiredExplicitAggregateVersionAndRoutingChecks() throws SQLException {
        Flyway flyway = flyway(VERSION_3);
        flyway.migrate();

        assertEquals("3", flyway.info().current().getVersion().getVersion());
        assertEquals("NO", queryString(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'crewscope'
                  AND table_name = 'domain_event'
                  AND column_name = 'aggregate_version'
                """));
        assertNull(queryNullableString(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'crewscope'
                  AND table_name = 'domain_event'
                  AND column_name = 'aggregate_version'
                """));
        Set<String> constraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name IN ('domain_event', 'outbox_event')
                """);
        assertTrue(constraints.contains("ck_domain_event_aggregate_version"));
        assertTrue(constraints.contains("ck_domain_event_idempotency_key"));
        assertTrue(constraints.contains("ck_outbox_topic"));
        assertTrue(constraints.contains("ck_outbox_partition_key"));
        assertTrue(queryString(
                        """
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'crewscope'
                          AND indexname = 'ix_domain_event_subject'
                        """)
                .contains(
                        "(organization_id, subject_type, subject_id, aggregate_version, "
                                + "occurred_at, event_id)"));
    }

    @Test
    void upgradesAnExistingV2FactToVersionZeroAndRejectsRegression() throws SQLException {
        Flyway versionTwo = flyway(VERSION_2);
        versionTwo.migrate();
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
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
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, team_id, workspace_id,
                    subject_type, subject_id, actor_type,
                    correlation_id, occurred_at, payload
                ) VALUES (?, 'WORK_ITEM_CREATED', '1', ?, ?, ?,
                    'WORK_ITEM', ?, 'SERVICE', ?, CURRENT_TIMESTAMP, CAST('{}' AS JSONB))
                """,
                eventId,
                organizationId,
                teamId,
                workspaceId,
                UUID.randomUUID(),
                UUID.randomUUID());

        Flyway versionThree = flyway(VERSION_3);
        versionThree.migrate();

        assertEquals(0L, queryLong(
                "SELECT aggregate_version FROM crewscope.domain_event WHERE event_id = ?",
                eventId));
        assertThrows(
                SQLException.class,
                () -> execute(
                        "UPDATE crewscope.domain_event SET aggregate_version = -1 WHERE event_id = ?",
                        eventId));
    }

    private static Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
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
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static long queryLong(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static String queryString(String sql) throws SQLException {
        return queryNullableString(sql);
    }

    private static String queryNullableString(String sql) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        java.util.HashSet<String> values = new java.util.HashSet<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }
}
