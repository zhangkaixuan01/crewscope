package io.crewscope.domain.provider;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed ProviderBinding identifier. */
public record ProviderBindingId(UUID value) implements AggregateId {

    public ProviderBindingId {
        value = AggregateId.requireValue(value, "ProviderBindingId");
    }

    public static ProviderBindingId generate() {
        return new ProviderBindingId(AggregateId.generateValue());
    }

    public static ProviderBindingId from(String value) {
        return new ProviderBindingId(
                AggregateId.parseCanonical(value, "ProviderBindingId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
