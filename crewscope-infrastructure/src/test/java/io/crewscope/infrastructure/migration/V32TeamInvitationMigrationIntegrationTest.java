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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Locks down V32 one-time Team invitations and digest-only persistence. */
class V32TeamInvitationMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_31 = MigrationVersion.fromVersion("31");
    private static final MigrationVersion VERSION_32 = MigrationVersion.fromVersion("32");
    private static final String CREATED = "TIMESTAMPTZ '2026-08-28 10:00:00+00'";
    private static final String RESOLVED = "TIMESTAMPTZ '2026-08-28 10:10:00+00'";
    private static final String EXPIRES = "TIMESTAMPTZ '2026-08-28 11:00:00+00'";

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS v32_probe CASCADE");
            statement.execute("DROP ROLE IF EXISTS v32_metadata_reader");
            statement.execute("CREATE SCHEMA v32_probe");
        }
    }

    @Test
    void migratesEmptyDatabaseWithNonDefaultSearchPathAndConvergesAtV32()
            throws SQLException {
        Flyway target = flyway(
                POSTGRES.getJdbcUrl() + "&currentSchema=v32_probe", VERSION_32);

        assertEquals(32, target.migrate().migrationsExecuted);
        target.validate();
        assertEquals("32", target.info().current().getVersion().getVersion());
        assertEquals(1, relationCount("crewscope", "team_invitation"));
        assertEquals(1, relationCount("crewscope", "team_invitation_metadata"));
        assertEquals(0, relationCount("v32_probe", "team_invitation"));
        assertEquals(0, target.migrate().migrationsExecuted);
    }

    @Test
    void upgradesV31WithoutChangingIdentityTeamOrAppendOnlyEventFacts() throws SQLException {
        Flyway source = flyway(POSTGRES.getJdbcUrl(), VERSION_31);
        assertEquals(31, source.migrate().migrationsExecuted);
        UUID organizationId = insertOrganization("Existing Organization");
        UUID teamId = insertTeam(organizationId, "Existing Team");
        UUID principalId = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID accountId = insertAccount("existing-user", "existing@example.com");
        insertBinding(accountId, organizationId, principalId);
        insertDomainEvent(organizationId, teamId, principalId);

        Flyway target = flyway(POSTGRES.getJdbcUrl(), VERSION_32);
        assertEquals(1, target.migrate().migrationsExecuted);
        target.validate();

        assertEquals(1, scalar("SELECT COUNT(*) FROM crewscope.user_account"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM crewscope.account_organization_binding"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM crewscope.team"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM crewscope.domain_event"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM crewscope.team_invitation"));
    }

    @Test
    void enforcesDigestRoleIssueScopeAndTerminalShape() throws SQLException {
        migrateLatest();
        UUID organizationId = insertOrganization("Primary Organization");
        UUID teamId = insertTeam(organizationId, "Primary Team");
        UUID inviterId = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID invitationId = insertInvitation(
                organizationId, teamId, inviterId, digest('a'), EXPIRES);

        assertSqlState("23505", () -> insertInvitation(
                organizationId, teamId, inviterId, digest('a'), EXPIRES));
        assertSqlState("23514", () -> insertInvitation(
                organizationId, teamId, inviterId, "plaintext-invitation-token", EXPIRES));
        assertSqlState("23514", () -> execute(invitationInsertSql(
                UUID.randomUUID(), organizationId, teamId, inviterId, digest('b'),
                "TEAM_OWNER", EXPIRES)));

        UUID otherOrganizationId = insertOrganization("Other Organization");
        UUID otherTeamId = insertTeam(otherOrganizationId, "Other Team");
        UUID otherInviterId = insertPrincipal(
                otherOrganizationId, "USER", null, "ACTIVE");
        assertSqlState("23503", () -> insertInvitation(
                organizationId, otherTeamId, inviterId, digest('c'), EXPIRES));
        assertSqlState("23503", () -> insertInvitation(
                organizationId, teamId, otherInviterId, digest('d'), EXPIRES));

        UUID servicePrincipalId = insertPrincipal(
                organizationId, "SERVICE", null, "ACTIVE");
        assertSqlState("23514", () -> insertInvitation(
                organizationId, teamId, servicePrincipalId, digest('e'), EXPIRES));

        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.team_invitation
                SET status = 'EXPIRED', resolved_at = %s, version = 1, updated_at = %s
                WHERE id = '%s'
                """.formatted(RESOLVED, RESOLVED, invitationId)));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.team_invitation
                SET status = 'REVOKED', resolved_at = %s, version = 2, updated_at = %s
                WHERE id = '%s'
                """.formatted(RESOLVED, RESOLVED, invitationId)));

        execute("""
                UPDATE crewscope.team_invitation
                SET status = 'REVOKED', resolved_at = %s, version = 1, updated_at = %s
                WHERE id = '%s'
                """.formatted(RESOLVED, RESOLVED, invitationId));
        assertSqlState("23514", () -> execute("""
                UPDATE crewscope.team_invitation
                SET status = 'EXPIRED', resolved_at = TIMESTAMPTZ '2026-08-28 11:01:00+00',
                    version = 2, updated_at = TIMESTAMPTZ '2026-08-28 11:01:00+00'
                WHERE id = '%s'
                """.formatted(invitationId)));
        assertSqlState("23514", () -> execute(
                "DELETE FROM crewscope.team_invitation WHERE id = '" + invitationId + "'"));
    }

    @Test
    void concurrentAcceptanceProducesExactlyOneTerminalCommit() throws Exception {
        migrateLatest();
        UUID organizationId = insertOrganization("Primary Organization");
        UUID teamId = insertTeam(organizationId, "Primary Team");
        UUID principalId = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID accountId = insertAccount("accepting-user", "target@example.com");
        insertBinding(accountId, organizationId, principalId);
        UUID memberId = insertActiveMember(organizationId, teamId, principalId, principalId);
        UUID invitationId = insertInvitation(
                organizationId, teamId, principalId, digest('f'), EXPIRES);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(
                    () -> acceptOnce(barrier, invitationId, accountId, memberId));
            Future<Integer> second = executor.submit(
                    () -> acceptOnce(barrier, invitationId, accountId, memberId));

            assertEquals(1, first.get() + second.get());
        } finally {
            executor.shutdownNow();
        }

        assertEquals("ACCEPTED", stringScalar("""
                SELECT status FROM crewscope.team_invitation WHERE id = '%s'
                """.formatted(invitationId)));
        assertEquals(1, scalar("""
                SELECT version FROM crewscope.team_invitation WHERE id = '%s'
                """.formatted(invitationId)));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM crewscope.team_invitation
                WHERE id = '%s' AND accepted_by_account_id = '%s' AND accepted_member_id = '%s'
                """.formatted(invitationId, accountId, memberId)));
    }

    @Test
    void acceptanceRequiresActiveAccountMatchingTargetAndActiveTeam() throws SQLException {
        migrateLatest();
        UUID organizationId = insertOrganization("Primary Organization");
        UUID teamId = insertTeam(organizationId, "Primary Team");
        UUID inviterId = insertPrincipal(organizationId, "USER", null, "ACTIVE");

        UUID wrongEmailPrincipal = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID wrongEmailAccount = insertAccount("wrong-email", "wrong@example.com");
        insertBinding(wrongEmailAccount, organizationId, wrongEmailPrincipal);
        UUID wrongEmailMember = insertActiveMember(
                organizationId, teamId, wrongEmailPrincipal, inviterId);
        UUID targetedInvitation = insertInvitation(
                organizationId, teamId, inviterId, digest('7'), EXPIRES);
        assertSqlState("23514", () -> acceptInvitation(
                targetedInvitation, wrongEmailAccount, wrongEmailMember));

        UUID disabledPrincipal = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID disabledAccount = insertAccount("disabled-account", "disabled@example.com");
        insertBinding(disabledAccount, organizationId, disabledPrincipal);
        UUID disabledMember = insertActiveMember(
                organizationId, teamId, disabledPrincipal, inviterId);
        UUID disabledInvitation = insertInvitation(
                organizationId,
                teamId,
                inviterId,
                digest('8'),
                EXPIRES,
                "disabled@example.com");
        execute("""
                UPDATE crewscope.user_account
                SET status = 'DISABLED', security_version = 2, version = 1, updated_at = %s
                WHERE id = '%s'
                """.formatted(RESOLVED, disabledAccount));
        assertSqlState("23514", () -> acceptInvitation(
                disabledInvitation, disabledAccount, disabledMember));

        UUID archivedPrincipal = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID archivedAccount = insertAccount("archived-team", "archived@example.com");
        insertBinding(archivedAccount, organizationId, archivedPrincipal);
        UUID archivedMember = insertActiveMember(
                organizationId, teamId, archivedPrincipal, inviterId);
        UUID archivedInvitation = insertInvitation(
                organizationId,
                teamId,
                inviterId,
                digest('9'),
                EXPIRES,
                "archived@example.com");
        execute("""
                UPDATE crewscope.team
                SET status = 'ARCHIVED', version = version + 1, updated_at = %s
                WHERE organization_id = '%s' AND id = '%s'
                """.formatted(RESOLVED, organizationId, teamId));
        assertSqlState("23514", () -> acceptInvitation(
                archivedInvitation, archivedAccount, archivedMember));
    }

    @Test
    void acceptanceHoldsStatusLocksAgainstConcurrentBindingDisable() throws SQLException {
        migrateLatest();
        UUID organizationId = insertOrganization("Primary Organization");
        UUID teamId = insertTeam(organizationId, "Primary Team");
        UUID principalId = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID accountId = insertAccount("locking-user", "target@example.com");
        UUID bindingId = insertBinding(accountId, organizationId, principalId);
        UUID memberId = insertActiveMember(organizationId, teamId, principalId, principalId);
        UUID invitationId = insertInvitation(
                organizationId, teamId, principalId, digest('0'), EXPIRES);

        try (Connection accepting = open();
                PreparedStatement acceptance = accepting.prepareStatement(acceptanceSql());
                Connection disabling = open();
                Statement disable = disabling.createStatement()) {
            accepting.setAutoCommit(false);
            acceptance.setObject(1, accountId);
            acceptance.setObject(2, memberId);
            acceptance.setObject(3, invitationId);
            assertEquals(1, acceptance.executeUpdate());

            disable.execute("SET lock_timeout = '100ms'");
            SQLException blocked = assertThrows(SQLException.class, () -> disable.execute("""
                    UPDATE crewscope.account_organization_binding
                    SET status = 'DISABLED', version = version + 1, updated_at = %s
                    WHERE id = '%s'
                    """.formatted(RESOLVED, bindingId)));
            assertEquals("55P03", blocked.getSQLState());
            accepting.rollback();
        }
    }

    @Test
    void supportsBoundedExpiryScanAndHidesDigestFromGenericReaders() throws SQLException {
        migrateLatest();
        UUID organizationId = insertOrganization("Primary Organization");
        UUID teamId = insertTeam(organizationId, "Primary Team");
        UUID inviterId = insertPrincipal(organizationId, "USER", null, "ACTIVE");
        UUID firstExpired = insertInvitation(
                organizationId,
                teamId,
                inviterId,
                digest('1'),
                "TIMESTAMPTZ '2026-08-28 10:05:00+00'");
        UUID secondExpired = insertInvitation(
                organizationId,
                teamId,
                inviterId,
                digest('2'),
                "TIMESTAMPTZ '2026-08-28 10:15:00+00'");
        insertInvitation(organizationId, teamId, inviterId, digest('3'), EXPIRES);

        assertEquals(Set.of(firstExpired, secondExpired), queryIds("""
                SELECT id FROM crewscope.team_invitation
                WHERE status = 'PENDING'
                  AND expires_at <= TIMESTAMPTZ '2026-08-28 10:20:00+00'
                ORDER BY expires_at, id
                """));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'crewscope'
                  AND indexname = 'ix_team_invitation_pending_expiry_v32'
                """));
        assertEquals(0, scalar("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'crewscope'
                  AND table_name = 'team_invitation_metadata'
                  AND column_name = 'token_digest'
                """));
        assertEquals(0, scalar("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'crewscope'
                  AND table_name = 'team_invitation'
                  AND column_name IN ('token', 'raw_token', 'plaintext_token', 'token_value')
                """));
        assertEquals(3, scalar("""
                SELECT COUNT(*) FROM crewscope.team_invitation
                WHERE CHAR_LENGTH(token_digest) = 64
                  AND token_digest ~ '^[0-9a-f]{64}$'
                """));

        execute("CREATE ROLE v32_metadata_reader NOLOGIN");
        execute("GRANT USAGE ON SCHEMA crewscope TO v32_metadata_reader");
        execute("GRANT SELECT ON crewscope.team_invitation_metadata TO v32_metadata_reader");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE v32_metadata_reader");
            assertEquals(3, queryScalar(
                    statement, "SELECT COUNT(*) FROM crewscope.team_invitation_metadata"));
            SQLException denied = assertThrows(SQLException.class, () -> queryScalar(
                    statement, "SELECT token_digest FROM crewscope.team_invitation"));
            assertEquals("42501", denied.getSQLState());
            statement.execute("RESET ROLE");
        }
    }

    @Test
    void rollsBackWholeMigrationWhenReservedInvitationRelationConflicts()
            throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_31).migrate();
        execute("CREATE TABLE crewscope.team_invitation (collision INTEGER PRIMARY KEY)");

        assertThrows(
                FlywayException.class,
                () -> flyway(POSTGRES.getJdbcUrl(), VERSION_32).migrate());

        assertEquals(1, relationCount("crewscope", "team_invitation"));
        assertEquals(0, relationCount("crewscope", "team_invitation_metadata"));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = 'team_invitation'
                  AND column_name = 'collision'
                """));
        assertEquals("31", flyway(POSTGRES.getJdbcUrl(), VERSION_32)
                .info().current().getVersion().getVersion());
    }

    private static int acceptOnce(
            CyclicBarrier barrier, UUID invitationId, UUID accountId, UUID memberId)
            throws Exception {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(acceptanceSql())) {
            statement.setObject(1, accountId);
            statement.setObject(2, memberId);
            statement.setObject(3, invitationId);
            barrier.await();
            return statement.executeUpdate();
        }
    }

    private static void acceptInvitation(
            UUID invitationId, UUID accountId, UUID memberId) throws SQLException {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(acceptanceSql())) {
            statement.setObject(1, accountId);
            statement.setObject(2, memberId);
            statement.setObject(3, invitationId);
            statement.executeUpdate();
        }
    }

    private static String acceptanceSql() {
        return """
                UPDATE crewscope.team_invitation
                SET status = 'ACCEPTED', accepted_by_account_id = ?,
                    accepted_member_id = ?, resolved_at = %s,
                    version = version + 1, updated_at = %s
                WHERE id = ? AND status = 'PENDING' AND version = 0
                """.formatted(RESOLVED, RESOLVED);
    }

    private static UUID insertInvitation(
            UUID organizationId,
            UUID teamId,
            UUID inviterId,
            String tokenDigest,
            String expiresAt)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(invitationInsertSql(
                id, organizationId, teamId, inviterId, tokenDigest, "MEMBER", expiresAt));
        return id;
    }

    private static UUID insertInvitation(
            UUID organizationId,
            UUID teamId,
            UUID inviterId,
            String tokenDigest,
            String expiresAt,
            String targetEmail)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(invitationInsertSql(
                id,
                organizationId,
                teamId,
                inviterId,
                tokenDigest,
                "MEMBER",
                expiresAt,
                targetEmail));
        return id;
    }

    private static String invitationInsertSql(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID inviterId,
            String tokenDigest,
            String targetRole,
            String expiresAt) {
        return invitationInsertSql(
                id,
                organizationId,
                teamId,
                inviterId,
                tokenDigest,
                targetRole,
                expiresAt,
                "target@example.com");
    }

    private static String invitationInsertSql(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID inviterId,
            String tokenDigest,
            String targetRole,
            String expiresAt,
            String targetEmail) {
        return """
                INSERT INTO crewscope.team_invitation (
                    id, organization_id, team_id, invited_by_principal_id,
                    target_email_normalized, target_role, token_digest,
                    expires_at, status, version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    %s, 'PENDING', 0, %s, %s)
                """.formatted(
                id,
                organizationId,
                teamId,
                inviterId,
                targetEmail,
                targetRole,
                tokenDigest,
                expiresAt,
                CREATED,
                CREATED);
    }

    private static UUID insertOrganization(String name) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.organization (id, name, status, created_at, updated_at)
                VALUES ('%s', '%s', 'ACTIVE', %s, %s)
                """.formatted(id, name, CREATED, CREATED));
        return id;
    }

    private static UUID insertTeam(UUID organizationId, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.team (
                    id, organization_id, name, status, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', 'ACTIVE', %s, %s)
                """.formatted(id, organizationId, name, CREATED, CREATED));
        return id;
    }

    private static UUID insertPrincipal(
            UUID organizationId, String type, UUID teamId, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        String teamValue = teamId == null ? "NULL" : "'" + teamId + "'";
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status, created_at, updated_at
                ) VALUES ('%s', '%s', %s, '%s', 'Invitation Principal',
                    'ORGANIZATION', '%s', %s, %s)
                """.formatted(
                id, organizationId, teamValue, type, status, CREATED, CREATED));
        return id;
    }

    private static UUID insertAccount(String username, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized,
                    display_name, status, platform_role, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', '%s', 'Invitation User',
                    'ACTIVE', 'USER', %s, %s)
                """.formatted(id, username, username, email, email, CREATED, CREATED));
        return id;
    }

    private static UUID insertBinding(
            UUID accountId, UUID organizationId, UUID principalId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.account_organization_binding (
                    id, account_id, organization_id, principal_id,
                    status, version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 0, %s, %s)
                """.formatted(id, accountId, organizationId, principalId, CREATED, CREATED));
        return id;
    }

    private static UUID insertActiveMember(
            UUID organizationId, UUID teamId, UUID principalId, UUID inviterId)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, invited_by_principal_id, joined_at,
                    version, created_at, updated_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 'INVITATION', '%s',
                    %s, 0, %s, %s)
                """.formatted(
                id, organizationId, teamId, principalId, inviterId, CREATED, CREATED, CREATED));
        return id;
    }

    private static void insertDomainEvent(
            UUID organizationId, UUID teamId, UUID actorId) throws SQLException {
        execute("""
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    subject_type, subject_id, actor_type, actor_id, correlation_id,
                    occurred_at, payload, aggregate_version
                ) VALUES ('%s', 'TeamInvitationLegacyFact', 'V1', '%s', '%s',
                    'Team', '%s', 'USER', '%s', '%s', %s, '{}'::JSONB, 0)
                """.formatted(
                UUID.randomUUID(),
                organizationId,
                teamId,
                teamId,
                actorId,
                UUID.randomUUID(),
                CREATED));
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static void migrateLatest() {
        flyway(POSTGRES.getJdbcUrl(), VERSION_32).migrate();
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
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

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int queryScalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            int value = result.getInt(1);
            assertFalse(result.next());
            return value;
        }
    }

    private static int scalar(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            return queryScalar(statement, sql);
        }
    }

    private static String stringScalar(String sql) throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            String value = result.getString(1);
            assertFalse(result.next());
            return value;
        }
    }

    private static Set<UUID> queryIds(String sql) throws SQLException {
        Set<UUID> ids = new HashSet<>();
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                ids.add(result.getObject(1, UUID.class));
            }
        }
        return Set.copyOf(ids);
    }

    private static int relationCount(String schema, String relation) throws SQLException {
        return scalar("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = '%s' AND table_name = '%s'
                """.formatted(schema, relation));
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
