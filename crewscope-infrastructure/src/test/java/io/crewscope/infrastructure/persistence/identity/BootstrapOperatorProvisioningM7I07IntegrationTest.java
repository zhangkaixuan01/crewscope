package io.crewscope.infrastructure.persistence.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.BootstrapOperatorLock;
import io.crewscope.application.identity.BootstrapOperatorPasswordHasher;
import io.crewscope.application.identity.BootstrapOperatorPasswordVerification;
import io.crewscope.application.identity.BootstrapOperatorProvisioning;
import io.crewscope.application.identity.BootstrapOperatorProvisioningException;
import io.crewscope.application.identity.BootstrapOperatorProvisioningResult;
import io.crewscope.application.identity.BootstrapOperatorProvisioningService;
import io.crewscope.application.identity.LocalCredentialAuthenticationMaterial;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.team.JpaPrincipalRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.PrincipalEntity;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.security.password.LocalBootstrapOperatorPasswordHasher;
import io.crewscope.infrastructure.security.password.PasswordHashingConfiguration;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** PostgreSQL proof for the V30-to-Account Bootstrap Operator upgrade and Secret rotation. */
@SpringBootTest(
        classes = BootstrapOperatorProvisioningM7I07IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false",
            "crewscope.security.password.hash-permits=2"
        })
