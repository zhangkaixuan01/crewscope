package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** Proves V26 preserves complete automatic Receipt claim coordinates across recovery. */
class V26ActionReceiptClaimCoordinatesMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_25 = MigrationVersion.fromVersion("25");
    private static final MigrationVersion VERSION_26 = MigrationVersion.fromVersion("26");

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void upgradesClaimedReceiptsAndRejectsPartialRecoveredCoordinates() throws SQLException {
        flyway(VERSION_25).migrate();
        UUID receiptId = UUID.randomUUID();
        seedAutomaticReceipt(receiptId);

        Flyway target = flyway(VERSION_26);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("26", target.info().current().getVersion().getVersion());

        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT * FROM crewscope.action_receipt WHERE id = '" + receiptId + "'")) {
            row.next();
            assertEquals("EXECUTE", row.getString("claim_mode"));
            assertNotNull(row.getObject("claim_acquired_at"));
            assertNotNull(row.getObject("claim_last_heartbeat_at"));
            assertNotNull(row.getObject("claim_lease_until"));
        }

        assertEquals("23514", assertThrows(
                        SQLException.class,
                        () -> seedAutomaticReceipt(UUID.randomUUID()))
                .getSQLState());
    }

    private static void seedAutomaticReceipt(UUID receiptId) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                statement.executeUpdate("""
                        INSERT INTO crewscope.action_receipt (
                            id, organization_id, team_id, workspace_id, project_id,
                            action_bundle_id, bundle_digest, action_dispatch_id, action_id,
                            action_digest, idempotency_key, result, source,
                            claim_worker_id, claim_fencing_token,
                            evidence_code, evidence_hash, received_at, created_at,
                            created_by_principal_id
                        ) VALUES (
                            '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                            '%s', '%s', 'FAILED', 'WRITE_RESPONSE', 'worker-before-v26', 7,
                            'REMOTE_HEAD_CONFLICT', '%s',
                            TIMESTAMPTZ '2026-08-23 14:00:00+00',
                            TIMESTAMPTZ '2026-08-23 14:00:00+00', '%s'
                        )
                        """.formatted(
                        receiptId,
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(),
                        "b".repeat(64), "c".repeat(64), "d".repeat(64), UUID.randomUUID()));
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
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
}
