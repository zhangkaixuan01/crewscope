package io.crewscope.domain.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentProfileTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T19:00:00Z");

    @Test
    void createsActiveDefaultPersonalProfileForMemberAndWorkspace() {
        Principal owner = activeUser("Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        AgentProfile profile = team.ownerPersonalAgent().agentProfile();

        assertEquals(AgentProfileType.PERSONAL, profile.type());
        assertTrue(profile.isActiveDefaultPersonal());
        assertEquals(team.ownerMember().id(), profile.ownerMemberId().orElseThrow());
        assertEquals(team.defaultWorkspace().id(), profile.workspaceId());
        assertEquals(owner.id(), profile.audit().createdBy().orElseThrow());
    }

    @Test
    void rejectsWorkspaceOutsideTheOwnerMembership() {
        Principal firstOwner = activeUser("First");
        Principal secondOwner = activeUser("Second");
        TeamInitialization first = TeamInitialization.create(firstOwner, "First", CREATED_AT);
        TeamInitialization second = TeamInitialization.create(secondOwner, "Second", CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> AgentProfile.createDefaultPersonal(
                        AgentProfileId.generate(),
                        second.defaultWorkspace(),
                        first.ownerMember(),
                        firstOwner,
                        first.ownerPersonalAgent().agentPrincipal(),
                        CREATED_AT));

        assertEquals("agentProfile.workspaceId", failure.error().details().get("field"));
    }

    @Test
    void rejectsAgentOwnedByAnotherUser() {
        Principal owner = activeUser("Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        Principal anotherAgent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(ORGANIZATION_ID, team.team().id()),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(PrincipalId.generate()),
                "Other Agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> AgentProfile.createDefaultPersonal(
                        AgentProfileId.generate(),
                        team.defaultWorkspace(),
                        team.ownerMember(),
                        owner,
                        anotherAgent,
                        CREATED_AT));

        assertEquals("agentProfile.agentPrincipalId", failure.error().details().get("field"));
    }

    @Test
    void supportsDisableReactivateAndTerminalArchive() {
        Principal owner = activeUser("Owner");
        AgentProfile active = TeamInitialization.create(owner, "Platform", CREATED_AT)
                .ownerPersonalAgent()
                .agentProfile();
        UtcTimestamp disabledAt = UtcTimestamp.parse("2026-08-07T19:01:00Z");
        UtcTimestamp activatedAt = UtcTimestamp.parse("2026-08-07T19:02:00Z");
        UtcTimestamp archivedAt = UtcTimestamp.parse("2026-08-07T19:03:00Z");

        AgentProfile archived = active.disable(owner.id(), disabledAt)
                .activate(owner.id(), activatedAt)
                .archive(owner.id(), archivedAt);

        assertEquals(AgentProfileStatus.ARCHIVED, archived.status());
        assertEquals(3, archived.version());
        assertEquals(archivedAt, archived.audit().updatedAt());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.activate(owner.id(), UtcTimestamp.parse("2026-08-07T19:04:00Z")));
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
