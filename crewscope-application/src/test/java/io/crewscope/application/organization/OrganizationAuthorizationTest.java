package io.crewscope.application.organization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The S01 §3.6 capability matrix as executable truth — including the orthogonality guarantee:
 * this authorization path only ever reads organization grants, and a principal without an active
 * grant is denied everything with the action detail the API contract exposes.
 */
class OrganizationAuthorizationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId principalId = PrincipalId.generate();
    private final OrganizationRoleGrantRepository grants =
            mock(OrganizationRoleGrantRepository.class);
    private final OrganizationAuthorization authorization = new OrganizationAuthorization(grants);
    private final Principal actor = Principal.create(
            principalId,
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Actor",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    @Test
    void auditorImpliesOnlyOrganizationAuditRead() {
        active(OrganizationRole.ORG_AUDITOR);

        assertDoesNotThrow(
                () -> authorization.requireCapability(
                        access(), organizationId, OrganizationCapability.AUDIT_READ_ORG));
        denied(OrganizationCapability.SETTINGS_READ, "read organization settings");
        denied(OrganizationCapability.SETTINGS_WRITE, "write organization settings");
        denied(OrganizationCapability.ROLE_ASSIGN, "assign organization roles");
        denied(OrganizationCapability.GOVERNANCE_EXPORT, "run organization data exports");
        denied(OrganizationCapability.GOVERNANCE_DELETION, "run account deletion governance");
    }

    @Test
    void adminImpliesGovernanceButNeitherOwnerPowerNorOwnerAssign() {
        active(OrganizationRole.ORG_ADMIN);

        for (OrganizationCapability capability : new OrganizationCapability[] {
                OrganizationCapability.AUDIT_READ_ORG,
                OrganizationCapability.SETTINGS_READ,
                OrganizationCapability.SETTINGS_WRITE,
                OrganizationCapability.ROLE_ASSIGN,
                OrganizationCapability.GOVERNANCE_EXPORT}) {
            assertDoesNotThrow(
                    () -> authorization.requireCapability(access(), organizationId, capability));
        }
        denied(
                OrganizationCapability.ROLE_ASSIGN_OWNER,
                "assign the ORG_OWNER role");
        denied(OrganizationCapability.ROLE_REVOKE_OWNER, "revoke the ORG_OWNER role");
        denied(OrganizationCapability.GOVERNANCE_DELETION, "run account deletion governance");
    }

    @Test
    void ownerImpliesEveryCapability() {
        active(OrganizationRole.ORG_OWNER);

        for (OrganizationCapability capability : OrganizationCapability.values()) {
            assertDoesNotThrow(
                    () -> authorization.requireCapability(access(), organizationId, capability));
        }
    }

    @Test
    void aPrincipalWithoutActiveGrantsIsDeniedEverythingWithTheActionDetail() {
        when(grants.findActiveByPrincipal(organizationId, principalId)).thenReturn(List.of());

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> authorization.requireCapability(
                        access(), organizationId, OrganizationCapability.SETTINGS_WRITE));
        assertEquals("write organization settings", denied.error().details().get("action"));
        assertEquals(Optional.empty(), authorization.roleOf(organizationId, principalId));
    }

    @Test
    void revokedGrantsDoNotConferCapabilities() {
        OrganizationRoleGrant grant = OrganizationRoleGrant.grant(
                organizationId, principalId, OrganizationRole.ORG_OWNER, principalId, null, NOW);
        OrganizationRoleGrant revoked = grant.revoke(0, principalId, "handover", NOW);
        when(grants.findActiveByPrincipal(organizationId, principalId))
                .thenReturn(List.of(revoked));

        denied(OrganizationCapability.AUDIT_READ_ORG, "read organization audit");
        assertEquals(Optional.empty(), authorization.roleOf(organizationId, principalId));
    }

    @Test
    void roleOfReportsTheHighestActiveRole() {
        when(grants.findActiveByPrincipal(organizationId, principalId))
                .thenReturn(List.of(
                        OrganizationRoleGrant.grant(
                                organizationId, principalId, OrganizationRole.ORG_AUDITOR,
                                principalId, null, NOW),
                        OrganizationRoleGrant.grant(
                                organizationId, principalId, OrganizationRole.ORG_OWNER,
                                principalId, null, NOW)));

        assertEquals(Optional.of(OrganizationRole.ORG_OWNER), authorization.roleOf(organizationId, principalId));
    }

    private OrganizationAccessContext access() {
        return new OrganizationAccessContext(actor);
    }

    private void active(OrganizationRole role) {
        when(grants.findActiveByPrincipal(organizationId, principalId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, principalId, role, principalId, null, NOW)));
    }

    private void denied(OrganizationCapability capability, String action) {
        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> authorization.requireCapability(access(), organizationId, capability));
        assertEquals(action, denied.error().details().get("action"));
    }
}
