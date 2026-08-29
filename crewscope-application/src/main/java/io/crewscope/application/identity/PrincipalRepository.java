package io.crewscope.application.identity;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Optional;

/**
 * Persistence Port for tenant-scoped Principal lookup and atomic external-identity provisioning.
 */
public interface PrincipalRepository {

  Optional<Principal> findById(OrganizationId organizationId, PrincipalId principalId);

  Optional<Principal> findByExternalIdentity(
      OrganizationId organizationId, String provider, String subject);

  boolean organizationExists(OrganizationId organizationId);

  /**
   * Inserts one new local USER Principal without fabricating an external identity. Local login
   * truth remains in LoginIdentity and AccountOrganizationBinding.
   */
  default Principal createLocalUser(Principal candidate) {
    throw new UnsupportedOperationException("Local USER Principal creation is not implemented");
  }

  /**
   * Inserts the candidate when its external identity is new, or returns the concurrently existing
   * Principal. Implementations must make this operation atomic at the database uniqueness boundary.
   */
  PrincipalProvisioningResult provisionUser(Principal candidate);
}
