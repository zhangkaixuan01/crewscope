package io.crewscope.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LarkCollaborationDomainM6D04Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000701");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000702");
    private static final TeamMemberId MEMBER_ID =
            TeamMemberId.from("00000000-0000-0000-0000-000000000703");
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T10:00:00Z");

    @Test
    void acceptsOnlyExactExternalIdentityShapesAndRedactsStringRepresentations() {
        LarkTenantKey tenantKey = new LarkTenantKey("tenant-a");
        LarkOpenId openId = new LarkOpenId("ou_member_100");
        LarkUnionId unionId = new LarkUnionId("on_union_100");

        assertThrows(DomainValidationException.class, () -> new LarkOpenId("Zhang Kaixuan"));
        assertThrows(DomainValidationException.class, () -> new LarkOpenId("user@example.com"));
        assertThrows(DomainValidationException.class, () -> new LarkUnionId("ou_member_100"));
        assertFalse((tenantKey + " " + openId + " " + unionId).contains("member_100"));
        assertFalse(new LarkExternalMemberKey(
                        ORGANIZATION_ID, tenantKey, openId)
                .equals(new LarkExternalMemberKey(
                        OrganizationId.generate(), tenantKey, openId)));
    }

    @Test
    void tenantVerificationRequiresConfiguredKeyAndExactLookupCapability() {
        LarkConnectionAuthorization authorization = authorization(1, 1, 1,
                LarkCollaborationCapabilities.COMPLETE);

        LarkExternalTenant tenant = LarkExternalTenant.verify(
                authorization,
                new LarkTenantKey("tenant-a"),
                new LarkProviderVersion("tenant-v1"),
                NOW);

        assertTrue(tenant.isCurrent(authorization));
        assertThrows(
                DomainValidationException.class,
                () -> LarkExternalTenant.verify(
                        authorization,
                        new LarkTenantKey("tenant-b"),
                        new LarkProviderVersion("tenant-v1"),
                        NOW));
        assertThrows(
                DomainValidationException.class,
                () -> LarkExternalTenant.verify(
                        authorization(1, 1, 1,
                                LarkCollaborationCapabilities.NOTIFICATION_DELIVERY),
                        new LarkTenantKey("tenant-a"),
                        new LarkProviderVersion("tenant-v1"),
                        NOW));

        LarkExternalTenant invalidated = tenant.invalidate(tenant.version());
        assertFalse(invalidated.isCurrent(authorization));
        assertThrows(
                IllegalStateException.class,
                () -> invalidated.refresh(
                        invalidated.version(),
                        authorization,
                        new LarkTenantKey("tenant-a"),
                        new LarkProviderVersion("tenant-v2"),
                        UtcTimestamp.parse("2026-08-25T10:01:00Z")));
    }

    @Test
    void proofRejectsLateConfirmationAndEveryAuthorizationDrift() {
        LarkConnectionAuthorization authorization = authorization(1, 1, 1,
                LarkCollaborationCapabilities.COMPLETE);
        LarkExternalTenant tenant = tenant(authorization);
        LarkMemberVerificationProof proof = proof(authorization, tenant);

        proof.requireConfirmable(
                authorization, tenant, UtcTimestamp.parse("2026-08-25T10:14:59Z"));
        assertThrows(
                DomainValidationException.class,
                () -> proof.requireConfirmable(
                        authorization, tenant, UtcTimestamp.parse("2026-08-25T10:15:00Z")));
        assertThrows(
                DomainValidationException.class,
                () -> proof.requireConfirmable(
                        authorization(2, 1, 1, LarkCollaborationCapabilities.COMPLETE),
                        tenant,
                        NOW));
        assertThrows(
                DomainValidationException.class,
                () -> proof.requireConfirmable(
                        authorization(1, 2, 1, LarkCollaborationCapabilities.COMPLETE),
                        tenant,
                        NOW));
        assertThrows(
                DomainValidationException.class,
                () -> proof.requireConfirmable(
                        authorization(1, 1, 2, LarkCollaborationCapabilities.COMPLETE),
                        tenant,
                        NOW));
        LarkConnectionAuthorization otherTeam = new LarkConnectionAuthorization(
                ORGANIZATION_ID,
                TeamId.generate(),
                authorization.providerBindingId(),
                authorization.providerBindingVersion(),
                authorization.connectionId(),
                authorization.connectionVersion(),
                authorization.grantId(),
                authorization.grantVersion(),
                authorization.expectedTenantKey(),
                authorization.effectiveCapabilities());
        assertThrows(
                DomainValidationException.class,
                () -> proof.requireConfirmable(otherTeam, tenant, NOW));
    }

    @Test
    void mappingResolvesRecipientOnlyForCurrentActiveMemberAndAuthorization() {
        LarkConnectionAuthorization authorization = authorization(1, 1, 1,
                LarkCollaborationCapabilities.COMPLETE);
        LarkExternalTenant tenant = tenant(authorization);
        TeamMember member = member(MEMBER_ID, TEAM_ID);
        Principal admin = principal(
                "00000000-0000-0000-0000-000000000704", TEAM_ID, "Admin");
        LarkMemberMapping mapping = LarkMemberMapping.confirm(
                LarkMemberMappingId.generate(),
                member,
                authorization,
                tenant,
                proof(authorization, tenant),
                admin,
                NOW);

        CollaborationRecipient recipient = mapping.resolveRecipient(member, authorization, tenant);

        assertEquals(MEMBER_ID, recipient.memberId());
        assertFalse(recipient.toString().contains("ou_member_100"));
        assertThrows(
                DomainValidationException.class,
                () -> mapping.resolveRecipient(
                        member,
                        authorization(2, 1, 1, LarkCollaborationCapabilities.COMPLETE),
                        tenant));
        assertThrows(
                DomainValidationException.class,
                () -> mapping.resolveRecipient(member.suspend(NOW), authorization, tenant));
    }

    @Test
    void revocationIsStrongVersionedAndHistoricalIdentityRemainsImmutable() {
        LarkConnectionAuthorization authorization = authorization(1, 1, 1,
                LarkCollaborationCapabilities.COMPLETE);
        LarkExternalTenant tenant = tenant(authorization);
        TeamMember member = member(MEMBER_ID, TEAM_ID);
        Principal admin = principal(
                "00000000-0000-0000-0000-000000000704", TEAM_ID, "Admin");
        LarkMemberMapping mapping = LarkMemberMapping.confirm(
                LarkMemberMappingId.generate(), member, authorization, tenant,
                proof(authorization, tenant), admin, NOW);

        LarkMemberMapping revoked = mapping.terminate(
                mapping.version(), LarkMemberMappingStatus.REVOKED,
                LarkMemberMappingTerminalReason.ADMIN_REVOKED, admin,
                UtcTimestamp.parse("2026-08-25T10:01:00Z"));

        assertEquals(LarkMemberMappingStatus.REVOKED, revoked.status());
        assertEquals(mapping.openId(), revoked.openId());
        assertThrows(
                io.crewscope.domain.shared.error.OptimisticLockConflictException.class,
                () -> mapping.terminate(
                        mapping.version() + 1,
                        LarkMemberMappingStatus.REVOKED,
                        LarkMemberMappingTerminalReason.ADMIN_REVOKED,
                        admin,
                        NOW));
        assertThrows(
                DomainValidationException.class,
                () -> revoked.resolveRecipient(member, authorization, tenant));
    }

    private static LarkConnectionAuthorization authorization(
            long bindingVersion,
            long connectionVersion,
            long grantVersion,
            ProviderCapabilities capabilities) {
        return new LarkConnectionAuthorization(
                ORGANIZATION_ID,
                TEAM_ID,
                new ProviderBindingId(
                        UUID.fromString("00000000-0000-0000-0000-000000000705")),
                bindingVersion,
                new ConnectionId(
                        UUID.fromString("00000000-0000-0000-0000-000000000706")),
                connectionVersion,
                new ConnectionGrantId(
                        UUID.fromString("00000000-0000-0000-0000-000000000707")),
                grantVersion,
                new LarkTenantKey("tenant-a"),
                capabilities);
    }

    private static LarkExternalTenant tenant(LarkConnectionAuthorization authorization) {
        return LarkExternalTenant.verify(
                authorization,
                new LarkTenantKey("tenant-a"),
                new LarkProviderVersion("tenant-v1"),
                NOW);
    }

    private static LarkMemberVerificationProof proof(
            LarkConnectionAuthorization authorization, LarkExternalTenant tenant) {
        return LarkMemberVerificationProof.verified(
                LarkMemberVerificationProofId.generate(),
                authorization,
                tenant,
                new LarkOpenId("ou_member_100"),
                new LarkOpenId("ou_member_100"),
                new LarkUnionId("on_union_100"),
                new LarkProviderVersion("member-v1"),
                NOW,
                UtcTimestamp.parse("2026-08-25T10:15:00Z"));
    }

    private static TeamMember member(TeamMemberId id, TeamId teamId) {
        Principal user = principal(
                "00000000-0000-0000-0000-000000000708", teamId, "Member");
        return TeamMember.join(
                id,
                new TeamScope(ORGANIZATION_ID, teamId),
                user,
                TeamJoinMethod.BOOTSTRAP,
                NOW);
    }

    private static Principal principal(String id, TeamId teamId, String name) {
        return Principal.create(
                PrincipalId.from(id),
                PrincipalScope.team(ORGANIZATION_ID, teamId),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
    }
}
