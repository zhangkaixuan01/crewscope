package io.crewscope.infrastructure.persistence.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.CurrentAccountSnapshot;
import io.crewscope.application.identity.CurrentAccountSnapshotReader;
import io.crewscope.application.identity.LocalCredentialMetadataRepository;
import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingConflictException;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationBindingStatus;
import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL contract for the M7 non-secret identity adapters and REQUIRED transaction boundary. */
@SpringBootTest(
        classes = M7I01IdentityPersistenceIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class M7I01IdentityPersistenceIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T12:00:00Z"));
    private static final UtcTimestamp LATER =
            UtcTimestamp.from(Instant.parse("2026-08-28T12:01:00Z"));

    @Autowired private UserAccountRepository accounts;
    @Autowired private LoginIdentityRepository identities;
    @Autowired private LocalCredentialMetadataRepository credentials;
    @Autowired private AccountOrganizationBindingRepository bindings;
    @Autowired private CurrentAccountSnapshotReader snapshots;
    @Autowired private IdentityPersistenceMapper mapper;
    @Autowired private TransactionExecutor transactionExecutor;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.user_account, crewscope.organization CASCADE");
    }

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void roundTripsCanonicalAccountIdentityMetadataAndBinding() {
        UserAccount account = account("Ａlice", "Alice@例子.com", "Alice");
        accounts.create(account);
        LoginIdentity local = LoginIdentity.local(LoginIdentityId.generate(), account.id(), NOW);
        LoginIdentity external = LoginIdentity.external(
                LoginIdentityId.generate(),
                account.id(),
                new IdentityProviderKey("OIDC/Corporate"),
                new LoginIdentitySubject("Subject-42"),
                NOW);
        identities.create(local);
        identities.create(external);
        LocalCredentialId credentialId = insertCredential(account.id());
        OrganizationFixture organization = insertOrganizationPrincipal("Primary Organization");
        AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organization.organizationId(),
                organization.principal(),
                NOW);
        bindings.create(binding);

        assertEquals(account.id(), accounts.findByUsername(new Username("alice")).orElseThrow().id());
        assertEquals(
                account.id(),
                accounts.findByEmail(new NormalizedEmail("ALICE@XN--FSQU00A.COM"))
                        .orElseThrow()
                        .id());
        assertEquals(local.id(), identities.findByIdentityKey(local.identityKey()).orElseThrow().id());
        assertEquals(2, identities.findByAccountId(account.id()).size());
        assertEquals(credentialId, credentials.findByAccountId(account.id()).orElseThrow().id());
        assertEquals(
                binding.id(),
                bindings.findByAccountOrganizationKey(binding.accountOrganizationKey())
                        .orElseThrow()
                        .id());
        transactions.executeWithoutResult(status -> {
            assertTrue(accounts.findByIdForUpdate(account.id()).isPresent());
            assertTrue(identities.findByIdentityKeyForUpdate(local.identityKey()).isPresent());
            assertTrue(credentials.findByAccountIdForUpdate(account.id()).isPresent());
            assertTrue(bindings.findByAccountOrganizationKeyForUpdate(
                            binding.accountOrganizationKey())
                    .isPresent());
        });
    }

    @Test
    void currentAccountSnapshotUsesOneSqlAndDeduplicatesTheJoinedGraph() {
        UserAccount account = accounts.create(account("alice", "alice@example.com", "Alice"));
        identities.create(LoginIdentity.local(LoginIdentityId.generate(), account.id(), NOW));
        identities.create(LoginIdentity.external(
                LoginIdentityId.generate(),
                account.id(),
                new IdentityProviderKey("oidc/corporate"),
                new LoginIdentitySubject("Subject-42"),
                NOW));
        insertCredential(account.id());
        OrganizationFixture first = insertOrganizationPrincipal("First Organization");
        OrganizationFixture second = insertOrganizationPrincipal("Second Organization");
        bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                first.organizationId(),
                first.principal(),
                NOW));
        bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                second.organizationId(),
                second.principal(),
                NOW));

        CountingDataSource counting = new CountingDataSource(dataSource);
        CurrentAccountSnapshotReader singleQueryReader = new JdbcCurrentAccountSnapshotReader(
                new NamedParameterJdbcTemplate(counting), mapper);
        CurrentAccountSnapshot snapshot = singleQueryReader.findByAccountId(account.id()).orElseThrow();

        assertEquals(1, counting.statementCount());
        assertEquals(account.id(), snapshot.account().id());
        assertEquals(2, snapshot.loginIdentities().size());
        assertTrue(snapshot.credentialMetadata().isPresent());
        assertEquals(2, snapshot.organizationBindings().size());
        assertTrue(snapshots.findByAccountId(UserAccountId.generate()).isEmpty());
    }

    @Test
    void currentAccountSnapshotRejectsFactsOwnedByAnotherAccount() {
        UserAccount account = account("alice", "alice@example.com", "Alice");
        LoginIdentity foreignIdentity = LoginIdentity.local(
                LoginIdentityId.generate(), UserAccountId.generate(), NOW);

        assertThrows(
                IllegalArgumentException.class,
                () -> new CurrentAccountSnapshot(
                        account, List.of(foreignIdentity), Optional.empty(), List.of()));
    }

    @Test
    void appliesOptimisticVersionsAcrossAllMutableNonSecretAggregates() {
        UserAccount original = accounts.create(account("alice", "alice@example.com", "Alice"));
        UserAccount changed = original.changeDisplayName("Alice Updated", LATER);
        assertEquals(1, accounts.update(changed, 0).version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> accounts.update(original.changeDisplayName("Stale Alice", LATER), 0));

        LoginIdentity identity = identities.create(
                LoginIdentity.local(LoginIdentityId.generate(), original.id(), NOW));
        LoginIdentity authenticated = identity.recordAuthentication(LATER);
        assertEquals(1, identities.update(authenticated, 0).version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> identities.update(identity.recordAuthentication(LATER), 0));

        OrganizationFixture organization = insertOrganizationPrincipal("Primary Organization");
        AccountOrganizationBinding binding = bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                original,
                organization.organizationId(),
                organization.principal(),
                NOW));
        AccountOrganizationBinding disabled = binding.disable(LATER);
        assertEquals(1, bindings.update(disabled, 0).version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> bindings.update(binding.disable(LATER), 0));
    }

    @Test
    void concurrentRegistrationConvergesThroughDomainDerivedCanonicalKeys() throws Exception {
        UserAccount first = account("Alice", "first@example.com", "First");
        UserAccount second = account("ＡＬＩＣＥ", "second@example.com", "Second");
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Object>> attempts = List.of(
                executor.submit(() -> createAfter(start, first)),
                executor.submit(() -> createAfter(start, second)));
        start.countDown();
        List<Object> outcomes = attempts.stream().map(M7I01IdentityPersistenceIntegrationTest::get)
                .toList();

        assertEquals(1, outcomes.stream().filter(UserAccount.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(AccountIdentifierConflictException.class::isInstance)
                .count());
        AccountIdentifierConflictException conflict = outcomes.stream()
                .filter(AccountIdentifierConflictException.class::isInstance)
                .map(AccountIdentifierConflictException.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0, conflict.getSuppressed().length);
        assertNull(conflict.getCause());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.user_account", Integer.class));
    }

    @Test
    void accountLockBlocksAConcurrentMutationUntilTheOuterTransactionCompletes()
            throws Exception {
        UserAccount account = accounts.create(account("alice", "alice@example.com", "Alice"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = executor.submit(() -> transactions.execute(status -> {
            accounts.findByIdForUpdate(account.id()).orElseThrow();
            locked.countDown();
            await(release);
            return null;
        }));
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        Future<UserAccount> writer = executor.submit(
                () -> accounts.update(account.changeDisplayName("Locked Update", LATER), 0));

        assertThrows(TimeoutException.class, () -> writer.get(250, TimeUnit.MILLISECONDS));
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        assertEquals("Locked Update", writer.get(5, TimeUnit.SECONDS).displayName());
    }

    @Test
    void crossOrganizationPrincipalBindingFailsAsOneSafeConflict() {
        UserAccount account = accounts.create(account("alice", "alice@example.com", "Alice"));
        OrganizationFixture first = insertOrganizationPrincipal("First Organization");
        OrganizationFixture second = insertOrganizationPrincipal("Second Organization");
        AccountOrganizationBinding invalid = AccountOrganizationBinding.reconstitute(
                AccountOrganizationBindingId.generate(),
                account.id(),
                first.organizationId(),
                second.principal().id(),
                AccountOrganizationBindingStatus.ACTIVE,
                0,
                LifecycleMetadata.createdAt(NOW));

        AccountOrganizationBindingConflictException conflict = assertThrows(
                AccountOrganizationBindingConflictException.class,
                () -> bindings.create(invalid));

        assertTrue(conflict.error().details().isEmpty());
        assertEquals(0, conflict.getSuppressed().length);
        assertNull(conflict.getCause());
        assertTrue(bindings.findByAccountId(account.id()).isEmpty());
    }

    @Test
    void requiredTransactionRollsBackAllRepositoryWritesOnConflict() {
        UserAccount first = account("alice", "first@example.com", "First");
        UserAccount conflicting = account("ＡLICE", "second@example.com", "Second");

        assertThrows(AccountIdentifierConflictException.class, () -> transactionExecutor.required(() -> {
            accounts.create(first);
            accounts.create(conflicting);
            return null;
        }));

        assertTrue(accounts.findById(first.id()).isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.user_account", Integer.class));
    }

    @Test
    void rejectsPersistedCanonicalKeysThatWereNotDerivedByTheDomain() {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                ) VALUES (?, 'Alice', 'wrong-key', 'alice@example.com', 'alice@example.com',
                    'Alice', 'ACTIVE', 'USER', 1, 0, ?, ?)
                """,
                accountId,
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime());

        assertThrows(
                DomainValidationException.class,
                () -> accounts.findById(new UserAccountId(accountId)));
    }

    private Object createAfter(CountDownLatch start, UserAccount account) {
        await(start);
        try {
            return accounts.create(account);
        } catch (AccountIdentifierConflictException conflict) {
            return conflict;
        }
    }

    private UserAccount account(String username, String email, String displayName) {
        return UserAccount.register(UserAccountId.generate(), username, email, displayName, NOW);
    }

    private LocalCredentialId insertCredential(UserAccountId accountId) {
        LocalCredentialId credentialId = LocalCredentialId.generate();
        jdbc.update(
                """
                INSERT INTO crewscope.local_credential (
                    id, account_id, password_hash, algorithm, credential_version,
                    password_changed_at, version, created_at, updated_at
                ) VALUES (?, ?, '{argon2id}$printable-hash-body-0123456789', 'argon2id', 1,
                    ?, 0, ?, ?)
                """,
                credentialId.value(),
                accountId.value(),
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime());
        return credentialId;
    }

    private OrganizationFixture insertOrganizationPrincipal(String name) {
        OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
        PrincipalId principalId = new PrincipalId(UUID.randomUUID());
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                name);
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', ?, 'ORGANIZATION', 'ACTIVE')
                """,
                principalId.value(),
                organizationId.value(),
                name + " User");
        Principal principal = Principal.create(
                principalId,
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                name + " User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        return new OrganizationFixture(organizationId, principal);
    }

    private static Object get(Future<Object> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record OrganizationFixture(OrganizationId organizationId, Principal principal) {}

    /** Counts prepared statements without changing the production DataSource or query behavior. */
    private static final class CountingDataSource implements DataSource {

        private final DataSource delegate;
        private final AtomicInteger statements = new AtomicInteger();

        private CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        int statementCount() {
            return statements.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            return counted(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return counted(delegate.getConnection(username, password));
        }

        private Connection counted(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().startsWith("prepareStatement")) {
                            statements.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        IdentityPersistenceMapper.class,
        JdbcUserAccountRepositoryAdapter.class,
        JdbcLoginIdentityRepositoryAdapter.class,
        JdbcLocalCredentialMetadataRepositoryAdapter.class,
        JdbcAccountOrganizationBindingRepositoryAdapter.class,
        JdbcCurrentAccountSnapshotReader.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
