package io.crewscope.domain.policy;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed PolicyPack aggregate identifier. */
public record PolicyPackId(UUID value) implements AggregateId {

    public PolicyPackId {
        value = AggregateId.requireValue(value, "PolicyPackId");
    }

    public static PolicyPackId generate() {
        return new PolicyPackId(AggregateId.generateValue());
    }

    public static PolicyPackId from(String value) {
        return new PolicyPackId(AggregateId.parseCanonical(value, "PolicyPackId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
