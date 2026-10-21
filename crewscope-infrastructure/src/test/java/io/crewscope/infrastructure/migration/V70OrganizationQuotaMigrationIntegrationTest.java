package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testcontainers.SchemaTemplates;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the V70 quota tables: typed limits with NULL as "dimension unconfigured", the
 * month-key shape shared with the usage rollup, the RESTRICT principal ownership, and the
 * task intake COUNT support index. No seed rows — the default state is empty.
 */
class V70OrganizationQuotaMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String SCHEMA = "crewscope";
    private static final String EARLY = "'2026-01-10T00:00:00Z'::timestamptz";

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
    void createsTheQuotaTablesConstraintsAndIntakeIndexWithoutSeedRows() throws SQLException {
        // The setup migrates without a target, i.e. to the default chain's tip; TIP is
        // that tier (a numeric tier is impossible here: the chain skips V71, so the
        // history row count never equals the tip's version number).
        currentLease = SchemaTemplates.forPostgres().lease(SchemaTemplates.TIP);
        flyway().migrate();

        Set<String> configurationConstraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name = 'organization_quota_configuration'
                """);
        assertTrue(configurationConstraints.contains(
                "fk_organization_quota_configuration_organization"));
        assertTrue(configurationConstraints.contains(
                "fk_organization_quota_configuration_created_by"));
        assertTrue(configurationConstraints.contains(
                "ck_organization_quota_configuration_intake"));
        assertTrue(configurationConstraints.contains(
                "ck_organization_quota_configuration_token"));
        assertTrue(configurationConstraints.contains(
                "ck_organization_quota_configuration_version"));
        assertTrue(configurationConstraints.contains(
                "ck_organization_quota_configuration_timestamps"));

        Set<String> ledgerConstraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name = 'organization_quota_token_ledger'
                """);
        assertTrue(ledgerConstraints.contains("fk_organization_quota_token_ledger_organization"));
        assertTrue(ledgerConstraints.contains("ck_organization_quota_token_ledger_month"));
        assertTrue(ledgerConstraints.contains("ck_organization_quota_token_ledger_counters"));

        assertTrue(queryStrings(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'crewscope' AND tablename = 'task'
                """)
                .contains("ix_task_organization_created"));
        // The default state is "no row" for both aggregates: no seed, no backfill.
        assertEquals(0, queryInt("SELECT count(*) FROM crewscope.organization_quota_configuration"));
        assertEquals(0, queryInt("SELECT count(*) FROM crewscope.organization_quota_token_ledger"));
    }

    @Test
    void limitsEnforceTheLowerBoundWhileNullStaysUnconfigured() throws SQLException {
        currentLease = SchemaTemplates.forPostgres().lease(SchemaTemplates.TIP);
        flyway().migrate();
        UUID organization = insertOrganization();
        UUID principal = insertPrincipal(organization);

        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertConfiguration(organization, principal, 0, null))
                .getMessage()
                .contains("ck_organization_quota_configuration_intake"));
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertConfiguration(organization, principal, null, 0L))
                .getMessage()
                .contains("ck_organization_quota_configuration_token"));
        // NULL per dimension is the legal "unconfigured" state, including both at once.
        insertConfiguration(organization, principal, null, null);
        UUID secondOrganization = insertOrganization();
        insertConfiguration(secondOrganization, insertPrincipal(secondOrganization), 1, 1L);
        assertEquals(
                2, queryInt("SELECT count(*) FROM crewscope.organization_quota_configuration"));
    }

    @Test
    void ledgerMonthsMustMatchTheRollupMonthShape() throws SQLException {
        currentLease = SchemaTemplates.forPostgres().lease(SchemaTemplates.TIP);
        flyway().migrate();
        UUID organization = insertOrganization();
        UUID principal = insertPrincipal(organization);

        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertLedger(organization, principal, "2026-13"))
                .getMessage()
                .contains("ck_organization_quota_token_ledger_month"));
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertLedger(organization, principal, "2026-1"))
                .getMessage()
                .contains("ck_organization_quota_token_ledger_month"));
        insertLedger(organization, principal, "2026-10");
        assertEquals(1, queryInt("SELECT count(*) FROM crewscope.organization_quota_token_ledger"));
    }

    @Test
    void ownershipColumnsRestrictAgainstMissingRows() throws SQLException {
        currentLease = SchemaTemplates.forPostgres().lease(SchemaTemplates.TIP);
        flyway().migrate();

        assertThrows(
                SQLException.class,
                () -> insertConfiguration(UUID.randomUUID(), UUID.randomUUID(), 5, 100L));
        assertThrows(
                SQLException.class,
                () -> insertLedger(UUID.randomUUID(), UUID.randomUUID(), "2026-10"));
    }

    private static UUID insertOrganization() throws SQLException {
        UUID organization = UUID.randomUUID();
        execute(
                "INSERT INTO crewscope.organization (id, name, status)"
                        + " VALUES ('%s', 'Org', 'ACTIVE')".formatted(organization));
        return organization;
    }

    private static UUID insertPrincipal(UUID organizationId) throws SQLException {
        UUID principal = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'Quota Writer', 'ACTIVE')
                """
                .formatted(principal, organizationId));
        return principal;
    }

    private static void insertConfiguration(
            UUID organizationId, UUID principalId, Integer intakeLimit, Long tokenBudget)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.organization_quota_configuration (
                    organization_id, monthly_task_intake_limit, monthly_token_budget, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', %s, %s, 0, %s, '%s', %s, '%s')
                """
                .formatted(
                        organizationId,
                        intakeLimit == null ? "NULL" : intakeLimit.toString(),
                        tokenBudget == null ? "NULL" : tokenBudget.toString(),
                        EARLY, principalId, EARLY, principalId));
    }

    private static void insertLedger(
            UUID organizationId, UUID principalId, String usageMonth) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.organization_quota_token_ledger (
                    organization_id, usage_month, reserved_tokens, reservation_sequence, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', 0, 0, 0, %s, '%s', %s, '%s')
                """
                .formatted(organizationId, usageMonth, EARLY, principalId, EARLY, principalId));
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

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                currentLease.jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int execute(String sql) throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static int queryInt(String sql) throws SQLException {
        return Integer.parseInt(queryString(sql));
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        java.util.HashSet<String> values = new java.util.HashSet<>();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }
}
