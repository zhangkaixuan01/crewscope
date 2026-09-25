package io.crewscope.domain.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ADR-038 §2: the authorization dimension moves on every participation or authority change,
 * while presence recording (recordActivity) leaves it untouched.
 */
class TeamMemberAuthorizationVersionTest {

    private static final TeamScope SCOPE =
            new TeamScope(OrganizationId.generate(), TeamId.generate());
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-09-09T05:00:00Z");

    @Test
    void trustedFactoriesStartTheAuthorizationDimensionAtOne() {
        TeamMember joined = TeamMember.join(
                TeamMemberId.generate(), SCOPE, activeUser(), TeamJoinMethod.BOOTSTRAP, CREATED_AT);
        TeamMember invited = TeamMember.invite(
                TeamMemberId.generate(), SCOPE, activeUser(), PrincipalId.generate(), CREATED_AT);

        assertEquals(1, joined.authorizationVersion());
        assertEquals(0, joined.version());
        assertEquals(1, invited.authorizationVersion());
        assertEquals(0, invited.version());
    }

    @Test
    void participationTransitionsAdvanceBothDimensions() {
        UtcTimestamp later = UtcTimestamp.parse("2026-09-09T05:10:00Z");
        Principal user = activeUser();
        TeamMember joined = TeamMember.join(
                TeamMemberId.generate(), SCOPE, user, TeamJoinMethod.BOOTSTRAP, CREATED_AT);

        TeamMember suspended = joined.suspend(later);
        TeamMember activated = suspended.activate(user, later);
        TeamMember left = activated.leave(later);
        TeamMember rejoined = left.activate(user, later);
        TeamMember removed = rejoined.remove(later);

        assertEquals(2, suspended.authorizationVersion());
        assertEquals(3, activated.authorizationVersion());
        assertEquals(4, left.authorizationVersion());
        assertEquals(5, rejoined.authorizationVersion());
        assertEquals(6, removed.authorizationVersion());
        assertEquals(5, removed.version());
    }

    @Test
    void presenceRecordingNeverAdvancesTheAuthorizationDimension() {
        TeamMember member = TeamMember.join(
                TeamMemberId.generate(), SCOPE, activeUser(), TeamJoinMethod.BOOTSTRAP, CREATED_AT);
        TeamMember seen = member
                .recordActivity(UtcTimestamp.parse("2026-09-09T05:01:00Z"))
                .recordActivity(UtcTimestamp.parse("2026-09-09T05:02:00Z"));

        assertEquals(2, seen.version());
        assertEquals(1, seen.authorizationVersion());
    }

    @Test
    void markAuthorizationChangedMovesBothDimensionsWithoutTouchingState() {
        UtcTimestamp changedAt = UtcTimestamp.parse("2026-09-09T05:05:00Z");
        TeamMember member = TeamMember.join(
                TeamMemberId.generate(), SCOPE, activeUser(), TeamJoinMethod.BOOTSTRAP, CREATED_AT);
        TeamMember marked = member
                .markAuthorizationChanged(changedAt)
                .markAuthorizationChanged(changedAt);

        assertEquals(TeamMemberStatus.ACTIVE, marked.status());
        assertEquals(3, marked.authorizationVersion());
        assertEquals(2, marked.version());
    }

    @Test
    void reconstitutePreservesTheDimensionAndRejectsNonPositiveValues() {
        TeamMember committed = TeamMember.join(
                TeamMemberId.generate(), SCOPE, activeUser(), TeamJoinMethod.BOOTSTRAP, CREATED_AT);
        TeamMember loaded = TeamMember.reconstitute(
                committed.id(),
                SCOPE,
                committed.userPrincipalId(),
                committed.status(),
                committed.joinMethod(),
                committed.invitedByPrincipalId(),
                committed.joinedAt(),
                committed.lastActiveAt(),
                committed.version(),
                7,
                LifecycleMetadata.createdAt(CREATED_AT));

        assertEquals(7, loaded.authorizationVersion());

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> TeamMember.reconstitute(
                        committed.id(),
                        SCOPE,
                        committed.userPrincipalId(),
                        committed.status(),
                        committed.joinMethod(),
                        committed.invitedByPrincipalId(),
                        committed.joinedAt(),
                        committed.lastActiveAt(),
                        committed.version(),
                        0,
                        LifecycleMetadata.createdAt(CREATED_AT)));
        assertEquals("teamMember.authorizationVersion", failure.error().details().get("field"));
    }

    private static Principal activeUser() {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(SCOPE.organizationId()),
                PrincipalType.USER,
                Optional.empty(),
                "User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
