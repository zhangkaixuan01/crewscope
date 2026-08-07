package io.crewscope.infrastructure.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStoreError;
import io.crewscope.application.credential.CredentialStoreException;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Proves online historical-key reads and bounded, optimistic key rewrap against PostgreSQL. */
@SpringBootTest(
        classes = CredentialKeyRotationIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none"
        })
class CredentialKeyRotationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-07T13:00:00Z");
    private static final String OLD_KEY_ID = "credential-key-2026-07";
    private static final String NEW_KEY_ID = "credential-key-2026-08";
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId ACTOR_ID = PrincipalId.generate();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private HookedClock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                ORGANIZATION_ID.value(),
                "Credential Rotation Organization");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                ACTOR_ID.value(),
                ORGANIZATION_ID.value(),
                "Credential Rotation Actor");
        clock = new HookedClock(BASE_TIME);
    }

    @Test
    void readsHistoricalEnvelopesAndRewrapsThemInBoundedBatches() {
        CredentialCreateRequest first = request("first");
        CredentialCreateRequest second = request("second");
        createWithOldKey(first, "first-secret");
        createWithOldKey(second, "second-secret");
        EnvelopeSnapshot firstBefore = snapshot(first.credentialId());
        EnvelopeSnapshot secondBefore = snapshot(second.credentialId());

        try (CredentialKeyRing rotatingRing = rotatingKeyRing()) {
            DatabaseEnvelopeCredentialStore rotatingStore = store(rotatingRing);
            assertSecret(rotatingStore, first, "first-secret");
            assertSecret(rotatingStore, second, "second-secret");

            CredentialRewrapResult firstBatch = rotatingStore.rewrapBatch(1);
            assertEquals(new CredentialRewrapResult(1, 1, 0, 1), firstBatch);
            assertEquals(1, countByKey(NEW_KEY_ID));
            assertEquals(1, countByKey(OLD_KEY_ID));

            CredentialRewrapResult secondBatch = rotatingStore.rewrapBatch(1);
            assertEquals(new CredentialRewrapResult(1, 1, 0, 0), secondBatch);
            assertEquals(2, countByKey(NEW_KEY_ID));

            assertSecret(rotatingStore, first, "first-secret");
            assertSecret(rotatingStore, second, "second-secret");
        }

        assertRewrapped(firstBefore, snapshot(first.credentialId()));
        assertRewrapped(secondBefore, snapshot(second.credentialId()));

        // Once no active envelope references the historical key, the next deployment may remove it.
        try (CredentialKeyRing currentOnly = CredentialKeyRing.of(
                NEW_KEY_ID, Map.of(NEW_KEY_ID, keyBytes((byte) 0x42)))) {
            DatabaseEnvelopeCredentialStore currentOnlyStore = store(currentOnly);
            assertSecret(currentOnlyStore, first, "first-secret");
            assertSecret(currentOnlyStore, second, "second-secret");
            assertEquals(new CredentialRewrapResult(0, 0, 0, 0),
                    currentOnlyStore.rewrapBatch(10));
        }
    }

    @Test
    void reportsIntegrityViolationWhenAnEnvelopeReferencesAnUnavailableKey() {
        CredentialCreateRequest request = request("missing-old-key");
        createWithOldKey(request, "historical-secret");

        try (CredentialKeyRing currentOnly = CredentialKeyRing.of(
                NEW_KEY_ID, Map.of(NEW_KEY_ID, keyBytes((byte) 0x42)))) {
            CredentialStoreException exception = assertThrows(
                    CredentialStoreException.class,
                    () -> store(currentOnly).resolve(reference(request), access(request)));

            assertEquals(CredentialStoreError.INTEGRITY_VIOLATION, exception.error());
            assertFalse(exception.getMessage().contains("historical-secret"));
        }
    }

    @Test
    void treatsConcurrentVersionChangesAsConflictsWithoutOverwritingThem() {
        CredentialCreateRequest request = request("concurrent");
        createWithOldKey(request, "concurrent-secret");
        EnvelopeSnapshot before = snapshot(request.credentialId());
        clock.runBeforeNextInstant(() -> jdbcTemplate.update(
                "UPDATE crewscope.credential_secret SET version = version + 1 WHERE id = ?",
                request.credentialId().value()));

        try (CredentialKeyRing rotatingRing = rotatingKeyRing()) {
            CredentialRewrapResult result = store(rotatingRing).rewrapBatch(10);

            assertEquals(new CredentialRewrapResult(1, 0, 1, 1), result);
        }
        EnvelopeSnapshot after = snapshot(request.credentialId());
        assertEquals(OLD_KEY_ID, after.keyId());
        assertEquals(1L, after.version());
        assertArrayEquals(before.ciphertext(), after.ciphertext());
        assertArrayEquals(before.nonce(), after.nonce());
    }

    @Test
    void enforcesTheDocumentedBatchBounds() {
        try (CredentialKeyRing rotatingRing = rotatingKeyRing()) {
            DatabaseEnvelopeCredentialStore rotatingStore = store(rotatingRing);

            assertThrows(IllegalArgumentException.class, () -> rotatingStore.rewrapBatch(0));
            assertThrows(IllegalArgumentException.class, () -> rotatingStore.rewrapBatch(1001));
            assertEquals(new CredentialRewrapResult(0, 0, 0, 0),
                    rotatingStore.rewrapBatch(1000));
        }
    }

    private void createWithOldKey(CredentialCreateRequest request, String secretText) {
        try (CredentialKeyRing oldRing = CredentialKeyRing.of(
                        OLD_KEY_ID, Map.of(OLD_KEY_ID, keyBytes((byte) 0x21)));
                CredentialSecret secret = CredentialSecret.utf8(secretText)) {
            store(oldRing).create(request, secret);
        }
    }

    private CredentialKeyRing rotatingKeyRing() {
        return CredentialKeyRing.of(
                NEW_KEY_ID,
                Map.of(
                        NEW_KEY_ID, keyBytes((byte) 0x42),
                        OLD_KEY_ID, keyBytes((byte) 0x21)));
    }

    private DatabaseEnvelopeCredentialStore store(CredentialKeyRing keyRing) {
        return new DatabaseEnvelopeCredentialStore(
                jdbcTemplate, objectMapper, keyRing, new SecureRandom(), clock);
    }

    private void assertSecret(
            DatabaseEnvelopeCredentialStore credentialStore,
            CredentialCreateRequest request,
            String expected) {
        try (ResolvedCredential resolved = credentialStore
                .resolve(reference(request), access(request))
                .orElseThrow()) {
            assertArrayEquals(
                    expected.getBytes(StandardCharsets.UTF_8), resolved.secret().copyBytes());
        }
    }

    private void assertRewrapped(EnvelopeSnapshot before, EnvelopeSnapshot after) {
        assertEquals(OLD_KEY_ID, before.keyId());
        assertEquals(NEW_KEY_ID, after.keyId());
        assertEquals(0L, before.version());
        assertEquals(1L, after.version());
        assertFalse(Arrays.equals(before.ciphertext(), after.ciphertext()));
        assertFalse(Arrays.equals(before.nonce(), after.nonce()));
        assertEquals(before.updatedBy(), after.updatedBy());
        assertNull(after.rotatedAt());
    }

    private int countByKey(String keyId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.credential_secret WHERE key_id = ?",
                Integer.class,
                keyId);
    }

    private EnvelopeSnapshot snapshot(CredentialId credentialId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT key_id, ciphertext, nonce, version, updated_by_principal_id, rotated_at
                FROM crewscope.credential_secret
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new EnvelopeSnapshot(
                        resultSet.getString("key_id"),
                        resultSet.getBytes("ciphertext"),
                        resultSet.getBytes("nonce"),
                        resultSet.getLong("version"),
                        resultSet.getObject("updated_by_principal_id", UUID.class),
                        resultSet.getObject("rotated_at")),
                credentialId.value());
    }

    private CredentialCreateRequest request(String suffix) {
        return new CredentialCreateRequest(
                CredentialId.generate(),
                CredentialSubject.principal(ORGANIZATION_ID, ACTOR_ID),
                "github-rotation-" + suffix,
                "github",
                Optional.of(UUID.randomUUID()),
                "OAUTH_TOKEN",
                Map.of("account", suffix),
                Optional.empty(),
                ACTOR_ID);
    }

    private CredentialAccessContext access(CredentialCreateRequest request) {
        return new CredentialAccessContext(
                ORGANIZATION_ID,
                ACTOR_ID,
                Set.of(request.credentialId()),
                "github:push");
    }

    private static CredentialReference reference(CredentialCreateRequest request) {
        return new CredentialReference(ORGANIZATION_ID, request.credentialId());
    }

    private static byte[] keyBytes(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private record EnvelopeSnapshot(
            String keyId,
            byte[] ciphertext,
            byte[] nonce,
            long version,
            UUID updatedBy,
            Object rotatedAt) {}

    private static final class HookedClock extends Clock {
        private final AtomicReference<Runnable> beforeNextInstant = new AtomicReference<>();
        private final Instant instant;

        private HookedClock(Instant instant) {
            this.instant = instant;
        }

        void runBeforeNextInstant(Runnable action) {
            beforeNextInstant.set(action);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("HookedClock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            Runnable action = beforeNextInstant.getAndSet(null);
            if (action != null) {
                action.run();
            }
            return instant;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
