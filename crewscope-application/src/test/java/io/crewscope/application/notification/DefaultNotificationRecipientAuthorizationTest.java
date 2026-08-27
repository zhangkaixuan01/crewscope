package io.crewscope.application.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultNotificationRecipientAuthorizationTest {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000681");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000682");
    private static final TeamMemberId MEMBER_ID =
            TeamMemberId.from("00000000-0000-0000-0000-000000000683");
    private static final PrincipalId PRINCIPAL_ID =
            PrincipalId.from("00000000-0000-0000-0000-000000000684");

    private TeamMemberRepository members;
    private TeamMember member;
    private Principal actor;
    private DefaultNotificationRecipientAuthorization authorization;

    @BeforeEach
    void setUp() {
        members = mock(TeamMemberRepository.class);
        member = mock(TeamMember.class);
        actor = mock(Principal.class);
        authorization = new DefaultNotificationRecipientAuthorization(members);
        when(members.findById(ORGANIZATION_ID, MEMBER_ID)).thenReturn(Optional.of(member));
        when(member.scope()).thenReturn(new TeamScope(ORGANIZATION_ID, TEAM_ID));
        when(member.userPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(member.canParticipate()).thenReturn(true);
        when(actor.canAct()).thenReturn(true);
        when(actor.type()).thenReturn(PrincipalType.USER);
        when(actor.id()).thenReturn(PRINCIPAL_ID);
        when(actor.scope()).thenReturn(PrincipalScope.team(ORGANIZATION_ID, TEAM_ID));
    }

    @Test
    void acceptsTheCurrentActiveRecipient() {
        assertDoesNotThrow(() -> authorization.requireActiveRecipient(
                ORGANIZATION_ID, MEMBER_ID, actor));
    }

    @Test
    void rejectsWrongPrincipalTeamAndInactiveMembership() {
        when(member.userPrincipalId()).thenReturn(PrincipalId.generate());
        assertDenied();

        when(member.userPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(actor.scope()).thenReturn(PrincipalScope.organization(ORGANIZATION_ID));
        assertDenied();

        when(actor.scope()).thenReturn(PrincipalScope.team(ORGANIZATION_ID, TEAM_ID));
        when(member.canParticipate()).thenReturn(false);
        assertDenied();
    }

    private void assertDenied() {
        assertThrows(PolicyDeniedException.class, () -> authorization.requireActiveRecipient(
                ORGANIZATION_ID, MEMBER_ID, actor));
    }
}
