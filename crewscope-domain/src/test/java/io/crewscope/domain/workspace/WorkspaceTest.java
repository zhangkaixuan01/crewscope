package io.crewscope.domain.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkspaceTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId OWNER_ID = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T16:00:00Z");

    @Test
    void createsTheTeamDefaultWorkspaceWithTrustedOwnerAndScope() {
        Team team = team();

        Principal owner = owner();
        Workspace workspace = Workspace.createTeam(
                team.defaultWorkspaceId(), team, owner, "  Shared Work  ", CREATED_AT);

        assertEquals(WorkspaceType.TEAM, workspace.type());
        assertEquals(team.id(), workspace.scope().teamId().orElseThrow());
        assertEquals("Shared Work", workspace.name());
        assertEquals(OWNER_ID, workspace.ownerPrincipalId().orElseThrow());
        assertEquals(OWNER_ID, workspace.audit().createdBy().orElseThrow());
    }

    @Test
    void createsAPersonalWorkspaceWithoutATeamScope() {
        Workspace workspace = Workspace.createPersonal(
                WorkspaceId.generate(), owner(), "Personal", CREATED_AT);

        assertEquals(WorkspaceType.PERSONAL, workspace.type());
        assertTrue(workspace.scope().teamId().isEmpty());
    }

    @Test
    void rejectsWorkspaceTypeAndScopeShapeMismatch() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> Workspace.reconstitute(
                        WorkspaceId.generate(),
                        WorkspaceScope.team(ORGANIZATION_ID, TeamId.generate()),
                        WorkspaceType.PERSONAL,
                        Optional.of(OWNER_ID),
                        "Invalid",
                        WorkspaceStatus.ACTIVE,
                        0,
                        AuditMetadata.createdBy(OWNER_ID, CREATED_AT)));

        assertEquals("workspace.scope", failure.error().details().get("field"));
    }

    @Test
    void newWorkspaceRequiresAnActiveUserOwner() {
        Principal disabled = owner().transitionTo(
                PrincipalStatus.DISABLED,
                UtcTimestamp.parse("2026-08-07T16:01:00Z"));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> Workspace.createPersonal(
                        WorkspaceId.generate(), disabled, "Personal", CREATED_AT));

        assertEquals("workspace.ownerPrincipalId", failure.error().details().get("field"));
    }

    @Test
    void archivesWorkspaceAsATerminalLifecycleChange() {
        Workspace workspace = Workspace.createPersonal(
                WorkspaceId.generate(), owner(), "Personal", CREATED_AT);

        Workspace archived = workspace.archive(
                OWNER_ID, UtcTimestamp.parse("2026-08-07T16:01:00Z"));

        assertEquals(WorkspaceStatus.ARCHIVED, archived.status());
        assertEquals(1, archived.version());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.archive(
                        OWNER_ID, UtcTimestamp.parse("2026-08-07T16:02:00Z")));
    }

    private static Team team() {
        return Team.create(
                TeamId.generate(),
                ORGANIZATION_ID,
                "Platform Crew",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                OWNER_ID,
                CREATED_AT);
    }

    private static Principal owner() {
        return Principal.create(
                OWNER_ID,
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
