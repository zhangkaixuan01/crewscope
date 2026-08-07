package io.crewscope.domain.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemberRoleTest {

    private static final TeamScope SCOPE =
            new TeamScope(OrganizationId.generate(), TeamId.generate());
    private static final PrincipalId GRANTOR = PrincipalId.generate();
    private static final UtcTimestamp GRANTED_AT = UtcTimestamp.parse("2026-08-07T14:00:00Z");
    private static final UtcTimestamp VALID_FROM = UtcTimestamp.parse("2026-08-07T14:05:00Z");
    private static final UtcTimestamp EXPIRES_AT = UtcTimestamp.parse("2026-08-08T14:05:00Z");

    @Test
    void grantsATeamRoleWithAnInclusiveStartAndExclusiveExpiry() {
        MemberRole grant = MemberRole.grant(
                MemberRoleId.generate(),
                activeMember(SCOPE),
                builtInMemberRole(SCOPE),
                RoleScope.team(),
                GRANTOR,
                GRANTED_AT,
                VALID_FROM,
                Optional.of(EXPIRES_AT));

        assertFalse(grant.isEffectiveAt(UtcTimestamp.parse("2026-08-07T14:04:59Z")));
        assertTrue(grant.isEffectiveAt(VALID_FROM));
        assertFalse(grant.isEffectiveAt(EXPIRES_AT));
        assertEquals(MemberRoleStatus.ACTIVE, grant.status());
    }

    @Test
    void workProjectGrantRequiresMatchingRoleDefinitionScope() {
        TeamRole projectRole = TeamRole.createCustom(
                TeamRoleId.generate(),
                SCOPE,
                new TeamRoleKey("PROJECT_REVIEWER"),
                "Project Reviewer",
                Optional.empty(),
                EnumSet.of(TeamPermission.WORK_PARTICIPATE),
                RoleScopeType.WORK_PROJECT,
                GRANTED_AT);
        RoleScope projectScope = RoleScope.workProject(WorkProjectId.generate());

        MemberRole grant = MemberRole.grant(
                MemberRoleId.generate(),
                activeMember(SCOPE),
                projectRole,
                projectScope,
                GRANTOR,
                GRANTED_AT,
                GRANTED_AT,
                Optional.empty());

        assertEquals(projectScope, grant.roleScope());
    }

    @Test
    void rejectsScopeTypeMismatchAndCrossTeamGrant() {
        DomainValidationException scopeFailure = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.grant(
                        MemberRoleId.generate(),
                        activeMember(SCOPE),
                        builtInMemberRole(SCOPE),
                        RoleScope.workProject(WorkProjectId.generate()),
                        GRANTOR,
                        GRANTED_AT,
                        GRANTED_AT,
                        Optional.empty()));
        TeamScope otherScope = new TeamScope(SCOPE.organizationId(), TeamId.generate());
        DomainValidationException teamFailure = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.grant(
                        MemberRoleId.generate(),
                        activeMember(SCOPE),
                        builtInMemberRole(otherScope),
                        RoleScope.team(),
                        GRANTOR,
                        GRANTED_AT,
                        GRANTED_AT,
                        Optional.empty()));

        assertEquals("memberRole.scope", scopeFailure.error().details().get("field"));
        assertEquals("memberRole.teamScope", teamFailure.error().details().get("field"));
    }

    @Test
    void disabledRoleCannotBeGranted() {
        TeamRole disabledRole = builtInMemberRole(SCOPE).transitionTo(
                TeamRoleStatus.DISABLED,
                UtcTimestamp.parse("2026-08-07T14:01:00Z"));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.grant(
                        MemberRoleId.generate(),
                        activeMember(SCOPE),
                        disabledRole,
                        RoleScope.team(),
                        GRANTOR,
                        UtcTimestamp.parse("2026-08-07T14:02:00Z"),
                        UtcTimestamp.parse("2026-08-07T14:02:00Z"),
                        Optional.empty()));

        assertEquals("memberRole.teamRoleId", failure.error().details().get("field"));
    }

    @Test
    void revocationIsAuditableAndTerminal() {
        MemberRole revoked = activeGrant(Optional.empty()).revoke(
                UtcTimestamp.parse("2026-08-07T15:00:00Z"));

        assertEquals(MemberRoleStatus.REVOKED, revoked.status());
        assertEquals(UtcTimestamp.parse("2026-08-07T15:00:00Z"), revoked.revokedAt().orElseThrow());
        assertEquals(1, revoked.version());
        assertFalse(revoked.isEffectiveAt(UtcTimestamp.parse("2026-08-07T15:01:00Z")));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> revoked.revoke(UtcTimestamp.parse("2026-08-07T15:02:00Z")));
        assertTrue(revoked.isEffectiveAt(UtcTimestamp.parse("2026-08-07T14:30:00Z")));
    }

    @Test
    void timeBoundGrantExpiresOnlyAfterItsDeadline() {
        MemberRole grant = activeGrant(Optional.of(EXPIRES_AT));

        DomainValidationException early = assertThrows(
                DomainValidationException.class,
                () -> grant.expire(UtcTimestamp.parse("2026-08-08T14:04:59Z")));
        MemberRole expired = grant.expire(EXPIRES_AT);

        assertEquals("memberRole.expiresAt", early.error().details().get("field"));
        assertEquals(MemberRoleStatus.EXPIRED, expired.status());
        assertTrue(expired.revokedAt().isEmpty());
        assertFalse(expired.isEffectiveAt(EXPIRES_AT));
    }

    @Test
    void perpetualGrantCannotBeMarkedExpired() {
        MemberRole grant = activeGrant(Optional.empty());

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> grant.expire(UtcTimestamp.parse("2026-08-08T14:05:00Z")));

        assertEquals("memberRole.expiresAt", failure.error().details().get("field"));
    }

    @Test
    void rejectsInvalidValidityWindow() {
        DomainValidationException beforeGrant = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.grant(
                        MemberRoleId.generate(),
                        activeMember(SCOPE),
                        builtInMemberRole(SCOPE),
                        RoleScope.team(),
                        GRANTOR,
                        GRANTED_AT,
                        UtcTimestamp.parse("2026-08-07T13:59:00Z"),
                        Optional.empty()));
        DomainValidationException noWindow = assertThrows(
                DomainValidationException.class,
                () -> activeGrant(Optional.of(VALID_FROM)));

        assertEquals("memberRole.validFrom", beforeGrant.error().details().get("field"));
        assertEquals("memberRole.expiresAt", noWindow.error().details().get("field"));
    }

    @Test
    void rejectsExpiredPersistenceStateWithoutAConfiguredExpiry() {
        MemberRole grant = activeGrant(Optional.empty());

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.reconstitute(
                        grant.id(),
                        grant.teamScope(),
                        grant.teamMemberId(),
                        grant.teamRoleId(),
                        grant.roleScope(),
                        grant.grantedByPrincipalId(),
                        grant.grantedAt(),
                        grant.validFrom(),
                        Optional.empty(),
                        Optional.empty(),
                        MemberRoleStatus.EXPIRED,
                        grant.version(),
                        LifecycleMetadata.createdAt(GRANTED_AT)));

        assertEquals("memberRole.expiresAt", failure.error().details().get("field"));
    }

    @Test
    void rejectsTerminalPersistenceFactsAfterTheRecordedUpdateTime() {
        MemberRole grant = activeGrant(Optional.of(EXPIRES_AT));
        UtcTimestamp beforeTerminalFact = UtcTimestamp.parse("2026-08-08T14:04:59Z");

        DomainValidationException expiredFailure = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.reconstitute(
                        grant.id(),
                        grant.teamScope(),
                        grant.teamMemberId(),
                        grant.teamRoleId(),
                        grant.roleScope(),
                        grant.grantedByPrincipalId(),
                        grant.grantedAt(),
                        grant.validFrom(),
                        grant.expiresAt(),
                        Optional.empty(),
                        MemberRoleStatus.EXPIRED,
                        1,
                        new LifecycleMetadata(GRANTED_AT, beforeTerminalFact)));
        UtcTimestamp revokedAt = UtcTimestamp.parse("2026-08-07T15:00:00Z");
        DomainValidationException revokedFailure = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.reconstitute(
                        grant.id(),
                        grant.teamScope(),
                        grant.teamMemberId(),
                        grant.teamRoleId(),
                        grant.roleScope(),
                        grant.grantedByPrincipalId(),
                        grant.grantedAt(),
                        grant.validFrom(),
                        grant.expiresAt(),
                        Optional.of(revokedAt),
                        MemberRoleStatus.REVOKED,
                        1,
                        new LifecycleMetadata(
                                GRANTED_AT,
                                UtcTimestamp.parse("2026-08-07T14:59:59Z"))));

        assertEquals(
                "memberRole.lifecycle.updatedAt",
                expiredFailure.error().details().get("field"));
        assertEquals(
                "memberRole.lifecycle.updatedAt",
                revokedFailure.error().details().get("field"));
    }

    private static MemberRole activeGrant(Optional<UtcTimestamp> expiresAt) {
        return MemberRole.grant(
                MemberRoleId.generate(),
                activeMember(SCOPE),
                builtInMemberRole(SCOPE),
                RoleScope.team(),
                GRANTOR,
                GRANTED_AT,
                VALID_FROM,
                expiresAt);
    }

    private static TeamRole builtInMemberRole(TeamScope scope) {
        return TeamRole.createBuiltIn(
                TeamRoleId.generate(), scope, BuiltInTeamRole.MEMBER, GRANTED_AT);
    }

    private static TeamMember activeMember(TeamScope scope) {
        Principal user = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(scope.organizationId()),
                PrincipalType.USER,
                Optional.empty(),
                "Member",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                GRANTED_AT);
        return TeamMember.join(
                TeamMemberId.generate(), scope, user, TeamJoinMethod.BOOTSTRAP, GRANTED_AT);
    }
}
