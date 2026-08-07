package io.crewscope.domain.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonalAgentInitializationTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T20:00:00Z");

    @Test
    void createsPrivateTeamScopedPersonalAgentOwnedByTheMemberUser() {
        Principal owner = activeUser("Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        PersonalAgentInitialization personalAgent = team.ownerPersonalAgent();

        assertEquals(PrincipalType.PERSONAL_AGENT, personalAgent.agentPrincipal().type());
        assertEquals(PrincipalVisibility.PRIVATE, personalAgent.agentPrincipal().visibility());
        assertEquals(owner.id(), personalAgent.agentPrincipal().ownerPrincipalId().orElseThrow());
        assertEquals(
                team.team().id(),
                personalAgent.agentPrincipal().scope().teamId().orElseThrow());
        assertEquals(
                personalAgent.agentPrincipal().id(),
                personalAgent.agentProfile().agentPrincipalId());
    }

    @Test
    void derivesStableButTypeSeparatedIdsFromMembership() {
        Principal owner = activeUser("Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        PersonalAgentInitialization retry = PersonalAgentInitialization.createDefault(
                team.ownerMember(),
                team.defaultWorkspace(),
                owner,
                UtcTimestamp.parse("2026-08-07T20:01:00Z"));

        assertEquals(
                team.ownerPersonalAgent().agentPrincipal().id(), retry.agentPrincipal().id());
        assertEquals(team.ownerPersonalAgent().agentProfile().id(), retry.agentProfile().id());
        assertNotEquals(
                retry.agentPrincipal().id().value(), retry.agentProfile().id().value());
    }

    @Test
    void rejectsInactiveMembershipAndOwnerUser() {
        Principal owner = activeUser("Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        UtcTimestamp changedAt = UtcTimestamp.parse("2026-08-07T20:01:00Z");

        assertThrows(
                DomainValidationException.class,
                () -> PersonalAgentInitialization.createDefault(
                        team.ownerMember().suspend(changedAt),
                        team.defaultWorkspace(),
                        owner,
                        changedAt));
        assertThrows(
                DomainValidationException.class,
                () -> PersonalAgentInitialization.createDefault(
                        team.ownerMember(),
                        team.defaultWorkspace(),
                        owner.transitionTo(PrincipalStatus.DISABLED, changedAt),
                        changedAt));
    }

    @Test
    void rejectsMismatchedPrincipalAndProfilePair() {
        Principal firstOwner = activeUser("First");
        Principal secondOwner = activeUser("Second");
        TeamInitialization first = TeamInitialization.create(firstOwner, "First", CREATED_AT);
        TeamInitialization second = TeamInitialization.create(secondOwner, "Second", CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> new PersonalAgentInitialization(
                        first.ownerPersonalAgent().agentPrincipal(),
                        second.ownerPersonalAgent().agentProfile()));

        assertEquals(
                "personalAgentInitialization.agentProfile",
                failure.error().details().get("field"));
    }

    private static Principal activeUser(String displayName) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
