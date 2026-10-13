package io.crewscope.application.organization;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Capability check for the ORG_* role layer (S01 freeze section 3.6).
 *
 * <p>Orthogonality is structural: this class reads organization grants only — it never consults
 * team roles or permissions, and no team-scope authorization path reads organization grants. An
 * ORG_ADMIN therefore has no team_member_role rows and every team write stays denied, while a
 * team OWNER without an ORG grant is denied here.
 *
 * <p>Grants are re-queried on every decision, mirroring how
 * {@code TeamApplicationService.requirePermission} treats team grants as per-request facts.
 */
public final class OrganizationAuthorization {

    private static final Map<OrganizationCapability, String> ACTIONS = buildActions();

    private final OrganizationRoleGrantRepository grants;

    public OrganizationAuthorization(OrganizationRoleGrantRepository grants) {
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    /** The caller's highest active role, or empty when the principal holds no active grant. */
    public Optional<OrganizationRole> roleOf(OrganizationId organizationId, Principal actor) {
        return roleOf(organizationId, actor.id());
    }

    /** Session-projection variant resolved by principal id. */
    public Optional<OrganizationRole> roleOf(OrganizationId organizationId, PrincipalId principalId) {
        List<OrganizationRole> roles = grants
                .findActiveByPrincipal(
                        Objects.requireNonNull(organizationId, "organizationId"),
                        Objects.requireNonNull(principalId, "principalId"))
                .stream()
                .filter(grant -> grant.isActive())
                .map(grant -> grant.role())
                .toList();
        if (roles.contains(OrganizationRole.ORG_OWNER)) {
            return Optional.of(OrganizationRole.ORG_OWNER);
        }
        if (roles.contains(OrganizationRole.ORG_ADMIN)) {
            return Optional.of(OrganizationRole.ORG_ADMIN);
        }
        if (roles.contains(OrganizationRole.ORG_AUDITOR)) {
            return Optional.of(OrganizationRole.ORG_AUDITOR);
        }
        return Optional.empty();
    }

    /** Denies with {@link PolicyDeniedException} when no active grant implies the capability. */
    public void requireCapability(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            OrganizationCapability capability) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(capability, "capability");
        if (!hasCapability(organizationId, access.actor().id(), capability)) {
            throw new PolicyDeniedException(ACTIONS.get(capability));
        }
    }

    private boolean hasCapability(
            OrganizationId organizationId, PrincipalId principalId, OrganizationCapability capability) {
        return grants
                .findActiveByPrincipal(
                        Objects.requireNonNull(organizationId, "organizationId"), principalId)
                .stream()
                .filter(grant -> grant.isActive())
                .anyMatch(grant -> grant.role().implies(capability));
    }

    private static Map<OrganizationCapability, String> buildActions() {
        Map<OrganizationCapability, String> actions = new EnumMap<>(OrganizationCapability.class);
        actions.put(OrganizationCapability.AUDIT_READ_ORG, "read organization audit");
        actions.put(OrganizationCapability.SETTINGS_READ, "read organization settings");
        actions.put(OrganizationCapability.SETTINGS_WRITE, "write organization settings");
        actions.put(OrganizationCapability.ROLE_ASSIGN, "assign organization roles");
        actions.put(OrganizationCapability.ROLE_ASSIGN_OWNER, "assign the ORG_OWNER role");
        actions.put(OrganizationCapability.ROLE_REVOKE_OWNER, "revoke the ORG_OWNER role");
        actions.put(OrganizationCapability.GOVERNANCE_EXPORT, "run organization data exports");
        actions.put(OrganizationCapability.GOVERNANCE_DELETION, "run account deletion governance");
        return Map.copyOf(actions);
    }
}
