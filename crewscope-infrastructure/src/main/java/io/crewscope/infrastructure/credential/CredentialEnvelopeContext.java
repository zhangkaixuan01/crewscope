package io.crewscope.infrastructure.credential;

import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable non-secret fields bound to ciphertext through AAD v1. */
record CredentialEnvelopeContext(
        CredentialId credentialId,
        CredentialSubject subject,
        String credentialKey,
        String providerKey,
        Optional<UUID> connectionRef,
        String credentialType,
        Map<String, String> metadata,
        Optional<UtcTimestamp> expiresAt,
        String keyId,
        String algorithm,
        String aadVersion) {

    CredentialEnvelopeContext {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credentialKey, "credentialKey");
        Objects.requireNonNull(providerKey, "providerKey");
        connectionRef = Objects.requireNonNull(connectionRef, "connectionRef");
        Objects.requireNonNull(credentialType, "credentialType");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(aadVersion, "aadVersion");
    }
}
