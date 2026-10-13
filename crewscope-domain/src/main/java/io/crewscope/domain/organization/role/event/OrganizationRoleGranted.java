package io.crewscope.domain.organization.role.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after an ORG_* role grant became effective. */
public record OrganizationRoleGranted(
        UUID organizationId, UUID principalId, String role, long versionAfter)
        implements DomainEvent {

    public OrganizationRoleGranted {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        principalId = Objects.requireNonNull(principalId, "principalId");
        role = Objects.requireNonNull(role, "role");
    }
}
