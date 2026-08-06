package io.crewscope.domain.shared.id;

import java.util.UUID;

/** Strongly typed Organization aggregate identifier. */
public record OrganizationId(UUID value) implements AggregateId {

    public OrganizationId {
        value = AggregateId.requireValue(value, "OrganizationId");
    }

    public static OrganizationId generate() {
        return new OrganizationId(AggregateId.generateValue());
    }

    public static OrganizationId from(String value) {
        return new OrganizationId(AggregateId.parseCanonical(value, "OrganizationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
