package io.crewscope.application.identity;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;

/**
 * Account-creation-chain hook that grants the first ORG_OWNER of an organization (S01 freeze
 * section 3.6: initial assignment goes to the bootstrap seeder's first account).
 *
 * <p>The V68 migration backfill covers existing databases; this hook covers fresh installs where
 * the migration ran before any account existed. Wiring adapts it to the organization-role
 * application service, keeping the identity package free of organization imports.
 */
@FunctionalInterface
public interface InitialOrganizationOwnerProvisioner {

    /** Idempotent; concurrent winners of the active-grant index turn losers into no-ops. */
    void ensureInitialOwner(OrganizationId organizationId, PrincipalId principalId);
}
