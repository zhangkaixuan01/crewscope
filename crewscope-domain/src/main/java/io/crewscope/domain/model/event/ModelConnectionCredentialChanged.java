package io.crewscope.domain.model.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Security-safe model credential lifecycle fact; the payload can never carry plaintext. */
public record ModelConnectionCredentialChanged(
        UUID connectionId,
        String operation,
        String providerKey,
        long credentialSecretVersion,
        String connectionStatus,
        Optional<String> failureCode) implements DomainEvent {

    public ModelConnectionCredentialChanged {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        operation = requireText(operation, "operation");
        providerKey = requireText(providerKey, "providerKey");
        if (credentialSecretVersion < 0) {
            throw new IllegalArgumentException("credentialSecretVersion must not be negative");
        }
        connectionStatus = requireText(connectionStatus, "connectionStatus");
        failureCode = Objects.requireNonNull(failureCode, "failureCode")
                .map(value -> requireText(value, "failureCode"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
