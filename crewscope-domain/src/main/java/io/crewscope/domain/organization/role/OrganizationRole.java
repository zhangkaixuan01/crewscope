package io.crewscope.domain.organization.role;

import java.util.Map;
import java.util.Set;

/**
 * The organization-level role vocabulary (S01 freeze section 3.6): a third layer next to
 * PlatformRole and TeamRoleKey, deliberately not merged into either.
 *
 * <p>Capabilities are hierarchical (OWNER implies ADMIN implies AUDITOR) and, per the freeze,
 * no organization role implies any team-level write permission.
 */
public enum OrganizationRole {
    ORG_OWNER,
    ORG_ADMIN,
    ORG_AUDITOR;

    private static final Map<OrganizationRole, Set<OrganizationCapability>> CAPABILITIES = Map.of(
            ORG_AUDITOR, Set.of(OrganizationCapability.AUDIT_READ_ORG),
            ORG_ADMIN, Set.of(
                    OrganizationCapability.AUDIT_READ_ORG,
                    OrganizationCapability.SETTINGS_READ,
                    OrganizationCapability.SETTINGS_WRITE,
                    OrganizationCapability.ROLE_ASSIGN,
                    OrganizationCapability.GOVERNANCE_EXPORT),
            ORG_OWNER, Set.of(
                    OrganizationCapability.AUDIT_READ_ORG,
                    OrganizationCapability.SETTINGS_READ,
                    OrganizationCapability.SETTINGS_WRITE,
                    OrganizationCapability.ROLE_ASSIGN,
                    OrganizationCapability.ROLE_ASSIGN_OWNER,
                    OrganizationCapability.ROLE_REVOKE_OWNER,
                    OrganizationCapability.GOVERNANCE_EXPORT,
                    OrganizationCapability.GOVERNANCE_DELETION));

    /** Whether this role carries the capability; the authority check re-queries grants per request. */
    public boolean implies(OrganizationCapability capability) {
        return CAPABILITIES.get(this).contains(capability);
    }
}
