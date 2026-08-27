package io.crewscope.application.projection;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Enforces the current active USER and trusted platform-administrator request facts. */
public final class DefaultProjectionAdministration implements ProjectionAdministration {

    @Override
    public void requireOrganizationAdministrator(
            OrganizationId organizationId,
            TeamAccessContext access,
            UtcTimestamp occurredAt) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        TeamAccessContext trusted = Objects.requireNonNull(access, "access");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Principal actor = trusted.actor();
        if (!trusted.platformAdministrator()
                || actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organization)) {
            throw new PolicyDeniedException("administer Organization operations");
        }
    }
}
