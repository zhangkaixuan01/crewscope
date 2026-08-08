package io.crewscope.domain.provider;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed ProviderImplementation identifier. */
public record ProviderImplementationId(UUID value) implements AggregateId {

    public ProviderImplementationId {
        value = AggregateId.requireValue(value, "ProviderImplementationId");
    }

    public static ProviderImplementationId generate() {
        return new ProviderImplementationId(AggregateId.generateValue());
    }

    public static ProviderImplementationId from(String value) {
        return new ProviderImplementationId(
                AggregateId.parseCanonical(value, "ProviderImplementationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
