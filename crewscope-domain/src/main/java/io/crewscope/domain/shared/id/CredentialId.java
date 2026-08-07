package io.crewscope.domain.shared.id;

import java.util.UUID;

/** Strongly typed identifier for one stored credential envelope. */
public record CredentialId(UUID value) implements AggregateId {

    public CredentialId {
        value = AggregateId.requireValue(value, "CredentialId");
    }

    public static CredentialId generate() {
        return new CredentialId(AggregateId.generateValue());
    }

    public static CredentialId from(String value) {
        return new CredentialId(AggregateId.parseCanonical(value, "CredentialId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
