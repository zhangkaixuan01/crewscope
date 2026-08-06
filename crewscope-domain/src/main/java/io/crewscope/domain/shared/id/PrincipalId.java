package io.crewscope.domain.shared.id;

import java.util.UUID;

/** Strongly typed User, Agent or Service Principal identifier. */
public record PrincipalId(UUID value) implements AggregateId {

    public PrincipalId {
        value = AggregateId.requireValue(value, "PrincipalId");
    }

    public static PrincipalId generate() {
        return new PrincipalId(AggregateId.generateValue());
    }

    public static PrincipalId from(String value) {
        return new PrincipalId(AggregateId.parseCanonical(value, "PrincipalId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
