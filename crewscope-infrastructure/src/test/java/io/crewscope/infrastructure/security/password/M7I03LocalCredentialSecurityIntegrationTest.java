package io.crewscope.infrastructure.security.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.crewscope.application.identity.LocalCredentialAuthenticationMaterial;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LocalPasswordAuthentication;
import io.crewscope.application.identity.LocalPasswordVerification;
import io.crewscope.application.identity.PasswordHashCapacityException;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.identity.IdentityPersistenceMapper;
import io.crewscope.infrastructure.persistence.identity.JdbcLocalCredentialStoreAdapter;
import io.crewscope.infrastructure.persistence.identity.JdbcUserAccountRepositoryAdapter;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Real PostgreSQL and encoder contract for M7-I03's trusted local password boundary. */
@SpringBootTest(
        classes = M7I03LocalCredentialSecurityIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false",
            "crewscope.security.password.hash-permits=1"
        })
class M7I03LocalCredentialSecurityIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String PASSWORD = "Correct-Horse-Battery-2026";
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.from(Instant.parse("2025-08-28T12:00:00Z"));
    private static final UtcTimestamp CHANGED_AT =
            UtcTimestamp.from(Instant.parse("2025-08-28T12:01:00Z"));

    @Autowired private UserAccountRepository accounts;
    @Autowired private LocalCredentialStore credentials;
    @Autowired private LocalPasswordAuthentication passwords;
    @Autowired private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("localCredentialPasswordEncoder")
    private PasswordEncoder encoder;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.user_account CASCADE");
    }

    @Test
    void currentArgon2idRoundTripsAndWrongPasswordNeverMutatesCredential() {
        UserAccount account = createAccount("current");
        LocalPasswordHash hash = await(passwords.encodeForStorage(PASSWORD));
        LocalCredentialMetadata metadata = LocalCredentialMetadata.create(
                LocalCredentialId.generate(), account.id(), hash, CREATED_AT);
        LocalCredentialAuthenticationMaterial stored = credentials.create(metadata, hash);

        LocalPasswordVerification correct = await(passwords.verify(
                PASSWORD, Optional.of(stored), account.canAuthenticate()));
        LocalPasswordVerification wrong = await(passwords.verify(
                "Wrong-Password-Value-2026", Optional.of(stored), account.canAuthenticate()));

        assertTrue(correct.authenticated());
        assertEquals(LocalPasswordVerification.Upgrade.NOT_REQUIRED, correct.upgrade());
        assertEquals(
                LocalPasswordVerification.Decision.INVALID_CREDENTIALS, wrong.decision());
        LocalCredentialAuthenticationMaterial unchanged = credentials
                .findByAccountIdForAuthentication(account.id())
                .orElseThrow();
        assertEquals(0, unchanged.metadata().version());
        assertEquals(hash.encodedValue(), unchanged.passwordHash().orElseThrow().encodedValue());
    }

    @Test
    void approvedWeakArgon2idAndBcryptUpgradeOnlyAfterSuccessfulMatch() {
        UserAccount weakAccount = createAccount("weak");
        UserAccount bcryptAccount = createAccount("bcrypt");
        LocalPasswordHash weak = new LocalPasswordHash(
                "{argon2id}" + new Argon2PasswordEncoder(16, 32, 1, 19_456, 2).encode(PASSWORD));
        LocalPasswordHash bcrypt = new LocalPasswordHash(
                "{bcrypt}" + new BCryptPasswordEncoder(10).encode(PASSWORD));
        insertLegacy(weakAccount.id(), weak);
        insertLegacy(bcryptAccount.id(), bcrypt);

        LocalPasswordVerification failed = await(passwords.verify(
                "Wrong-Password-Value-2026",
                credentials.findByAccountIdForAuthentication(weakAccount.id()),
                true));
        assertFalse(failed.authenticated());
        assertEquals(0, credentialVersion(weakAccount.id()));

        LocalPasswordVerification weakResult = await(passwords.verify(
                PASSWORD,
                credentials.findByAccountIdForAuthentication(weakAccount.id()),
                true));
        LocalPasswordVerification bcryptResult = await(passwords.verify(
                PASSWORD,
                credentials.findByAccountIdForAuthentication(bcryptAccount.id()),
                true));

        assertEquals(LocalPasswordVerification.Upgrade.REHASHED, weakResult.upgrade());
        assertEquals(LocalPasswordVerification.Upgrade.REHASHED, bcryptResult.upgrade());
        assertCurrentCredential(weakAccount.id());
        assertCurrentCredential(bcryptAccount.id());
    }

    @Test
    void oversizedAndInvalidUnicodeInputsAreRejectedBeforeHashAdmission() {
        LocalPasswordVerification tooLong = await(passwords.verify(
                "a".repeat(129), Optional.empty(), false));
        LocalPasswordVerification tooLarge = await(passwords.verify(
                "😀".repeat(129), Optional.empty(), false));
        LocalPasswordVerification invalidUnicode = await(passwords.verify(
                "valid-prefix-123\ud800", Optional.empty(), false));

        assertEquals(LocalPasswordVerification.Decision.INPUT_REJECTED, tooLong.decision());
        assertEquals(LocalPasswordVerification.Decision.INPUT_REJECTED, tooLarge.decision());
        assertEquals(
                LocalPasswordVerification.Decision.INPUT_REJECTED, invalidUnicode.decision());
        assertThrows(
                DomainValidationException.class,
                () -> passwords.encodeForStorage("password1234"));
    }

    @Test
    void unknownDisabledAndCorruptedCredentialsEachPerformOneDummyMatch() {
        AtomicInteger matches = new AtomicInteger();
        PasswordEncoder counting = countingEncoder(encoder, matches);
        UserAccount account = createAccount("dummy");
        LocalPasswordHash currentHash = new LocalPasswordHash(encoder.encode(PASSWORD));
        LocalCredentialMetadata metadata = LocalCredentialMetadata.create(
                LocalCredentialId.generate(), account.id(), currentHash, CREATED_AT);
        LocalCredentialAuthenticationMaterial corrupted =
                LocalCredentialAuthenticationMaterial.corrupted(metadata);
        LocalCredentialAuthenticationMaterial unknownAlgorithm =
                LocalCredentialAuthenticationMaterial.corrupted();
        LocalCredentialAuthenticationMaterial usable =
                LocalCredentialAuthenticationMaterial.verified(metadata, currentHash);

        try (PasswordHashAdmissionExecutor admission =
                new PasswordHashAdmissionExecutor(1, Duration.ofMillis(100))) {
            LocalPasswordAuthentication isolated = new LocalPasswordAuthenticationAdapter(
                    counting,
                    admission,
                    credentials,
                    Clock.fixed(Instant.parse("2025-08-28T12:02:00Z"), ZoneOffset.UTC));
            matches.set(0); // Dummy preparation encodes once but never performs a Match.

            assertFalse(await(isolated.verify(PASSWORD, Optional.empty(), false)).authenticated());
            assertEquals(1, matches.get());
            assertFalse(await(isolated.verify(PASSWORD, Optional.of(corrupted), true))
                    .authenticated());
            assertEquals(2, matches.get());
            assertFalse(await(isolated.verify(PASSWORD, Optional.of(usable), false))
                    .authenticated());
            assertEquals(3, matches.get());
            assertFalse(await(isolated.verify(PASSWORD, Optional.of(unknownAlgorithm), true))
                    .authenticated());
            assertEquals(4, matches.get());
        }
    }

    @Test
    void persistedMalformedHashFailsClosedWithoutLeakingItsParserReason() {
        UserAccount account = createAccount("damaged");
        String damaged = "{argon2id}$this-is-not-an-approved-encoding-body";
        insertRaw(account.id(), damaged, "argon2id");
        LocalCredentialAuthenticationMaterial material = credentials
                .findByAccountIdForAuthentication(account.id())
                .orElseThrow();

        LocalPasswordVerification result =
                await(passwords.verify(PASSWORD, Optional.of(material), true));

        assertFalse(result.authenticated());
        assertEquals(0, credentialVersion(account.id()));
        assertFalse(result.toString().contains(damaged));
        assertFalse(material.toString().contains(damaged));
    }

    @Test
    void rehashCompareAndSetNeverOverwritesAConcurrentPasswordChange() {
        UserAccount account = createAccount("concurrent");
        LocalPasswordHash legacy = new LocalPasswordHash(
                "{bcrypt}" + new BCryptPasswordEncoder(10).encode(PASSWORD));
        insertLegacy(account.id(), legacy);
        LocalCredentialAuthenticationMaterial original = credentials
                .findByAccountIdForAuthentication(account.id())
                .orElseThrow();
        LocalPasswordHash concurrentHash =
                new LocalPasswordHash(encoder.encode("Concurrent-New-Password-2026"));
        LocalCredentialStore interleaving = new ConcurrentChangeStore(
                credentials, original, concurrentHash);

        try (PasswordHashAdmissionExecutor admission =
                new PasswordHashAdmissionExecutor(1, Duration.ofMillis(100))) {
            LocalPasswordAuthentication isolated = new LocalPasswordAuthenticationAdapter(
                    encoder,
                    admission,
                    interleaving,
                    Clock.fixed(Instant.parse("2025-08-28T12:03:00Z"), ZoneOffset.UTC));
            LocalPasswordVerification result =
                    await(isolated.verify(PASSWORD, Optional.of(original), true));

            assertTrue(result.authenticated());
            assertEquals(
                    LocalPasswordVerification.Upgrade.SKIPPED_CONCURRENT_CHANGE,
                    result.upgrade());
        }
        LocalCredentialAuthenticationMaterial committed = credentials
                .findByAccountIdForAuthentication(account.id())
                .orElseThrow();
        assertEquals(1, committed.metadata().version());
        assertEquals(
                concurrentHash.encodedValue(), committed.passwordHash().orElseThrow().encodedValue());
    }

    @Test
    void fairBoundedAdmissionExpiresQueuedWorkWithoutStartingAnotherHash() throws Exception {
        AtomicInteger started = new AtomicInteger();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (PasswordHashAdmissionExecutor admission =
                new PasswordHashAdmissionExecutor(1, Duration.ofMillis(100))) {
            CompletionStage<Boolean> first = admission.submit(() -> {
                started.incrementAndGet();
                occupied.countDown();
                awaitLatch(release);
                return true;
            });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            CompletionStage<Boolean> queued = admission.submit(() -> {
                started.incrementAndGet();
                return true;
            });
            CompletionStage<Boolean> overflow = admission.submit(() -> {
                started.incrementAndGet();
                return true;
            });

            assertCapacityFailure(queued);
            assertCapacityFailure(overflow);
            assertEquals(1, started.get());
            release.countDown();
            assertTrue(await(first));
        } finally {
            release.countDown();
        }
    }

    @Test
    void admissionShutdownRejectsQueuedWorkAndLetsRunningWorkSettle() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PasswordHashAdmissionExecutor admission =
                new PasswordHashAdmissionExecutor(1, Duration.ofSeconds(5));
        try {
            CompletionStage<Boolean> running = admission.submit(() -> {
                occupied.countDown();
                awaitLatchUninterruptibly(release);
                return true;
            });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            CompletionStage<Boolean> queued = admission.submit(() -> true);

            admission.close();

            assertCapacityFailure(queued);
            release.countDown();
            assertTrue(await(running));
        } finally {
            release.countDown();
            admission.close();
        }
    }

    @Test
    void admissionCompletesExceptionallyWhenHashProviderThrowsAnError() {
        try (PasswordHashAdmissionExecutor admission =
                new PasswordHashAdmissionExecutor(1, Duration.ofMillis(100))) {
            CompletionStage<Boolean> failed = admission.submit(() -> {
                throw new AssertionError("simulated password provider failure");
            });

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> failed.toCompletableFuture().orTimeout(1, TimeUnit.SECONDS).join());

            assertTrue(failure.getCause() instanceof AssertionError);
        }
    }

    @Test
    void rawPasswordAndEncodedHashNeverEnterStringFormsExceptionsOrLogs() {
        String uniquePassword = "M7-I03-Secret-Never-Log-9zQ";
        UserAccount account = createAccount("nolog");
        LocalPasswordHash hash = await(passwords.encodeForStorage(uniquePassword));
        LocalCredentialMetadata metadata = LocalCredentialMetadata.create(
                LocalCredentialId.generate(), account.id(), hash, CREATED_AT);
        LocalCredentialAuthenticationMaterial material = credentials.create(metadata, hash);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        try {
            await(passwords.verify(uniquePassword, Optional.of(material), true));
            CompletionException capacity = assertThrows(
                    CompletionException.class,
                    () -> CompletableFuture.failedFuture(new PasswordHashCapacityException()).join());
            String observable = material + " " + hash + " " + capacity;
            String logs = appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.TRACE))
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + right);

            assertFalse(observable.contains(uniquePassword));
            assertFalse(observable.contains(hash.encodedValue()));
            assertFalse(logs.contains(uniquePassword));
            assertFalse(logs.contains(hash.encodedValue()));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    private UserAccount createAccount(String suffix) {
        return accounts.create(UserAccount.register(
                UserAccountId.generate(),
                "m7-i03-" + suffix,
                suffix + "@example.com",
                "M7 I03 " + suffix,
                CREATED_AT));
    }

    private void insertLegacy(UserAccountId accountId, LocalPasswordHash hash) {
        insertRaw(accountId, hash.encodedValue(), hash.algorithm().encodingId());
    }

    private void insertRaw(UserAccountId accountId, String hash, String algorithm) {
        jdbc.update(
                """
                INSERT INTO crewscope.local_credential (
                    id, account_id, password_hash, algorithm, credential_version,
                    password_changed_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 1, ?, 0, ?, ?)
                """,
                LocalCredentialId.generate().value(),
                accountId.value(),
                hash,
                algorithm,
                CREATED_AT.toOffsetDateTime(),
                CREATED_AT.toOffsetDateTime(),
                CREATED_AT.toOffsetDateTime());
    }

    private long credentialVersion(UserAccountId accountId) {
        return jdbc.queryForObject(
                "SELECT version FROM crewscope.local_credential WHERE account_id = ?",
                Long.class,
                accountId.value());
    }

    private void assertCurrentCredential(UserAccountId accountId) {
        LocalCredentialAuthenticationMaterial material = credentials
                .findByAccountIdForAuthentication(accountId)
                .orElseThrow();
        assertEquals(1, material.metadata().version());
        assertEquals(2, material.metadata().credentialVersion().value());
        assertTrue(material.passwordHash().orElseThrow().encodedValue()
                .startsWith("{argon2id}$argon2id$v=19$m=32768,t=3,p=1$"));
    }

    private static PasswordEncoder countingEncoder(
            PasswordEncoder delegate, AtomicInteger matches) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return delegate.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                matches.incrementAndGet();
                return delegate.matches(rawPassword, encodedPassword);
            }

            @Override
            public boolean upgradeEncoding(String encodedPassword) {
                return delegate.upgradeEncoding(encodedPassword);
            }
        };
    }

    private static void assertCapacityFailure(CompletionStage<?> stage) {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof PasswordHashCapacityException);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitLatchUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Injects one password change exactly between successful Match and the Rehash CAS. */
    private static final class ConcurrentChangeStore implements LocalCredentialStore {

        private final LocalCredentialStore delegate;
        private final LocalCredentialAuthenticationMaterial original;
        private final LocalPasswordHash concurrentHash;
        private final AtomicInteger rotations = new AtomicInteger();

        private ConcurrentChangeStore(
                LocalCredentialStore delegate,
                LocalCredentialAuthenticationMaterial original,
                LocalPasswordHash concurrentHash) {
            this.delegate = delegate;
            this.original = original;
            this.concurrentHash = concurrentHash;
        }

        @Override
        public LocalCredentialAuthenticationMaterial create(
                LocalCredentialMetadata metadata, LocalPasswordHash passwordHash) {
            return delegate.create(metadata, passwordHash);
        }

        @Override
        public Optional<LocalCredentialAuthenticationMaterial> findByAccountIdForAuthentication(
                UserAccountId accountId) {
            return delegate.findByAccountIdForAuthentication(accountId);
        }

        @Override
        public boolean rotateIfUnchanged(
                LocalCredentialMetadata replacement,
                LocalPasswordHash replacementHash,
                long expectedMetadataVersion) {
            if (rotations.getAndIncrement() == 0) {
                LocalCredentialMetadata concurrent = original.metadata().rotate(
                        concurrentHash, CHANGED_AT);
                assertTrue(delegate.rotateIfUnchanged(
                        concurrent,
                        concurrentHash,
                        original.metadata().version()));
            }
            return delegate.rotateIfUnchanged(
                    replacement, replacementHash, expectedMetadataVersion);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        IdentityPersistenceMapper.class,
        JdbcUserAccountRepositoryAdapter.class,
        JdbcLocalCredentialStoreAdapter.class,
        PasswordHashingConfiguration.class
    })
    static class TestApplication {}
}
