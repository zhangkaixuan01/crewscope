package io.crewscope.domain.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamInitializationTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T17:00:00Z");

    @Test
    void createsACompleteTeamFoundationWithExactlyOneOwner() {
        Principal creator = activeUser();

        TeamInitialization initialization =
                TeamInitialization.create(creator, "  Agent Platform  ", CREATED_AT);

        assertEquals("Agent Platform", initialization.team().name());
        assertEquals(
                initialization.ownerMember().id(),
                initialization.team().ownerMemberId());
        assertEquals(
                initialization.defaultWorkspace().id(),
                initialization.team().defaultWorkspaceId());
        assertEquals(WorkspaceType.TEAM, initialization.defaultWorkspace().type());
        assertEquals(creator.id(), initialization.ownerMember().userPrincipalId());
        assertEquals(BuiltInTeamRole.values().length, initialization.builtInRoles().size());
        assertEquals(
                initialization.ownerMember().id(),
                initialization.ownerRole().teamMemberId());
        assertTrue(initialization.ownerRole().isEffectiveAt(CREATED_AT));
        assertEquals(
                PrincipalType.PERSONAL_AGENT,
                initialization.ownerPersonalAgent().agentPrincipal().type());
        assertEquals(
                AgentProfileType.PERSONAL,
                initialization.ownerPersonalAgent().agentProfile().type());
        assertEquals(
                initialization.ownerMember().id(),
                initialization.ownerPersonalAgent()
                        .agentProfile()
                        .ownerMemberId()
                        .orElseThrow());
        assertEquals(
                1,
                initialization.builtInRoles().stream()
                        .filter(role -> role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER))
                        .count());
    }

    @Test
    void rejectsAgentAndDisabledUserAsTeamCreator() {
        Principal agent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(PrincipalId.generate()),
                "Agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                CREATED_AT);
        Principal disabled = activeUser().transitionTo(
                PrincipalStatus.DISABLED,
                UtcTimestamp.parse("2026-08-07T17:01:00Z"));

        assertThrows(
                DomainValidationException.class,
                () -> TeamInitialization.create(agent, "Agent Team", CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> TeamInitialization.create(
                        disabled,
                        "Disabled Team",
                        UtcTimestamp.parse("2026-08-07T17:02:00Z")));
    }

    @Test
    void initializationRejectsMissingOrDuplicateBuiltInRoleDefinitions() {
        TeamInitialization valid = TeamInitialization.create(activeUser(), "Team", CREATED_AT);
        List<TeamRole> duplicated = new ArrayList<>(valid.builtInRoles());
        duplicated.set(duplicated.size() - 1, duplicated.get(0));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> new TeamInitialization(
                        valid.team(),
                        valid.defaultWorkspace(),
                        valid.ownerMember(),
                        duplicated,
                        valid.ownerRole(),
                        valid.ownerPersonalAgent()));

        assertEquals(
                "teamInitialization.builtInRoles",
                failure.error().details().get("field"));
    }

    @Test
    void teamOwnerGrantCannotBypassTheOwnershipFlow() {
        TeamInitialization initialization = TeamInitialization.create(
                activeUser(), "Team", CREATED_AT);
        TeamRole ownerRole = initialization.builtInRoles().stream()
                .filter(role -> role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER))
                .findFirst()
                .orElseThrow();

        DomainValidationException genericGrant = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.grant(
                        MemberRoleId.generate(),
                        initialization.ownerMember(),
                        ownerRole,
                        RoleScope.team(),
                        initialization.ownerMember().userPrincipalId(),
                        CREATED_AT,
                        CREATED_AT,
                        Optional.empty()));

        TeamMember regularMember = initialization.team().joinMember(
                TeamMemberId.generate(), activeUser(), TeamJoinMethod.OIDC, CREATED_AT);
        DomainValidationException nonOwner = assertThrows(
                DomainValidationException.class,
                () -> MemberRole.grantOwner(
                        MemberRoleId.generate(),
                        initialization.team(),
                        regularMember,
                        ownerRole,
                        initialization.ownerMember().userPrincipalId(),
                        CREATED_AT));

        assertEquals("memberRole.teamRoleId", genericGrant.error().details().get("field"));
        assertEquals("memberRole.teamMemberId", nonOwner.error().details().get("field"));
    }

    private static Principal activeUser() {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Creator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