class BootstrapOperatorProvisioningM7I07IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_30 = MigrationVersion.fromVersion("30");
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-28T15:00:00Z");
    private static final String INITIAL_SECRET = "M7-I07-initial-secret-47";
    private static final UUID V30_ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID V30_PRINCIPAL_ID = UUID.randomUUID();
    private static final UUID V30_TEAM_ID = UUID.randomUUID();
    private static final UUID V30_MEMBER_ID = UUID.randomUUID();
    private static final UUID V30_AUDIT_ID = UUID.randomUUID();

    @Autowired private BootstrapOperatorProvisioningService service;
    @Autowired private BootstrapOperatorPasswordHasher passwordHasher;
    @Autowired private LocalCredentialStore credentialStore;
    @Autowired private UserAccountRepository accounts;
    @Autowired private LoginIdentityRepository identities;
    @Autowired private AccountOrganizationBindingRepository bindings;
    @Autowired private PrincipalRepository principals;
    @Autowired private JdbcTemplate jdbc;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(8);

    @BeforeAll
    static void migrateRealV30FixtureToLatest() throws SQLException {
        execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        flyway(VERSION_30).migrate();
        seedV30Fixture();
        flyway(null).migrate();
    }

    @AfterAll
    static void stopExecutor() {
        EXECUTOR.shutdownNow();
    }

    @Test
    void upgradesV30PrincipalWithoutChangingMembershipOrAuditIdsAndIsIdempotent() {
        BootstrapOperatorProvisioning command = command(V30_ORGANIZATION_ID, INITIAL_SECRET);

        assertEquals("32", jdbc.queryForObject(
                "SELECT version FROM crewscope.flyway_schema_history "
                        + "WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class));
        // The V30 restore fixture must converge through both identity migrations to V32.
        assertEquals(0, count("team_invitation", ""));

        BootstrapOperatorProvisioningResult first = service.provision(command);
        BootstrapOperatorProvisioningResult repeated = service.provision(command);

        assertEquals(BootstrapOperatorProvisioningResult.CredentialAction.CREATED,
                first.credentialAction());
        assertEquals(BootstrapOperatorProvisioningResult.CredentialAction.UNCHANGED,
                repeated.credentialAction());
        assertEquals(first.accountId(), repeated.accountId());
        assertEquals(first.loginIdentityId(), repeated.loginIdentityId());
        assertEquals(first.bindingId(), repeated.bindingId());
        assertEquals(V30_PRINCIPAL_ID, first.principalId().value());
        assertEquals(V30_MEMBER_ID, jdbc.queryForObject(
                "SELECT id FROM crewscope.team_member WHERE id = ?", UUID.class, V30_MEMBER_ID));
        assertEquals(V30_PRINCIPAL_ID, jdbc.queryForObject(
                "SELECT user_principal_id FROM crewscope.team_member WHERE id = ?",
                UUID.class,
                V30_MEMBER_ID));
        assertEquals(V30_AUDIT_ID, jdbc.queryForObject(
                "SELECT event_id FROM crewscope.audit_event WHERE event_id = ?",
                UUID.class,
                V30_AUDIT_ID));
        assertEquals(1, count("user_account", "WHERE id = '" + first.accountId() + "'"));
        assertEquals(1, count("login_identity",
                "WHERE account_id = '" + first.accountId() + "'"));
        assertEquals(1, count("account_organization_binding",
                "WHERE organization_id = '" + V30_ORGANIZATION_ID + "'"));
        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM crewscope.local_credential WHERE account_id = ?",
                String.class,
                first.accountId().value());
        assertFalse(storedHash.contains(INITIAL_SECRET));
        assertFalse(command.toString().contains(INITIAL_SECRET));
    }

    @Test
    void rotatesChangedExternalSecretAndAdvancesAccountSecurityVersion() {
        OrganizationId organizationId = createOrganization("Secret Rotation");
        BootstrapOperatorProvisioningResult initial = service.provision(
                command(organizationId.value(), INITIAL_SECRET));
        long securityBefore = accountSecurityVersion(initial.accountId());
        String replacementSecret = "M7-I07-rotated-secret-83";

        BootstrapOperatorProvisioningResult rotated = service.provision(
                command(organizationId.value(), replacementSecret));
        LocalCredentialAuthenticationMaterial material = credentialStore
                .findByAccountIdForAuthentication(rotated.accountId())
                .orElseThrow();

        assertEquals(BootstrapOperatorProvisioningResult.CredentialAction.ROTATED,
                rotated.credentialAction());
        assertEquals(securityBefore + 1, accountSecurityVersion(rotated.accountId()));
        assertEquals(2L, material.metadata().credentialVersion().value());
        assertEquals(
                BootstrapOperatorPasswordVerification.MISMATCHED,
                passwordHasher.verify(INITIAL_SECRET, material.passwordHash().orElseThrow()));
        assertEquals(
                BootstrapOperatorPasswordVerification.MATCHED,
                passwordHasher.verify(replacementSecret, material.passwordHash().orElseThrow()));
        assertFalse(material.toString().contains(replacementSecret));
    }

    @Test
    void rehashesAnApprovedLegacyEncodingWithoutRevokingSessions() {
        OrganizationId organizationId = createOrganization("Legacy Password Encoding");
        Principal principal = insertPrincipal(
                organizationId,
                PrincipalType.USER,
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                "ACTIVE");
        String secret = "M7-I07-legacy-encoding-secret-73";
        UserAccount account = accounts.create(UserAccount.bootstrapOperator(
                UserAccountId.generate(),
                operatorUsername(organizationId.value()),
                operatorEmail(organizationId.value()),
                "CrewScope Operator",
                NOW));
        identities.create(LoginIdentity.local(LoginIdentityId.generate(), account.id(), NOW));
        LocalPasswordHash legacyHash = new LocalPasswordHash(
                "{bcrypt}" + new BCryptPasswordEncoder(10).encode(secret));
        insertCredential(account.id(), legacyHash);
        bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organizationId,
                principal,
                NOW));

        BootstrapOperatorProvisioningResult result =
                service.provision(command(organizationId.value(), secret));
        LocalCredentialAuthenticationMaterial material = credentialStore
                .findByAccountIdForAuthentication(account.id())
                .orElseThrow();

        assertEquals(BootstrapOperatorProvisioningResult.CredentialAction.REHASHED,
                result.credentialAction());
        assertEquals(account.securityVersion().value(), accountSecurityVersion(account.id()));
        assertEquals(2L, material.metadata().credentialVersion().value());
        assertTrue(material.passwordHash().orElseThrow().algorithm().isCurrentWriteAlgorithm());
        assertEquals(
                BootstrapOperatorPasswordVerification.MATCHED,
                passwordHasher.verify(secret, material.passwordHash().orElseThrow()));
    }

    @Test
    void rejectsConfiguredProfileDriftWithoutRotatingCredentialOrAccountVersions() {
        OrganizationId organizationId = createOrganization("Configured Profile Drift");
        BootstrapOperatorProvisioning initialCommand =
                command(organizationId.value(), INITIAL_SECRET);
        BootstrapOperatorProvisioningResult initial = service.provision(initialCommand);
        UserAccount accountBefore = accounts.findById(initial.accountId()).orElseThrow();
        LocalCredentialAuthenticationMaterial credentialBefore = credentialStore
                .findByAccountIdForAuthentication(initial.accountId())
                .orElseThrow();
        BootstrapOperatorProvisioning drifted = new BootstrapOperatorProvisioning(
                organizationId,
                "changed-" + operatorUsername(organizationId.value()),
                operatorEmail(organizationId.value()),
                "CrewScope Operator",
                "M7-I07-drifted-secret-29");

        assertThrows(
                BootstrapOperatorProvisioningException.class,
                () -> service.provision(drifted));

        UserAccount accountAfter = accounts.findById(initial.accountId()).orElseThrow();
        LocalCredentialAuthenticationMaterial credentialAfter = credentialStore
                .findByAccountIdForAuthentication(initial.accountId())
                .orElseThrow();
        assertEquals(accountBefore.version(), accountAfter.version());
        assertEquals(accountBefore.securityVersion(), accountAfter.securityVersion());
        assertEquals(
                credentialBefore.metadata().version(), credentialAfter.metadata().version());
        assertEquals(
                credentialBefore.passwordHash().orElseThrow().encodedValue(),
                credentialAfter.passwordHash().orElseThrow().encodedValue());
    }

    @Test
    void createsOneNewLegacyPrincipalOnlyWhenNoCandidateExists() {
        OrganizationId organizationId = createOrganization("Absent Legacy Principal");

        BootstrapOperatorProvisioningResult result = service.provision(
                command(organizationId.value(), "M7-I07-absent-principal-52"));

        Principal principal = principal(organizationId, result.principalId());
        assertEquals(PrincipalType.USER, principal.type());
        assertEquals(
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                principal.externalIdentity());
        assertEquals(PlatformRole.OPERATOR,
                accounts.findById(result.accountId()).orElseThrow().platformRole());
        assertEquals(1, count("principal", "WHERE organization_id = '" + organizationId + "'"));
    }

    @Test
    void concurrentStartupConvergesToOneCompleteIdentityChain() throws Exception {
        OrganizationId organizationId = createOrganization("Concurrent Bootstrap");
        BootstrapOperatorProvisioning command = command(
                organizationId.value(), "M7-I07-concurrent-secret-61");
        List<Future<BootstrapOperatorProvisioningResult>> futures = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            futures.add(EXECUTOR.submit(() -> service.provision(command)));
        }

        List<BootstrapOperatorProvisioningResult> results = new ArrayList<>();
        for (Future<BootstrapOperatorProvisioningResult> future : futures) {
            results.add(future.get());
        }

        assertEquals(1, results.stream()
                .filter(value -> value.credentialAction()
                        == BootstrapOperatorProvisioningResult.CredentialAction.CREATED)
                .count());
        assertEquals(1, results.stream().map(BootstrapOperatorProvisioningResult::accountId)
                .distinct().count());
        assertEquals(1, count("user_account",
                "WHERE id = '" + results.get(0).accountId() + "'"));
        assertEquals(1, count("account_organization_binding",
                "WHERE organization_id = '" + organizationId + "'"));
    }

    @Test
    void rejectsWrongLegacyPrincipalShapeBeforeCreatingAccount() {
        OrganizationId organizationId = createOrganization("Wrong Legacy Shape");
        insertPrincipal(
                organizationId,
                PrincipalType.SERVICE,
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                "ACTIVE");
        int accountsBefore = count("user_account", "");

        assertThrows(
                BootstrapOperatorProvisioningException.class,
                () -> service.provision(command(
                        organizationId.value(), "M7-I07-wrong-principal-64")));

        assertEquals(accountsBefore, count("user_account", ""));
        assertEquals(0, count("account_organization_binding",
                "WHERE organization_id = '" + organizationId + "'"));
    }

    @Test
    void rejectsExistingUserAccountBoundToTheLegacyPrincipal() {
        OrganizationId organizationId = createOrganization("Wrong Account Role");
        Principal principal = insertPrincipal(
                organizationId,
                PrincipalType.USER,
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                "ACTIVE");
        UserAccount account = accounts.create(UserAccount.register(
                UserAccountId.generate(),
                "ordinary-user",
                "ordinary-user@example.test",
                "Ordinary User",
                NOW));
        LoginIdentity identity = identities.create(
                LoginIdentity.local(LoginIdentityId.generate(), account.id(), NOW));
        LocalPasswordHash hash = passwordHasher.encode("M7-I07-existing-user-secret-92");
        credentialStore.create(
                LocalCredentialMetadata.create(
                        LocalCredentialId.generate(), account.id(), hash, NOW),
                hash);
        bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organizationId,
                principal,
                NOW));

        assertThrows(
                BootstrapOperatorProvisioningException.class,
                () -> service.provision(command(
                        organizationId.value(), "M7-I07-existing-user-secret-92")));

        assertEquals(PlatformRole.USER, accounts.findById(account.id()).orElseThrow().platformRole());
        assertEquals(identity.id(), identities.findById(identity.id()).orElseThrow().id());
    }

    @Test
    void fixedFailureAndCommandStringsNeverExposeThePassword() {
        OrganizationId missingOrganization = OrganizationId.generate();
        String secret = "M7-I07-never-log-this-secret-37";
        BootstrapOperatorProvisioning command = command(missingOrganization.value(), secret);

        BootstrapOperatorProvisioningException failure = assertThrows(
                BootstrapOperatorProvisioningException.class,
                () -> service.provision(command));

        assertFalse(command.toString().contains(secret));
        assertFalse(failure.toString().contains(secret));
        assertFalse(failure.getMessage().contains(missingOrganization.toString()));
    }

    private BootstrapOperatorProvisioning command(UUID organizationId, String password) {
        // Each fixture models an independent deployment whose configured operator is globally unique.
        return new BootstrapOperatorProvisioning(
                new OrganizationId(organizationId),
                operatorUsername(organizationId),
                operatorEmail(organizationId),
                "CrewScope Operator",
                password);
    }

    private static String operatorUsername(UUID organizationId) {
        return "operator-" + organizationId.toString().substring(0, 12);
    }

    private static String operatorEmail(UUID organizationId) {
        return operatorUsername(organizationId) + "@crewscope.local";
    }

    private OrganizationId createOrganization(String name) {
        OrganizationId organizationId = OrganizationId.generate();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                name);
        return organizationId;
    }

    private Principal insertPrincipal(
            OrganizationId organizationId,
            PrincipalType type,
            Optional<ExternalIdentity> externalIdentity,
            String status) {
        PrincipalId id = PrincipalId.generate();
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name,
                    identity_provider, external_subject, visibility, status
                ) VALUES (?, ?, ?, 'Legacy Operator', ?, ?, 'ORGANIZATION', ?)
                """,
                id.value(),
                organizationId.value(),
                type.name(),
                externalIdentity.map(ExternalIdentity::provider).orElse(null),
                externalIdentity.map(ExternalIdentity::subject).orElse(null),
                status);
        return principal(organizationId, id);
    }

    private Principal principal(OrganizationId organizationId, PrincipalId principalId) {
        return principals.findById(organizationId, principalId).orElseThrow();
    }

    private long accountSecurityVersion(UserAccountId accountId) {
        return jdbc.queryForObject(
                "SELECT security_version FROM crewscope.user_account WHERE id = ?",
                Long.class,
                accountId.value());
    }

    private void insertCredential(UserAccountId accountId, LocalPasswordHash passwordHash) {
        jdbc.update(
                """
                INSERT INTO crewscope.local_credential (
                    id, account_id, password_hash, algorithm, credential_version,
                    password_changed_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 1, ?, 0, ?, ?)
                """,
                LocalCredentialId.generate().value(),
                accountId.value(),
                passwordHash.encodedValue(),
                passwordHash.algorithm().encodingId(),
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime());
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope." + table + " " + predicate,
                Integer.class);
    }

    private static void seedV30Fixture() throws SQLException {
        execute("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V30 Organization', 'ACTIVE')
                """.formatted(V30_ORGANIZATION_ID));
        execute("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name,
                    identity_provider, external_subject, visibility, status
                ) VALUES ('%s', '%s', 'USER', 'CrewScope Operator',
                    'bootstrap', 'crewscope-monitor', 'ORGANIZATION', 'ACTIVE')
                """.formatted(V30_PRINCIPAL_ID, V30_ORGANIZATION_ID));
        execute("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES ('%s', '%s', 'V30 Team', 'ACTIVE')
                """.formatted(V30_TEAM_ID, V30_ORGANIZATION_ID));
        execute("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES ('%s', '%s', '%s', '%s', 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """.formatted(
                V30_MEMBER_ID, V30_ORGANIZATION_ID, V30_TEAM_ID, V30_PRINCIPAL_ID));
        execute("""
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, principal_id, initiator_id,
                    actor_type, actor_id, event_type, subject_type, subject_id,
                    outcome, correlation_id, schema_version, occurred_at, payload
                ) VALUES ('%s', '%s', '%s', '%s', 'USER', '%s',
                    'LegacyBootstrapObserved', 'Principal', '%s', 'SUCCEEDED',
                    '%s', 'V1', CURRENT_TIMESTAMP, '{}'::JSONB)
                """.formatted(
                V30_AUDIT_ID,
                V30_ORGANIZATION_ID,
                V30_PRINCIPAL_ID,
                V30_PRINCIPAL_ID,
                V30_PRINCIPAL_ID,
                V30_PRINCIPAL_ID,
                UUID.randomUUID()));
    }

    private static Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
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

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = PrincipalEntity.class)
    @Import({
        IdentityPersistenceMapper.class,
        JdbcUserAccountRepositoryAdapter.class,
        JdbcLoginIdentityRepositoryAdapter.class,
        JdbcLocalCredentialStoreAdapter.class,
        JdbcAccountOrganizationBindingRepositoryAdapter.class,
        JdbcBootstrapOperatorLock.class,
        TeamPersistenceMapper.class,
        JpaPrincipalRepositoryAdapter.class,
        LocalBootstrapOperatorPasswordHasher.class,
        PasswordHashingConfiguration.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {

        @Bean
        TimeProvider timeProvider() {
            return TimeProvider.from(Clock.systemUTC());
        }

        @Bean
        BootstrapOperatorProvisioningService service(
                BootstrapOperatorLock bootstrapLock,
                PrincipalRepository principals,
                UserAccountRepository accounts,
                LoginIdentityRepository loginIdentities,
                LocalCredentialStore credentials,
                AccountOrganizationBindingRepository bindings,
                BootstrapOperatorPasswordHasher passwordHasher,
                TransactionExecutor transactions,
                TimeProvider timeProvider) {
            return new BootstrapOperatorProvisioningService(
                    bootstrapLock,
                    principals,
                    accounts,
                    loginIdentities,
                    credentials,
                    bindings,
                    passwordHasher,
                    transactions,
                    timeProvider);
        }
    }
}
