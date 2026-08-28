package io.crewscope.domain.team;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamInvitationM7D05Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T09:00:00Z"));
    private static final UtcTimestamp LATER =
            UtcTimestamp.from(Instant.parse("2026-08-28T10:00:00Z"));
    private static final UtcTimestamp EXPIRY =
            UtcTimestamp.from(Instant.parse("2026-09-04T09:00:00Z"));
    private static final InvitationTokenDigest DIGEST = digest(1);

    @Test
    void issuesPendingInvitationWithOnlyDigestAndStableScope() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue(
                Optional.of(NormalizedEmail.fromDisplayValue("Invitee@Example.com")),
                BuiltInTeamRole.MEMBER);

        assertEquals(fixture.team.scope(), invitation.scope());
        assertEquals(fixture.inviter.id(), invitation.invitedByPrincipalId());
        assertEquals(BuiltInTeamRole.MEMBER, invitation.targetRole());
        assertEquals(DIGEST, invitation.tokenDigest());
        assertEquals(EXPIRY, invitation.expiresAt());
        assertEquals(TeamInvitationStatus.PENDING, invitation.status());
        assertTrue(invitation.acceptedByAccountId().isEmpty());
        assertTrue(invitation.acceptedMemberId().isEmpty());
        assertTrue(invitation.resolvedAt().isEmpty());
        assertEquals(0, invitation.version());
        assertTrue(invitation.isPendingAt(NOW));
    }

    @Test
    void digestIsFixedRedactedAndComparedWithoutExposingTheEncoding() {
        byte[] bytes = new byte[InvitationTokenDigest.BYTE_LENGTH];
        bytes[0] = 0x0a;
        InvitationTokenDigest fromBytes = InvitationTokenDigest.fromBytes(bytes);
        InvitationTokenDigest same = InvitationTokenDigest.fromHex(
                fromBytes.valueForPersistence());

        assertEquals("[REDACTED]", fromBytes.toString());
        assertTrue(fromBytes.matches(same));
        assertFalse(fromBytes.matches(digest(2)));
        assertArrayEquals(bytes, java.util.HexFormat.of().parseHex(fromBytes.valueForPersistence()));
        assertThrows(
                DomainValidationException.class,
                () -> InvitationTokenDigest.fromHex("A".repeat(64)));
        assertThrows(
                DomainValidationException.class,
                () -> InvitationTokenDigest.fromHex("0".repeat(63)));
        assertThrows(
                DomainValidationException.class,
                () -> InvitationTokenDigest.fromBytes(new byte[31]));
    }

    @Test
    void issueRejectsArchivedTeamOwnerRoleAndInvalidExpiry() {
        Fixture fixture = new Fixture();
        Team archived = fixture.team.archive(fixture.inviter.id(), LATER);

        assertThrows(
                DomainValidationException.class,
                () -> TeamInvitation.issue(
                        TeamInvitationId.generate(),
                        archived,
                        fixture.inviter,
                        Optional.empty(),
                        BuiltInTeamRole.MEMBER,
                        DIGEST,
                        EXPIRY,
                        NOW));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.issue(Optional.empty(), BuiltInTeamRole.TEAM_OWNER));
        assertThrows(
                DomainValidationException.class,
                () -> TeamInvitation.issue(
                        TeamInvitationId.generate(),
                        fixture.team,
                        fixture.inviter,
                        Optional.empty(),
                        BuiltInTeamRole.MEMBER,
                        DIGEST,
                        NOW,
                        NOW));
    }

    @Test
    void issueRejectsWrongTypeScopeOrganizationAndInactiveInviter() {
        Fixture fixture = new Fixture();
        Principal service = principal(
                PrincipalId.generate(),
                fixture.organizationId,
                PrincipalType.SERVICE,
                PrincipalStatus.ACTIVE,
                false);
        Principal teamScoped = principal(
                PrincipalId.generate(),
                fixture.organizationId,
                PrincipalType.USER,
                PrincipalStatus.ACTIVE,
                true);
        Principal crossOrganization = user(OrganizationId.generate());
        Principal disabled = principal(
                PrincipalId.generate(),
                fixture.organizationId,
                PrincipalType.USER,
                PrincipalStatus.DISABLED,
                false);

        for (Principal invalid : new Principal[] {
            service, teamScoped, crossOrganization, disabled
        }) {
            assertThrows(
                    DomainValidationException.class,
                    () -> TeamInvitation.issue(
                            TeamInvitationId.generate(),
                            fixture.team,
                            invalid,
                            Optional.empty(),
                            BuiltInTeamRole.MEMBER,
                            DIGEST,
                            EXPIRY,
                            NOW));
        }
    }

    @Test
    void targetedAndOpenInvitationsUseTheAccountsNormalizedEmail() {
        Fixture fixture = new Fixture();
        TeamInvitation targeted = fixture.issue(
                Optional.of(NormalizedEmail.fromDisplayValue("Invitee@EXAMPLE.com")),
                BuiltInTeamRole.MEMBER);
        TeamInvitation open = fixture.issue(Optional.empty(), BuiltInTeamRole.MEMBER);

        assertTrue(targeted.targets(fixture.account));
        assertFalse(targeted.targets(account("other", "other@example.com")));
        assertTrue(open.targets(account("anyone", "anyone@example.com")));
    }

    @Test
    void acceptsOnceAndBindsStableAccountAndMembership() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue(
                Optional.of(fixture.account.normalizedEmail()), BuiltInTeamRole.TEAM_LEAD);

        TeamInvitation accepted = invitation.accept(
                fixture.account,
                fixture.binding,
                fixture.userPrincipal,
                fixture.team,
                fixture.member,
                DIGEST,
                LATER);

        assertEquals(TeamInvitationStatus.ACCEPTED, accepted.status());
        assertEquals(Optional.of(fixture.account.id()), accepted.acceptedByAccountId());
        assertEquals(Optional.of(fixture.member.id()), accepted.acceptedMemberId());
        assertEquals(Optional.of(LATER), accepted.resolvedAt());
        assertEquals(1, accepted.version());
        assertFalse(accepted.isPendingAt(LATER));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> accepted.accept(
                        fixture.account,
                        fixture.binding,
                        fixture.userPrincipal,
                        fixture.team,
                        fixture.member,
                        DIGEST,
                        LATER));
    }

    @Test
    void acceptanceRejectsWrongDigestAndTargetEmail() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue(
                Optional.of(fixture.account.normalizedEmail()), BuiltInTeamRole.MEMBER);

        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        fixture.account,
                        fixture.binding,
                        fixture.userPrincipal,
                        fixture.team,
                        fixture.member,
                        digest(9),
                        LATER));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.issue(
                                Optional.of(NormalizedEmail.fromDisplayValue("other@example.com")),
                                BuiltInTeamRole.MEMBER)
                        .accept(
                                fixture.account,
                                fixture.binding,
                                fixture.userPrincipal,
                                fixture.team,
                                fixture.member,
                                DIGEST,
                                LATER));
    }

    @Test
    void acceptanceRejectsInactiveAccountAndBindingConflicts() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue(Optional.empty(), BuiltInTeamRole.MEMBER);
        UserAccount disabledAccount = inactiveAccount(fixture.account, AccountStatus.DISABLED);
        AccountOrganizationBinding disabledBinding = fixture.binding.disable(LATER);
        UserAccount otherAccount = account("other", "other@example.com");
        Principal disabledPrincipal = principal(
                fixture.userPrincipal.id(),
                fixture.organizationId,
                PrincipalType.USER,
                PrincipalStatus.DISABLED,
                false);

        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        disabledAccount,
                        fixture.binding,
                        fixture.userPrincipal,
                        fixture.team,
                        fixture.member,
                        DIGEST,
                        LATER));
        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        fixture.account,
                        disabledBinding,
                        fixture.userPrincipal,
                        fixture.team,
                        fixture.member,
                        DIGEST,
                        LATER));
        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        fixture.account,
                        fixture.binding,
                        disabledPrincipal,
                        fixture.team,
                        fixture.member,
                        DIGEST,
                        LATER));
        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        otherAccount,
                        fixture.binding,
                        fixture.userPrincipal,
                        fixture.team,
                        fixture.member,
                        DIGEST,
                        LATER));
    }

    @Test
    void acceptanceRejectsArchivedOrCrossTeamAndIncompatibleMembership() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue(Optional.empty(), BuiltInTeamRole.MEMBER);
        Team archived = fixture.team.archive(fixture.inviter.id(), LATER);
        Team otherTeam = team(fixture.organizationId, fixture.inviter.id());
        TeamMember otherMember = otherTeam.acceptInvitedMember(
                TeamMemberId.generate(), fixture.userPrincipal, fixture.inviter.id(), NOW);
        TeamMember suspended = fixture.member.suspend(LATER);

        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        fixture.account,
                        fixture.binding,
                        fixture.userPrincipal,
                        archived,
                        fixture.member,
                        DIGEST,
                        LATER));
        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        fixture.account,
                        fixture.binding,
                        fixture.userPrincipal,
                        otherTeam,
                        otherMember,
                        DIGEST,
                        LATER));
        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        fixture.account,
                        fixture.binding,
                        fixture.userPrincipal,
                        fixture.team,
                        suspended,
                        DIGEST,
                        LATER));
    }

    @Test
    void exactExpiryClosesAcceptanceAndRevocationAndAllowsExpiry() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue(Optional.empty(), BuiltInTeamRole.MEMBER);

        assertFalse(invitation.isPendingAt(EXPIRY));
        assertThrows(
                DomainValidationException.class,
                () -> invitation.accept(
                        fixture.account,
                        fixture.binding,
                        fixture.userPrincipal,
                        fixture.team,
                        fixture.member,
                        DIGEST,
                        EXPIRY));
        assertThrows(DomainValidationException.class, () -> invitation.revoke(EXPIRY));
        TeamInvitation expired = invitation.expire(EXPIRY);
        assertEquals(TeamInvitationStatus.EXPIRED, expired.status());
        assertEquals(Optional.of(EXPIRY), expired.resolvedAt());
    }

    @Test
    void revocationIsTerminalAndCarriesNoAcceptanceResult() {
        Fixture fixture = new Fixture();
        TeamInvitation revoked = fixture.issue(Optional.empty(), BuiltInTeamRole.AUDITOR)
                .revoke(LATER);

        assertEquals(TeamInvitationStatus.REVOKED, revoked.status());
        assertTrue(revoked.acceptedByAccountId().isEmpty());
        assertTrue(revoked.acceptedMemberId().isEmpty());
        assertEquals(Optional.of(LATER), revoked.resolvedAt());
        assertThrows(InvalidStateTransitionException.class, () -> revoked.revoke(LATER));
        assertThrows(InvalidStateTransitionException.class, () -> revoked.expire(EXPIRY));
    }

    @Test
    void reconstitutionRejectsImpossibleTerminalShapesAndTimelines() {
        Fixture fixture = new Fixture();
        TeamInvitation pending = fixture.issue(Optional.empty(), BuiltInTeamRole.MEMBER);

        assertThrows(
                DomainValidationException.class,
                () -> TeamInvitation.reconstitute(
                        pending.id(),
                        pending.scope(),
                        pending.invitedByPrincipalId(),
                        pending.targetEmail(),
                        pending.targetRole(),
                        pending.tokenDigest(),
                        pending.expiresAt(),
                        TeamInvitationStatus.ACCEPTED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(LATER),
                        1,
                        new LifecycleMetadata(NOW, LATER)));
        assertThrows(
                DomainValidationException.class,
                () -> TeamInvitation.reconstitute(
                        pending.id(),
                        pending.scope(),
                        pending.invitedByPrincipalId(),
                        pending.targetEmail(),
                        pending.targetRole(),
                        pending.tokenDigest(),
                        pending.expiresAt(),
                        TeamInvitationStatus.EXPIRED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(LATER),
                        1,
                        new LifecycleMetadata(NOW, LATER)));
    }

    @Test
    void reconstitutionRequiresTheSingleTransitionVersionShape() {
        Fixture fixture = new Fixture();
        TeamInvitation pending = fixture.issue(Optional.empty(), BuiltInTeamRole.MEMBER);
        assertThrows(
                DomainValidationException.class,
                () -> TeamInvitation.reconstitute(
                        pending.id(),
                        pending.scope(),
                        pending.invitedByPrincipalId(),
                        pending.targetEmail(),
                        pending.targetRole(),
                        pending.tokenDigest(),
                        pending.expiresAt(),
                        TeamInvitationStatus.PENDING,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        -1,
                        LifecycleMetadata.createdAt(NOW)));
        assertThrows(
                DomainValidationException.class,
                () -> TeamInvitation.reconstitute(
                        pending.id(),
                        pending.scope(),
                        pending.invitedByPrincipalId(),
                        pending.targetEmail(),
                        pending.targetRole(),
                        pending.tokenDigest(),
                        pending.expiresAt(),
                        TeamInvitationStatus.PENDING,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        LifecycleMetadata.createdAt(NOW)));
        assertThrows(
                DomainValidationException.class,
                () -> TeamInvitation.reconstitute(
                        pending.id(),
                        pending.scope(),
                        pending.invitedByPrincipalId(),
                        pending.targetEmail(),
                        pending.targetRole(),
                        pending.tokenDigest(),
                        pending.expiresAt(),
                        TeamInvitationStatus.REVOKED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(LATER),
                        2,
                        new LifecycleMetadata(NOW, LATER)));
    }

    @Test
    void invitationConflictContainsNoDigestEmailOrIdentityDetails() {
        TeamInvitationConflictException conflict = new TeamInvitationConflictException();

        assertEquals(DomainErrorCode.TEAM_INVITATION_CONFLICT, conflict.error().code());
        assertTrue(conflict.error().details().isEmpty());
        assertFalse(conflict.getMessage().contains("digest"));
        assertFalse(conflict.getMessage().contains("email"));
        assertFalse(conflict.getMessage().contains("account"));
    }

    @Test
    void invitationIdsAreCanonicalAndUnique() {
        TeamInvitationId first = TeamInvitationId.generate();
        TeamInvitationId second = TeamInvitationId.generate();

        assertNotEquals(first, second);
        assertEquals(first, TeamInvitationId.from(first.toString()));
    }

    private static final class Fixture {
        private final OrganizationId organizationId = OrganizationId.generate();
        private final Principal inviter = user(organizationId);
        private final Team team = team(organizationId, inviter.id());
        private final UserAccount account = account("invitee", "invitee@example.com");
        private final Principal userPrincipal = user(organizationId);
        private final AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organizationId,
                userPrincipal,
                NOW);
        private final TeamMember member = team.acceptInvitedMember(
                TeamMemberId.generate(), userPrincipal, inviter.id(), NOW);

        private TeamInvitation issue(
                Optional<NormalizedEmail> targetEmail, BuiltInTeamRole targetRole) {
            return TeamInvitation.issue(
                    TeamInvitationId.generate(),
                    team,
                    inviter,
                    targetEmail,
                    targetRole,
                    DIGEST,
                    EXPIRY,
                    NOW);
        }
    }

    private static Team team(OrganizationId organizationId, PrincipalId actor) {
        return Team.create(
                TeamId.generate(),
                organizationId,
                "Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                actor,
                NOW);
    }

    private static UserAccount account(String username, String email) {
        return UserAccount.register(UserAccountId.generate(), username, email, username, NOW);
    }

    private static UserAccount inactiveAccount(UserAccount source, AccountStatus status) {
        return UserAccount.reconstitute(
                source.id(),
                new Username(source.username().displayValue()),
                source.email(),
                source.normalizedEmail(),
                source.displayName(),
                status,
                PlatformRole.USER,
                SecurityVersion.initial(),
                source.version(),
                source.lifecycle());
    }

    private static Principal user(OrganizationId organizationId) {
        return principal(
                PrincipalId.generate(),
                organizationId,
                PrincipalType.USER,
                PrincipalStatus.ACTIVE,
                false);
    }

    private static Principal principal(
            PrincipalId id,
            OrganizationId organizationId,
            PrincipalType type,
            PrincipalStatus status,
            boolean teamScope) {
        return Principal.reconstitute(
                id,
                teamScope
                        ? PrincipalScope.team(organizationId, TeamId.generate())
                        : PrincipalScope.organization(organizationId),
                type,
                Optional.empty(),
                "Principal",
                Optional.empty(),
                teamScope ? PrincipalVisibility.TEAM : PrincipalVisibility.ORGANIZATION,
                status,
                0,
                LifecycleMetadata.createdAt(NOW));
    }

    private static InvitationTokenDigest digest(int seed) {
        byte[] value = new byte[InvitationTokenDigest.BYTE_LENGTH];
        value[0] = (byte) seed;
        return InvitationTokenDigest.fromBytes(value);
    }
}
