package io.crewscope.domain.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamRoleTest {

    private static final TeamScope SCOPE =
            new TeamScope(OrganizationId.generate(), TeamId.generate());
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T13:00:00Z");

    @Test
    void materializesAllFiveProductOwnedRoles() {
        assertEquals(5, BuiltInTeamRole.values().length);
        for (BuiltInTeamRole definition : BuiltInTeamRole.values()) {
            TeamRole role = TeamRole.createBuiltIn(
                    TeamRoleId.generate(), SCOPE, definition, CREATED_AT);

            assertTrue(role.builtIn());
            assertEquals(definition.name(), role.key().value());
            assertEquals(RoleScopeType.TEAM, role.scopeType());
            assertFalse(role.permissions().isEmpty());
            assertTrue(role.isGrantable());
        }
    }

    @Test
    void teamOwnerContainsEveryManagementPermission() {
        TeamRole owner = TeamRole.createBuiltIn(
                TeamRoleId.generate(), SCOPE, BuiltInTeamRole.TEAM_OWNER, CREATED_AT);

        assertEquals(EnumSet.allOf(TeamPermission.class), owner.permissions());
    }

    @Test
    void createsAWorkProjectScopedCustomRole() {
        TeamRole role = TeamRole.createCustom(
                TeamRoleId.generate(),
                SCOPE,
                new TeamRoleKey(" release_manager "),
                " Release Manager ",
                Optional.of("  Coordinates releases  "),
                EnumSet.of(TeamPermission.WORK_PARTICIPATE, TeamPermission.TEAM_OBSERVE),
                RoleScopeType.WORK_PROJECT,
                CREATED_AT);

        assertEquals("RELEASE_MANAGER", role.key().value());
        assertEquals("Release Manager", role.name());
        assertEquals("Coordinates releases", role.description().orElseThrow());
        assertEquals(RoleScopeType.WORK_PROJECT, role.scopeType());
        assertFalse(role.builtIn());
    }

    @Test
    void rejectsInvalidRoleKeysWithStableFieldDetails() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class, () -> new TeamRoleKey("invalid-key"));

        assertEquals("teamRole.key", failure.error().details().get("field"));
    }

    @Test
    void reservesProductOwnedKeysForBuiltInRoles() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> TeamRole.createCustom(
                        TeamRoleId.generate(),
                        SCOPE,
                        new TeamRoleKey("TEAM_OWNER"),
                        "Custom Owner",
                        Optional.empty(),
                        EnumSet.of(TeamPermission.TEAM_MANAGE),
                        RoleScopeType.TEAM,
                        CREATED_AT));

        assertEquals("teamRole.key", failure.error().details().get("field"));
    }

    @Test
    void disablesReactivatesAndArchivesRoleDefinitions() {
        TeamRole disabled = TeamRole.createBuiltIn(
                        TeamRoleId.generate(), SCOPE, BuiltInTeamRole.MEMBER, CREATED_AT)
                .transitionTo(
                        TeamRoleStatus.DISABLED,
                        UtcTimestamp.parse("2026-08-07T13:01:00Z"));
        TeamRole active = disabled.transitionTo(
                TeamRoleStatus.ACTIVE,
                UtcTimestamp.parse("2026-08-07T13:02:00Z"));
        TeamRole archived = active.transitionTo(
                TeamRoleStatus.ARCHIVED,
                UtcTimestamp.parse("2026-08-07T13:03:00Z"));

        assertFalse(disabled.isGrantable());
        assertTrue(active.isGrantable());
        assertEquals(3, archived.version());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.transitionTo(
                        TeamRoleStatus.ACTIVE,
                        UtcTimestamp.parse("2026-08-07T13:04:00Z")));
    }
}
