package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * Verifies the V68 organization_role_grant and organization_setting tables: the append-only grant
 * backstops plus the three backfill states — earliest ACTIVE binding becomes the sole ORG_OWNER,
 * organizations with an owner are left alone, and databases without bindings stay empty.
 */
class V68OrganizationRoleAndSettingMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String SCHEMA = "crewscope";
    private static final MigrationVersion VERSION_67 = MigrationVersion.fromVersion("67");
    private static final String EARLY = "'2026-01-10T00:00:00Z'::timestamptz";
    private static final String LATE = "'2026-03-20T00:00:00Z'::timestamptz";

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void createsTheRoleAndSettingTablesWithTheirBackstops() throws SQLException {
        flyway(null).migrate();

        Set<String> grantConstraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name = 'organization_role_grant'
                """);
        assertTrue(grantConstraints.contains("fk_organization_role_grant_organization"));
        assertTrue(grantConstraints.contains("fk_organization_role_grant_principal"));
        assertTrue(grantConstraints.contains("ck_organization_role_grant_role"));
        assertTrue(grantConstraints.contains("ck_organization_role_grant_status"));
        assertTrue(grantConstraints.contains("ck_organization_role_grant_revocation"));

        String activeIndex = indexDefinition("ux_organization_role_grant_active");
        assertTrue(activeIndex.startsWith("CREATE UNIQUE INDEX"));
        assertTrue(activeIndex.contains("(organization_id, principal_id, role)"));
        assertTrue(
                activeIndex.toLowerCase(java.util.Locale.ROOT).contains("where ((status)::text = 'active'"));
        assertFalse(
                indexDefinition("ix_organization_role_grant_organization_role")
                        .startsWith("CREATE UNIQUE INDEX"));

        Set<String> settingConstraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name = 'organization_setting'
                """);
        assertTrue(settingConstraints.contains("ck_organization_setting_retention"));
        assertTrue(settingConstraints.contains("fk_organization_setting_organization"));
    }

    @Test
    void backfillsTheEarliestActiveBindingAsTheSoleOwnerPerOrganization() throws SQLException {
        flyway(VERSION_67).migrate();
        UUID firstOrganization = UUID.randomUUID();
        UUID secondOrganization = UUID.randomUUID();
        UUID earlyAccount = insertAccount("early");
        UUID lateAccount = insertAccount("late");
        UUID secondAccount = insertAccount("second");
        insertOrganization(firstOrganization);
        insertOrganization(secondOrganization);
        UUID earlyPrincipal =
                insertPrincipal(firstOrganization, "Early Operator");
        UUID latePrincipal = insertPrincipal(firstOrganization, "Late Operator");
        UUID secondPrincipal = insertPrincipal(secondOrganization, "Second Operator");
        insertBinding(earlyAccount, firstOrganization, earlyPrincipal, EARLY);
        insertBinding(lateAccount, firstOrganization, latePrincipal, LATE);
        insertBinding(secondAccount, secondOrganization, secondPrincipal, EARLY);

        flyway(null).migrate();

        assertEquals(
                earlyPrincipal.toString(),
                queryString(
                        """
                        SELECT principal_id FROM crewscope.organization_role_grant
                        WHERE organization_id = '%s' AND role = 'ORG_OWNER' AND status = 'ACTIVE'
                        """.formatted(firstOrganization)));
        assertEquals(
                1,
                queryInt(
                        """
                        SELECT count(*) FROM crewscope.organization_role_grant
                        WHERE organization_id = '%s'
                        """.formatted(firstOrganization)));
        assertEquals(
                secondPrincipal.toString(),
                queryString(
                        """
                        SELECT principal_id FROM crewscope.organization_role_grant
                        WHERE organization_id = '%s' AND role = 'ORG_OWNER'
                        """.formatted(secondOrganization)));
    }

    @Test
    void leavesOrganizationsWithoutActiveBindingsEmpty() throws SQLException {
        flyway(VERSION_67).migrate();
        UUID organization = UUID.randomUUID();
        insertOrganization(organization);
        UUID account = insertAccount("disabled");
        UUID principal = insertPrincipal(organization, "Disabled Operator");
        // A DISABLED binding is not an owner candidate; nothing else exists to backfill.
        execute(
                """
                INSERT INTO crewscope.account_organization_binding (
                    id, account_id, organization_id, principal_id,
                    status, version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', 'DISABLED', 0, %s, %s)
                """.formatted(
                        UUID.randomUUID(), account, organization, principal, EARLY, EARLY));

        flyway(null).migrate();

        assertEquals(
                0,
                queryInt("SELECT count(*) FROM crewscope.organization_role_grant"));
        assertEquals(
                0, queryInt("SELECT count(*) FROM crewscope.organization_setting"));
    }

    @Test
    void partialUniqueIndexAllowsRevokedHistoryButBlocksASecondActive() throws SQLException {
        flyway(null).migrate();
        UUID organization = insertOrganization(UUID.randomUUID());
        UUID principal = insertPrincipal(organization, "Grant Holder");
        insertGrant(organization, principal, "ORG_ADMIN", "ACTIVE");
        insertGrant(organization, principal, "ORG_AUDITOR", "ACTIVE");

        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertGrant(organization, principal, "ORG_ADMIN", "ACTIVE"))
                .getMessage()
                .contains("ux_organization_role_grant_active"));

        // Revoked history coexists with a fresh ACTIVE grant of the same role.
        execute(
                """
                UPDATE crewscope.organization_role_grant
                SET status = 'REVOKED', revoked_at = %s, revoked_by_principal_id = '%s',
                    updated_at = %s, updated_by_principal_id = '%s'
                WHERE organization_id = '%s' AND principal_id = '%s' AND role = 'ORG_ADMIN'
                """.formatted(
                        LATE, principal, LATE, principal, organization, principal));
        insertGrant(organization, principal, "ORG_ADMIN", "ACTIVE");
        assertEquals(
                2,
                queryInt(
                        """
                        SELECT count(*) FROM crewscope.organization_role_grant
                        WHERE organization_id = '%s' AND principal_id = '%s' AND role = 'ORG_ADMIN'
                        """.formatted(organization, principal)));
    }

    @Test
    void retentionCheckEnforcesTheThirtyDayFloorAndForeverDefault() throws SQLException {
        flyway(null).migrate();
        UUID organization = insertOrganization(UUID.randomUUID());
        UUID principal = insertPrincipal(organization, "Setting Writer");

        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertSetting(organization, principal, 29))
                .getMessage()
                .contains("ck_organization_setting_retention"));
        // NULL (forever) and exactly 30 days are both legal materializations.
        insertSetting(organization, principal, null);
        UUID floorOrganization = insertOrganization(UUID.randomUUID());
        UUID floorPrincipal = insertPrincipal(floorOrganization, "Floor Writer");
        insertSetting(floorOrganization, floorPrincipal, 30);
        assertEquals(
                2, queryInt("SELECT count(*) FROM crewscope.organization_setting"));
    }

    private static UUID insertOrganization(UUID organization) throws SQLException {
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES ('%s', 'Org', 'ACTIVE')"
                        .formatted(organization));
        return organization;
    }

    private static UUID insertPrincipal(UUID organizationId, String displayName)
            throws SQLException {
        UUID principal = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', '%s', 'ACTIVE')
                """
                .formatted(principal, organizationId, displayName));
        return principal;
    }

    private static UUID insertAccount(String username) throws SQLException {
        UUID account = UUID.randomUUID();
        String email = username + "@example.test";
        execute(
                """
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized,
                    display_name, status, platform_role, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'ACTIVE', 'USER', %s, %s)
                """
                .formatted(account, username, username, email, email, "User " + username,
                        EARLY, EARLY));
        return account;
    }

    private static UUID insertBinding(
            UUID accountId, UUID organizationId, UUID principalId, String createdAt)
            throws SQLException {
        UUID binding = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.account_organization_binding (
                    id, account_id, organization_id, principal_id,
                    status, version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 0, %s, %s)
                """
                .formatted(binding, accountId, organizationId, principalId,
                        createdAt, createdAt));
        return binding;
    }

    private static void insertGrant(
            UUID organizationId, UUID principalId, String role, String status) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.organization_role_grant (
                    id, organization_id, principal_id, role, status,
                    granted_by_principal_id, reason, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', NULL, 0, %s, '%s', %s, '%s')
                """
                .formatted(UUID.randomUUID(), organizationId, principalId, role, status,
                        principalId, EARLY, principalId, EARLY, principalId));
    }

    private static void insertSetting(UUID organizationId, UUID principalId, Integer days)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.organization_setting (
                    organization_id, audit_event_retention_days, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', %s, 0, %s, '%s', %s, '%s')
                """
                .formatted(organizationId,
                        days == null ? "NULL" : days.toString(),
                        EARLY, principalId, EARLY, principalId));
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

    private static String indexDefinition(String indexName) throws SQLException {
        return queryString(
                """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'crewscope' AND indexname = '%s'
                """.formatted(indexName));
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
