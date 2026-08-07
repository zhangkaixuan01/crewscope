package io.crewscope.domain.team;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Product-owned Team roles and their default management permissions. */
public enum BuiltInTeamRole {
    TEAM_OWNER(
            "Team Owner",
            EnumSet.allOf(TeamPermission.class)),
    TEAM_ADMIN(
            "Team Admin",
            EnumSet.of(
                    TeamPermission.MEMBER_MANAGE,
                    TeamPermission.ROLE_MANAGE,
                    TeamPermission.WORKSPACE_MANAGE,
                    TeamPermission.PROVIDER_MANAGE,
                    TeamPermission.AGENT_MANAGE,
                    TeamPermission.WORK_PROJECT_MANAGE,
                    TeamPermission.RESPONSIBILITY_MANAGE,
                    TeamPermission.WORK_CREATE,
                    TeamPermission.WORK_PARTICIPATE,
                    TeamPermission.COLLABORATION_REQUEST,
                    TeamPermission.TEAM_OBSERVE,
                    TeamPermission.AUDIT_READ)),
    TEAM_LEAD(
            "Team Lead",
            EnumSet.of(
                    TeamPermission.WORK_PROJECT_MANAGE,
                    TeamPermission.RESPONSIBILITY_MANAGE,
                    TeamPermission.WORK_CREATE,
                    TeamPermission.WORK_PARTICIPATE,
                    TeamPermission.COLLABORATION_REQUEST,
                    TeamPermission.TEAM_OBSERVE)),
    MEMBER(
            "Member",
            EnumSet.of(
                    TeamPermission.WORK_CREATE,
                    TeamPermission.WORK_PARTICIPATE,
                    TeamPermission.COLLABORATION_REQUEST)),
    AUDITOR(
            "Auditor",
            EnumSet.of(
                    TeamPermission.TEAM_OBSERVE,
                    TeamPermission.AUDIT_READ,
                    TeamPermission.GOVERNANCE_EXPORT));

    private final String displayName;
    private final Set<TeamPermission> permissions;

    BuiltInTeamRole(String displayName, Set<TeamPermission> permissions) {
        this.displayName = displayName;
        this.permissions = Set.copyOf(permissions);
    }

    public TeamRoleKey key() {
        return new TeamRoleKey(name());
    }

    public String displayName() {
        return displayName;
    }

    public Set<TeamPermission> permissions() {
        return permissions;
    }

    public static Optional<BuiltInTeamRole> fromKey(TeamRoleKey key) {
        for (BuiltInTeamRole definition : values()) {
            if (definition.name().equals(key.value())) {
                return Optional.of(definition);
            }
        }
        return Optional.empty();
    }
}
