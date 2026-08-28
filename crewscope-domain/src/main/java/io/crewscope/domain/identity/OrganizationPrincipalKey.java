package io.crewscope.domain.identity;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Unique coordinate preventing one Organization USER Principal from owning two Accounts. */
public record OrganizationPrincipalKey(OrganizationId organizationId, PrincipalId principalId) {

    public OrganizationPrincipalKey {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        principalId = Objects.requireNonNull(principalId, "principalId");
    }
}
