package io.crewscope.domain.provider;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed ProviderDefinition identifier. */
public record ProviderDefinitionId(UUID value) implements AggregateId {

    public ProviderDefinitionId {
        value = AggregateId.requireValue(value, "ProviderDefinitionId");
    }

    public static ProviderDefinitionId generate() {
        return new ProviderDefinitionId(AggregateId.generateValue());
    }

    public static ProviderDefinitionId from(String value) {
        return new ProviderDefinitionId(
                AggregateId.parseCanonical(value, "ProviderDefinitionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
