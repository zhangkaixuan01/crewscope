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
   * Inserts the candidate when its external identity is new, or returns the concurrently existing
   * Principal. Implementations must make this operation atomic at the database uniqueness boundary.
   */
  PrincipalProvisioningResult provisionUser(Principal candidate);
}
