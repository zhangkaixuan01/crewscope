package io.crewscope.domain.identity;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of one deployment-level local password credential. */
public record LocalCredentialId(UUID value) implements AggregateId {

    public LocalCredentialId {
        value = AggregateId.requireValue(value, "LocalCredentialId");
    }

    public static LocalCredentialId generate() {
        return new LocalCredentialId(AggregateId.generateValue());
    }

    public static LocalCredentialId from(String value) {
        return new LocalCredentialId(AggregateId.parseCanonical(value, "LocalCredentialId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
