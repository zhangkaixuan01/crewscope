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

/** Verifies the V5 tenant-qualified reservation, completion and forward-upgrade contract. */
class V5CommandReceiptMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_4 = MigrationVersion.fromVersion("4");
    private static final MigrationVersion VERSION_5 = MigrationVersion.fromVersion("5");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void addsTheCommandReceiptShapeConstraintsAndIndexes() throws SQLException {
        Flyway flyway = flyway(VERSION_5);
        flyway.migrate();

        assertEquals("5", flyway.info().current().getVersion().getVersion());
        Set<String> columns = queryStrings(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = 'command_receipt'
                """);
        assertTrue(columns.containsAll(Set.of(
                "organization_id",
                "idempotency_key",
                "command_type",
                "request_hash",
                "command_id",
                "domain_event_id",
                "committed_version",
                "correlation_id",
                "status",
                "created_at",
                "updated_at")));
        Set<String> constraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope' AND table_name = 'command_receipt'
                """);
        assertTrue(constraints.containsAll(Set.of(
                "command_receipt_pkey",
                "uk_command_receipt_command",
                "fk_command_receipt_organization",
                "fk_command_receipt_domain_event",
                "ck_command_receipt_key",
                "ck_command_receipt_hash",
                "ck_command_receipt_completion")));
        Set<String> indexes = queryStrings(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'crewscope' AND tablename = 'command_receipt'
                """);
        assertTrue(indexes.containsAll(Set.of(
                "ux_command_receipt_domain_event", "ix_command_receipt_created")));
    }

    @Test
    void upgradesV4AndEnforcesTenantKeyAndCompletionShape() throws SQLException {
        Flyway versionFour = flyway(VERSION_4);
        versionFour.migrate();
        UUID organizationId = UUID.randomUUID();
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);

        flyway(VERSION_5).migrate();

        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.command_receipt (
                    organization_id, idempotency_key, command_type, request_hash,
                    command_id, correlation_id, status, created_at, updated_at
                ) VALUES (?, 'create-work-item-1', 'CREATE_WORK_ITEM', ?, ?, ?, 'PENDING',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                organizationId,
                "a".repeat(64),
                commandId,
                correlationId);
        assertEquals(1, queryInt("SELECT COUNT(*) FROM crewscope.command_receipt"));

        assertThrows(
                SQLException.class,
                () -> execute(
                        """
                        INSERT INTO crewscope.command_receipt (
                            organization_id, idempotency_key, command_type, request_hash,
                            command_id, correlation_id, status, created_at, updated_at
                        ) VALUES (?, 'create-work-item-1', 'CREATE_WORK_ITEM', ?, ?, ?, 'PENDING',
                                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                        organizationId,
                        "b".repeat(64),
                        UUID.randomUUID(),
                        UUID.randomUUID()));
        assertThrows(
                SQLException.class,
                () -> execute(
                        """
                        UPDATE crewscope.command_receipt
                        SET status = 'COMPLETED'
                        WHERE organization_id = ? AND idempotency_key = 'create-work-item-1'
                        """,
                        organizationId));
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
