package io.crewscope.application.organization;

import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRoleGrantId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for the OrganizationRoleGrant aggregate (V68). */
public interface OrganizationRoleGrantRepository {

    /** Inserts a new ACTIVE grant; duplicate active triples fail as a grant conflict. */
    OrganizationRoleGrant create(OrganizationRoleGrant grant);

    /** Locks and returns the grant row for command execution, or empty when absent. */
    Optional<OrganizationRoleGrant> findByIdForUpdate(
            OrganizationId organizationId, OrganizationRoleGrantId grantId);

    /** Persists a revoke result under optimistic versioning. */
    OrganizationRoleGrant update(OrganizationRoleGrant grant);

    /** All grants of the organization, active first — the role-management listing face. */
    List<OrganizationRoleGrant> findByOrganization(OrganizationId organizationId);

    /**
     * The caller's ACTIVE grants, re-queried on every authorization decision — never cached
     * across requests (TeamApplicationService.requirePermission precedent).
     */
    List<OrganizationRoleGrant> findActiveByPrincipal(
            OrganizationId organizationId, PrincipalId principalId);

    /** Number of ACTIVE ORG_OWNER grants; drives the last-owner protection. */
    long countActiveOwners(OrganizationId organizationId);

    /**
     * Serializes authorization changes of one organization on the caller's transaction. The
     * last-owner guard reads an unguarded {@link #countActiveOwners}, so two concurrent
     * revocations of <em>different</em> owner grants must not interleave (their per-row locks
     * never meet). The default is a no-op for single-threaded fakes; the production JDBC
     * adapter overrides it with a PostgreSQL advisory transaction lock and MUST keep doing so.
     */
    default void lockAuthorizationChanges(OrganizationId organizationId) {}

    /** Whether any ACTIVE ORG_OWNER exists; drives the initial-owner provisioning guard. */
    boolean hasActiveOwner(OrganizationId organizationId);

    /** Reserved for governance surfaces (C2); the fixed vocabulary keeps role filtering simple. */
    default List<OrganizationRoleGrant> findActiveByRole(
            OrganizationId organizationId, OrganizationRole role) {
        return findByOrganization(organizationId).stream()
                .filter(grant -> grant.isActive() && grant.role() == role)
                .toList();
    }
}
