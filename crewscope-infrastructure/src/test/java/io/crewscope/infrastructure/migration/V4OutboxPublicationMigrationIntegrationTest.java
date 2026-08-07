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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V4 lease state machine and forward upgrade of existing PENDING rows. */
class V4OutboxPublicationMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_3 = MigrationVersion.fromVersion("3");
    private static final MigrationVersion VERSION_4 = MigrationVersion.fromVersion("4");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void addsLeaseColumnsExactStatusesIndexesAndConsumerReceipts() throws SQLException {
        Flyway flyway = flyway(VERSION_4);
        flyway.migrate();

        assertEquals("4", flyway.info().current().getVersion().getVersion());
        Set<String> columns = queryStrings(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = 'outbox_event'
                """);
        assertTrue(columns.containsAll(Set.of(
                "claim_token", "claimed_by", "claim_expires_at", "last_error_code")));
        Set<String> constraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope' AND table_name = 'outbox_event'
                """);
        assertTrue(constraints.containsAll(Set.of(
                "ck_outbox_delivery_status",
                "ck_outbox_claim_fields",
                "ck_outbox_delivered_state")));
        assertEquals(1, queryInt(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'crewscope'
                  AND table_name = 'event_consumer_receipt'
                """));
        Set<String> indexes = queryStrings(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'crewscope' AND tablename = 'outbox_event'
                """);
        assertTrue(indexes.containsAll(Set.of(
                "ix_outbox_publishable", "ix_outbox_partition_order")));
    }

    @Test
    void preservesExistingPendingRowsAndRejectsInconsistentClaimState() throws SQLException {
        Flyway versionThree = flyway(VERSION_3);
        versionThree.migrate();
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id,
                    subject_type, subject_id, aggregate_version, actor_type,
                    correlation_id, occurred_at, payload
                ) VALUES (?, 'TEST_EVENT', '1', ?, 'TEST', ?, 0, 'SERVICE',
                    ?, CURRENT_TIMESTAMP, CAST('{}' AS JSONB))
                """,
                eventId,
                organizationId,
                UUID.randomUUID(),
                UUID.randomUUID());
        execute(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key, delivery_status,
                    retry_count, created_at, version, updated_at
                ) VALUES (?, ?, 'topic', 'partition', 'PENDING', 0,
                    CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP)
                """,
                outboxId,
                eventId);

        flyway(VERSION_4).migrate();

        assertEquals("PENDING", queryString(
                "SELECT delivery_status FROM crewscope.outbox_event WHERE id = ?", outboxId));
        assertThrows(
                SQLException.class,
                () -> execute(
                        """
                        UPDATE crewscope.outbox_event
                        SET delivery_status = 'CLAIMED'
                        WHERE id = ?
                        """,
                        outboxId));
        assertThrows(
                SQLException.class,
                () -> execute(
                        """
                        UPDATE crewscope.outbox_event
                        SET delivery_status = 'UNKNOWN'
                        WHERE id = ?
                        """,
                        outboxId));
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
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String queryString(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
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
}
