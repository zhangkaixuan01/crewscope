package io.crewscope.domain.organization.role;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed OrganizationRoleGrant aggregate identifier. */
public record OrganizationRoleGrantId(UUID value) implements AggregateId {

    public OrganizationRoleGrantId {
        value = AggregateId.requireValue(value, "OrganizationRoleGrantId");
    }

    public static OrganizationRoleGrantId generate() {
        return new OrganizationRoleGrantId(AggregateId.generateValue());
    }

    public static OrganizationRoleGrantId from(String value) {
        return new OrganizationRoleGrantId(AggregateId.parseCanonical(value, "OrganizationRoleGrantId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
