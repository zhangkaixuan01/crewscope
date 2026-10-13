package io.crewscope.application.organization;

import io.crewscope.domain.identity.Principal;
import java.util.Objects;

/**
 * Server-resolved identity for one organization-level request.
 *
 * <p>Deliberately carries no platform-administrator flag: organization governance is judged from
 * ORG_* grants only, so the deployment-level OPERATOR role cannot bypass organization boundaries
 * (it reaches ORG_OWNER through the initial-owner provisioning instead).
 */
public record OrganizationAccessContext(Principal actor) {

    public OrganizationAccessContext {
        actor = Objects.requireNonNull(actor, "actor");
    }
}
