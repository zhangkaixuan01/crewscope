package io.crewscope.domain.identity;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of one Account-to-Organization Principal binding. */
public record AccountOrganizationBindingId(UUID value) implements AggregateId {

    public AccountOrganizationBindingId {
        value = AggregateId.requireValue(value, "AccountOrganizationBindingId");
    }

    public static AccountOrganizationBindingId generate() {
        return new AccountOrganizationBindingId(AggregateId.generateValue());
    }

    public static AccountOrganizationBindingId from(String value) {
        return new AccountOrganizationBindingId(
                AggregateId.parseCanonical(value, "AccountOrganizationBindingId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
