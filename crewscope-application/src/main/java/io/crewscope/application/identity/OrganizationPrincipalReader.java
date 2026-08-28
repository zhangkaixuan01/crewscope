package io.crewscope.application.identity;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Optional;

/** Read-only Principal port used by authentication; it deliberately exposes no provisioning. */
@FunctionalInterface
public interface OrganizationPrincipalReader {

    Optional<Principal> findById(OrganizationId organizationId, PrincipalId principalId);
}
