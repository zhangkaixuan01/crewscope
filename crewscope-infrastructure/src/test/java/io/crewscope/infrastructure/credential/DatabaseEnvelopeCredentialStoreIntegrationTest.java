package io.crewscope.infrastructure.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialMutationContext;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStoreError;
import io.crewscope.application.credential.CredentialStoreException;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Proves encrypted persistence, AAD authentication and lifecycle updates against PostgreSQL. */
@SpringBootTest(
        classes = DatabaseEnvelopeCredentialStoreIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none"
        })
class DatabaseEnvelopeCredentialStoreIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-07T12:00:00Z");
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final OrganizationId OTHER_ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId ACTOR_ID = PrincipalId.generate();
    private static final PrincipalId OTHER_ACTOR_ID = PrincipalId.generate();
    private static final String SECRET_TEXT = "github-secret-value";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private MutableClock clock;
    private CredentialEncryptionKey encryptionKey;
    private DatabaseEnvelopeCredentialStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        seedPrincipal(ORGANIZATION_ID, ACTOR_ID, "Credential Actor");
        seedPrincipal(OTHER_ORGANIZATION_ID, OTHER_ACTOR_ID, "Other Actor");
        clock = new MutableClock(BASE_TIME);
        encryptionKey = new CredentialEncryptionKey("dev-key-1", keyBytes((byte) 0x21));
        store = store(encryptionKey);
    }

    @AfterEach
    void clearKeyMaterial() {
        encryptionKey.close();
    }

    @Test
    void persistsOnlyEnvelopeDataAndResolvesAnAuthorizedShortLivedSecret() {
        CredentialCreateRequest request = request("main", Optional.empty());
        CredentialSecret source = CredentialSecret.utf8(SECRET_TEXT);

        CredentialDescriptor descriptor = store.create(request, source);

        assertFalse(source.isClosed());
        assertEquals(CredentialStatus.ACTIVE, descriptor.status());
        assertEquals(0, descriptor.version());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT ciphertext, nonce, authentication_tag, key_id, algorithm, aad_version,
                       metadata::TEXT AS metadata
                FROM crewscope.credential_secret
                WHERE organization_id = ? AND id = ?
                """,
                ORGANIZATION_ID.value(),
                request.credentialId().value());
        assertFalse(Arrays.equals(
                SECRET_TEXT.getBytes(StandardCharsets.UTF_8), (byte[]) row.get("ciphertext")));
        assertEquals(12, ((byte[]) row.get("nonce")).length);
        assertEquals(16, ((byte[]) row.get("authentication_tag")).length);
        assertEquals("dev-key-1", row.get("key_id"));
        assertEquals("AES-256-GCM", row.get("algorithm"));
        assertEquals("1", row.get("aad_version"));
        assertFalse(descriptor.toString().contains(SECRET_TEXT));

        ResolvedCredential resolved = store.resolve(requestReference(request), access(request))
                .orElseThrow();
        assertArrayEquals(
                SECRET_TEXT.getBytes(StandardCharsets.UTF_8), resolved.secret().copyBytes());
        assertFalse(resolved.toString().contains(SECRET_TEXT));
        resolved.close();
        assertTrue(resolved.secret().isClosed());
    }

    @Test
    void hidesUnauthorizedCrossOrganizationAndExpiredCredentials() {
        CredentialCreateRequest request = request(
                "expiring",
                Optional.of(UtcTimestamp.from(BASE_TIME.plus(Duration.ofHours(1)))));
        store.create(request, CredentialSecret.utf8(SECRET_TEXT));
        CredentialAccessContext denied = new CredentialAccessContext(
                ORGANIZATION_ID, ACTOR_ID, Set.of(), "github:push");
        CredentialReference crossOrganization =
                new CredentialReference(OTHER_ORGANIZATION_ID, request.credentialId());
        CredentialAccessContext otherAccess = new CredentialAccessContext(
                OTHER_ORGANIZATION_ID,
                OTHER_ACTOR_ID,
                Set.of(request.credentialId()),
                "github:push");

        assertTrue(store.resolve(requestReference(request), denied).isEmpty());
        assertTrue(store.resolve(crossOrganization, otherAccess).isEmpty());
        clock.set(BASE_TIME.plus(Duration.ofHours(1)));
        assertTrue(store.resolve(requestReference(request), access(request)).isEmpty());
    }

    @Test
    void rejectsCiphertextTagAndAadTampering() {
        assertTamperingRejected("ciphertext", reference -> {
            byte[] ciphertext = jdbcTemplate.queryForObject(
                    "SELECT ciphertext FROM crewscope.credential_secret WHERE id = ?",
                    byte[].class,
                    reference.credentialId().value());
            ciphertext[0] ^= 1;
            jdbcTemplate.update(
                    "UPDATE crewscope.credential_secret SET ciphertext = ? WHERE id = ?",
                    ciphertext,
                    reference.credentialId().value());
        });
        assertTamperingRejected("tag", reference -> {
            byte[] tag = jdbcTemplate.queryForObject(
                    "SELECT authentication_tag FROM crewscope.credential_secret WHERE id = ?",
                    byte[].class,
                    reference.credentialId().value());
            tag[0] ^= 1;
            jdbcTemplate.update(
                    "UPDATE crewscope.credential_secret SET authentication_tag = ? WHERE id = ?",
                    tag,
                    reference.credentialId().value());
        });
        assertTamperingRejected("provider", reference -> jdbcTemplate.update(
                "UPDATE crewscope.credential_secret SET provider_key = 'gitlab' WHERE id = ?",
                reference.credentialId().value()));
        assertTamperingRejected("connection", reference -> jdbcTemplate.update(
                "UPDATE crewscope.credential_secret SET connection_ref = ? WHERE id = ?",
                UUID.randomUUID(),
                reference.credentialId().value()));
        assertTamperingRejected("metadata", reference -> jdbcTemplate.update(
                """
                UPDATE crewscope.credential_secret
                SET metadata = CAST('{"account":"tampered"}' AS JSONB)
                WHERE id = ?
                """,
                reference.credentialId().value()));
    }

    @Test
    void rejectsTheWrongMasterKeyWithoutLeakingPlaintext() {
        CredentialCreateRequest request = request("wrong-key", Optional.empty());
        store.create(request, CredentialSecret.utf8(SECRET_TEXT));
        try (CredentialEncryptionKey wrongKey =
                new CredentialEncryptionKey("dev-key-1", keyBytes((byte) 0x72))) {
            DatabaseEnvelopeCredentialStore wrongStore = store(wrongKey);

            CredentialStoreException exception = assertError(
                    CredentialStoreError.INTEGRITY_VIOLATION,
                    () -> wrongStore.resolve(requestReference(request), access(request)));
            assertFalse(exception.getMessage().contains(SECRET_TEXT));
        }
    }

    @Test
    void rotatesSecretWithFreshEnvelopeAndOptimisticVersion() {
        CredentialCreateRequest request = request("rotate", Optional.empty());
        store.create(request, CredentialSecret.utf8("old-secret"));
        byte[] oldNonce = nonce(request.credentialId());

        clock.set(BASE_TIME.plusSeconds(10));
        CredentialDescriptor rotated = store.rotate(
                requestReference(request),
                0,
                mutation(),
                CredentialSecret.utf8("new-secret"));

        assertEquals(1, rotated.version());
        assertEquals(Optional.of(UtcTimestamp.from(BASE_TIME.plusSeconds(10))), rotated.rotatedAt());
        assertFalse(Arrays.equals(oldNonce, nonce(request.credentialId())));
        try (ResolvedCredential resolved =
                store.resolve(requestReference(request), access(request)).orElseThrow()) {
            assertArrayEquals(
                    "new-secret".getBytes(StandardCharsets.UTF_8),
                    resolved.secret().copyBytes());
        }
        assertError(
                CredentialStoreError.CONFLICT,
                () -> store.rotate(
                        requestReference(request),
                        0,
                        mutation(),
                        CredentialSecret.utf8("stale-secret")));
        assertError(
                CredentialStoreError.ACCESS_DENIED,
                () -> store.rotate(
                        requestReference(request),
                        1,
                        new CredentialMutationContext(OTHER_ORGANIZATION_ID, OTHER_ACTOR_ID),
                        CredentialSecret.utf8("forbidden-secret")));
    }

    @Test
    void revokesWithoutDecryptingAndRejectsStaleMutation() {
        CredentialCreateRequest request = request("revoke", Optional.empty());
        store.create(request, CredentialSecret.utf8(SECRET_TEXT));
        byte[] originalCiphertext = ciphertext(request.credentialId());

        clock.set(BASE_TIME.plusSeconds(20));
        CredentialDescriptor revoked = store.revoke(
                requestReference(request),
                0,
                mutation(),
                CredentialRevocationReason.CONNECTION_REVOKED);

        assertEquals(CredentialStatus.REVOKED, revoked.status());
        assertEquals(1, revoked.version());
        assertEquals(Optional.of(UtcTimestamp.from(BASE_TIME.plusSeconds(20))), revoked.revokedAt());
        assertFalse(Arrays.equals(originalCiphertext, ciphertext(request.credentialId())));
        assertTrue(store.resolve(requestReference(request), access(request)).isEmpty());
        jdbcTemplate.update(
                """
                UPDATE crewscope.credential_secret
                SET status = 'ACTIVE', revoked_at = NULL
                WHERE id = ?
                """,
                request.credentialId().value());
        assertError(
                CredentialStoreError.INTEGRITY_VIOLATION,
                () -> store.resolve(requestReference(request), access(request)));
        assertError(
                CredentialStoreError.CONFLICT,
                () -> store.revoke(
                        requestReference(request),
                        0,
                        mutation(),
                        CredentialRevocationReason.CONNECTION_REVOKED));
    }

    @Test
    void rejectsDuplicateOrganizationCredentialKeys() {
        CredentialCreateRequest first = request("duplicate", Optional.empty());
        CredentialCreateRequest second = new CredentialCreateRequest(
                CredentialId.generate(),
                first.subject(),
                first.credentialKey(),
                first.providerKey(),
                first.connectionRef(),
                first.credentialType(),
                first.metadata(),
                first.expiresAt(),
                first.createdBy());
        store.create(first, CredentialSecret.utf8(SECRET_TEXT));

        assertError(
                CredentialStoreError.CONFLICT,
                () -> store.create(second, CredentialSecret.utf8("different-secret")));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.credential_secret", Integer.class));
    }

    private void assertTamperingRejected(
            String suffix, Consumer<CredentialReference> tamper) {
        CredentialCreateRequest request = request(suffix, Optional.empty());
        store.create(request, CredentialSecret.utf8(SECRET_TEXT));
        CredentialReference reference = requestReference(request);
        tamper.accept(reference);

        assertError(
                CredentialStoreError.INTEGRITY_VIOLATION,
                () -> store.resolve(reference, access(request)));
        assertError(
                CredentialStoreError.INTEGRITY_VIOLATION,
                () -> store.rotate(
                        reference,
                        0,
                        mutation(),
                        CredentialSecret.utf8("replacement-secret")));
    }

    private CredentialCreateRequest request(
            String suffix, Optional<UtcTimestamp> expiresAt) {
        return new CredentialCreateRequest(
                CredentialId.generate(),
                CredentialSubject.principal(ORGANIZATION_ID, ACTOR_ID),
                "github-" + suffix,
                "github",
                Optional.of(UUID.randomUUID()),
                "OAUTH_TOKEN",
                Map.of("account", "crewscope"),
                expiresAt,
                ACTOR_ID);
    }

    private CredentialAccessContext access(CredentialCreateRequest request) {
        return new CredentialAccessContext(
                ORGANIZATION_ID,
                ACTOR_ID,
                Set.of(request.credentialId()),
                "github:push");
    }

    private static CredentialReference requestReference(CredentialCreateRequest request) {
        return new CredentialReference(
                request.subject().organizationId(), request.credentialId());
    }

    private static CredentialMutationContext mutation() {
        return new CredentialMutationContext(ORGANIZATION_ID, ACTOR_ID);
    }

    private byte[] nonce(CredentialId credentialId) {
        return jdbcTemplate.queryForObject(
                "SELECT nonce FROM crewscope.credential_secret WHERE id = ?",
                byte[].class,
                credentialId.value());
    }

    private byte[] ciphertext(CredentialId credentialId) {
        return jdbcTemplate.queryForObject(
                "SELECT ciphertext FROM crewscope.credential_secret WHERE id = ?",
                byte[].class,
                credentialId.value());
    }

    private DatabaseEnvelopeCredentialStore store(CredentialEncryptionKey key) {
        return new DatabaseEnvelopeCredentialStore(
                jdbcTemplate, objectMapper, key, new SecureRandom(), clock);
    }

    private void seedPrincipal(
            OrganizationId organizationId, PrincipalId principalId, String displayName) {
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                displayName + " Organization");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                principalId.value(),
                organizationId.value(),
                displayName);
    }

    private static byte[] keyBytes(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private static CredentialStoreException assertError(
            CredentialStoreError expected, Runnable action) {
        CredentialStoreException exception =
                assertThrows(CredentialStoreException.class, action::run);
        assertEquals(expected, exception.error());
        return exception;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("MutableClock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
