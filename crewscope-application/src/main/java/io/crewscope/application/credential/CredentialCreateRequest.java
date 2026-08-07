package io.crewscope.application.credential;

import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Validated metadata used to create one encrypted credential envelope. */
public record CredentialCreateRequest(
        CredentialId credentialId,
        CredentialSubject subject,
        String credentialKey,
        String providerKey,
        Optional<UUID> connectionRef,
        String credentialType,
        Map<String, String> metadata,
        Optional<UtcTimestamp> expiresAt,
        PrincipalId createdBy) {

    public CredentialCreateRequest {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(subject, "subject");
        credentialKey = requireText(credentialKey, "credentialKey", 200);
        providerKey = requireText(providerKey, "providerKey", 100);
        connectionRef = Objects.requireNonNull(connectionRef, "connectionRef");
        connectionRef.ifPresent(value -> {
            if (value.equals(new UUID(0L, 0L))) {
                throw new IllegalArgumentException("connectionRef must not use the nil UUID");
            }
        });
        credentialType = requireText(credentialType, "credentialType", 64);
        metadata = immutableMetadata(metadata);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdBy, "createdBy");
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds its maximum length");
        }
        return normalized;
    }

    static Map<String, String> immutableMetadata(Map<String, String> value) {
        Objects.requireNonNull(value, "metadata");
        if (value.size() > 64) {
            throw new IllegalArgumentException("metadata must contain at most 64 entries");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        value.forEach((key, item) -> {
            String normalizedKey = requireText(key, "metadata key", 100);
            String previous = normalized.put(
                    normalizedKey, requireText(item, "metadata value", 1000));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "metadata contains duplicate keys after normalization");
            }
        });
        return Collections.unmodifiableMap(normalized);
    }
}
