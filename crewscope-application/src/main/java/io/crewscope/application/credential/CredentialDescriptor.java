package io.crewscope.application.credential;

import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Non-secret metadata describing one persisted credential envelope. */
public record CredentialDescriptor(
        CredentialId credentialId,
        CredentialSubject subject,
        String credentialKey,
        String providerKey,
        Optional<UUID> connectionRef,
        String credentialType,
        Map<String, String> metadata,
        CredentialStatus status,
        Optional<UtcTimestamp> expiresAt,
        Optional<UtcTimestamp> rotatedAt,
        Optional<UtcTimestamp> revokedAt,
        String keyId,
        String algorithm,
        String aadVersion,
        PrincipalId createdBy,
        PrincipalId updatedBy,
        UtcTimestamp createdAt,
        UtcTimestamp updatedAt,
        long version) {

    public CredentialDescriptor {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(subject, "subject");
        credentialKey = CredentialCreateRequest.requireText(credentialKey, "credentialKey", 200);
        providerKey = CredentialCreateRequest.requireText(providerKey, "providerKey", 100);
        connectionRef = Objects.requireNonNull(connectionRef, "connectionRef");
        credentialType = CredentialCreateRequest.requireText(credentialType, "credentialType", 64);
        metadata = CredentialCreateRequest.immutableMetadata(metadata);
        Objects.requireNonNull(status, "status");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        rotatedAt = Objects.requireNonNull(rotatedAt, "rotatedAt");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
        keyId = CredentialCreateRequest.requireText(keyId, "keyId", 200);
        algorithm = CredentialCreateRequest.requireText(algorithm, "algorithm", 32);
        aadVersion = CredentialCreateRequest.requireText(aadVersion, "aadVersion", 32);
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.compareTo(createdAt) < 0 || version < 0) {
            throw new IllegalArgumentException("Credential lifecycle metadata is invalid");
        }
        expiresAt.ifPresent(value -> requireAfter(value, createdAt, "expiresAt"));
        rotatedAt.ifPresent(value -> requireLifecycleTime(value, createdAt, updatedAt, "rotatedAt"));
        revokedAt.ifPresent(value -> requireLifecycleTime(value, createdAt, updatedAt, "revokedAt"));
        if ((status == CredentialStatus.REVOKED) != revokedAt.isPresent()) {
            throw new IllegalArgumentException("Credential revocation metadata is inconsistent");
        }
    }

    public CredentialReference reference() {
        return new CredentialReference(subject.organizationId(), credentialId);
    }

    public boolean isUsableAt(UtcTimestamp timestamp) {
        UtcTimestamp now = Objects.requireNonNull(timestamp, "timestamp");
        return status == CredentialStatus.ACTIVE
                && expiresAt.map(deadline -> deadline.compareTo(now) > 0).orElse(true);
    }

    private static void requireAfter(
            UtcTimestamp value, UtcTimestamp boundary, String field) {
        if (value.compareTo(boundary) <= 0) {
            throw new IllegalArgumentException(field + " must be after createdAt");
        }
    }

    private static void requireLifecycleTime(
            UtcTimestamp value,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt,
            String field) {
        if (value.compareTo(createdAt) < 0 || value.compareTo(updatedAt) > 0) {
            throw new IllegalArgumentException(field + " must be within the credential lifecycle");
        }
    }
}
