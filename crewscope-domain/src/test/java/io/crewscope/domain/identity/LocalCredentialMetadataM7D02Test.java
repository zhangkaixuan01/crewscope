package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** M7-D02 local credential version, metadata and sensitive string boundary. */
class LocalCredentialMetadataM7D02Test {

    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T09:00:00Z"));
    private static final UtcTimestamp ROTATED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T09:02:00Z"));
    private static final String ARGON_HASH =
            "{argon2id}$argon2id$v=19$m=32768,t=3,p=1$c2FsdA$ZW5jb2RlZC1oYXNo";
    private static final String BCRYPT_HASH =
            "{bcrypt}$2a$12$abcdefghijklmnopqrstuuR8MjJFZL7p57M4wJ7gT3zJqM5cfa";

    @Test
    void encodedHashRecognizesOnlyApprovedAlgorithmsAndAlwaysRedacts() {
        LocalPasswordHash argon = new LocalPasswordHash(ARGON_HASH);
        LocalPasswordHash bcrypt = new LocalPasswordHash(BCRYPT_HASH);

        assertEquals(PasswordHashAlgorithm.ARGON2ID, argon.algorithm());
        assertEquals(PasswordHashAlgorithm.BCRYPT, bcrypt.algorithm());
        assertEquals(ARGON_HASH, argon.encodedValue());
        assertEquals("LocalPasswordHash[REDACTED]", argon.toString());
        assertFalse(argon.toString().contains(ARGON_HASH));
    }

    @Test
    void encodedHashRejectsUnknownMalformedWhitespaceAndOversizeValues() {
        assertThrows(DomainValidationException.class, () -> new LocalPasswordHash("{scrypt}$encoded-value-that-is-long-enough"));
        assertThrows(DomainValidationException.class, () -> new LocalPasswordHash("{argon2id}$short"));
        assertThrows(DomainValidationException.class, () -> new LocalPasswordHash(ARGON_HASH + "\n"));
        assertThrows(
                DomainValidationException.class,
                () -> new LocalPasswordHash("{argon2id}" + "a".repeat(LocalPasswordHash.MAX_ENCODED_LENGTH)));
    }

    @Test
    void metadataCreationRetainsAlgorithmAndVersionsButNeverHash() {
        LocalCredentialMetadata metadata = metadata();

        assertEquals(PasswordHashAlgorithm.ARGON2ID, metadata.algorithm());
        assertEquals(LocalCredentialVersion.initial(), metadata.credentialVersion());
        assertEquals(0, metadata.version());
        assertEquals(CREATED_AT, metadata.passwordChangedAt());
        assertFalse(metadata.toString().contains(ARGON_HASH));
        assertFalse(metadata.toString().toLowerCase().contains("passwordhash"));
    }

    @Test
    void newCredentialRejectsHistoricalReaderAlgorithm() {
        assertThrows(
                DomainValidationException.class,
                () -> LocalCredentialMetadata.create(
                        LocalCredentialId.generate(),
                        UserAccountId.generate(),
                        new LocalPasswordHash(BCRYPT_HASH),
                        CREATED_AT));
    }

    @Test
    void metadataClassCannotRetainHashSecretOrPlaintextFields() {
        assertTrue(Arrays.stream(LocalCredentialMetadata.class.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .noneMatch(name -> name.contains("hash")
                        || name.contains("secret")
                        || name.contains("plaintext")
                        || name.contains("passwordvalue")));
        assertTrue(Arrays.stream(LocalCredentialMetadata.class.getDeclaredFields())
                .map(Field::getType)
                .noneMatch(LocalPasswordHash.class::equals));
    }

    @Test
    void rotationUpgradesHistoricalBcryptAndAdvancesBothVersions() {
        LocalCredentialMetadata historical = LocalCredentialMetadata.reconstitute(
                LocalCredentialId.generate(),
                UserAccountId.generate(),
                PasswordHashAlgorithm.BCRYPT,
                new LocalCredentialVersion(4),
                CREATED_AT,
                3,
                LifecycleMetadata.createdAt(CREATED_AT));
        LocalCredentialMetadata rotated = historical.rotate(
                new LocalPasswordHash(ARGON_HASH), ROTATED_AT);

        assertEquals(PasswordHashAlgorithm.ARGON2ID, rotated.algorithm());
        assertEquals(new LocalCredentialVersion(5), rotated.credentialVersion());
        assertEquals(4, rotated.version());
        assertEquals(ROTATED_AT, rotated.passwordChangedAt());
        assertEquals(ROTATED_AT, rotated.lifecycle().updatedAt());
    }

    @Test
    void rotationRejectsAlgorithmDowngrade() {
        assertThrows(
                DomainValidationException.class,
                () -> metadata().rotate(new LocalPasswordHash(BCRYPT_HASH), ROTATED_AT));
    }

    @Test
    void credentialVersionAndMetadataVersionRejectInvalidValuesAndOverflow() {
        assertThrows(DomainValidationException.class, () -> new LocalCredentialVersion(0));
        assertThrows(
                DomainValidationException.class,
                () -> new LocalCredentialVersion(Long.MAX_VALUE).next());

        LocalCredentialMetadata maximum = LocalCredentialMetadata.reconstitute(
                LocalCredentialId.generate(),
                UserAccountId.generate(),
                PasswordHashAlgorithm.ARGON2ID,
                LocalCredentialVersion.initial(),
                CREATED_AT,
                Long.MAX_VALUE,
                LifecycleMetadata.createdAt(CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> maximum.rotate(new LocalPasswordHash(ARGON_HASH), ROTATED_AT));
    }

    @Test
    void reconstitutionRejectsPasswordChangeOutsideLifecycle() {
        LifecycleMetadata lifecycle = LifecycleMetadata.createdAt(CREATED_AT).modifiedAt(ROTATED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> LocalCredentialMetadata.reconstitute(
                        LocalCredentialId.generate(),
                        UserAccountId.generate(),
                        PasswordHashAlgorithm.ARGON2ID,
                        LocalCredentialVersion.initial(),
                        UtcTimestamp.from(Instant.parse("2026-08-28T08:59:59Z")),
                        0,
                        lifecycle));
    }

    @Test
    void identityAndCredentialConflictsHaveNoSensitiveDetails() {
        LoginIdentityConflictException identityConflict = new LoginIdentityConflictException();
        LocalCredentialConflictException credentialConflict = new LocalCredentialConflictException();

        assertEquals(DomainErrorCode.LOGIN_IDENTITY_CONFLICT, identityConflict.error().code());
        assertEquals(DomainErrorCode.LOCAL_CREDENTIAL_CONFLICT, credentialConflict.error().code());
        assertTrue(identityConflict.error().details().isEmpty());
        assertTrue(credentialConflict.error().details().isEmpty());
        assertFalse(identityConflict.toString().contains("Subject-42"));
        assertFalse(credentialConflict.toString().contains(ARGON_HASH));
    }

    private static LocalCredentialMetadata metadata() {
        return LocalCredentialMetadata.create(
                LocalCredentialId.generate(),
                UserAccountId.generate(),
                new LocalPasswordHash(ARGON_HASH),
                CREATED_AT);
    }
}
