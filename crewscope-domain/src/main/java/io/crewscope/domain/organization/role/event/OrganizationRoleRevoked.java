package io.crewscope.domain.organization.role.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after an ORG_* role grant was revoked (irreversible terminal state). */
public record OrganizationRoleRevoked(
        UUID organizationId, UUID principalId, String role, String reason, long versionAfter)
        implements DomainEvent {

    public OrganizationRoleRevoked {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        principalId = Objects.requireNonNull(principalId, "principalId");
        role = Objects.requireNonNull(role, "role");
    }
}
