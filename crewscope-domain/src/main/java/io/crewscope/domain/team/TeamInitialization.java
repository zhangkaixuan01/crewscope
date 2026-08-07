package io.crewscope.domain.team;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Complete Team foundation committed atomically before Provider, Conversation and WorkItem
 * features use the Team.
 */
public record TeamInitialization(
        Team team,
        Workspace defaultWorkspace,
        TeamMember ownerMember,
        List<TeamRole> builtInRoles,
        MemberRole ownerRole,
        PersonalAgentInitialization ownerPersonalAgent) {

    public TeamInitialization {
        team = Objects.requireNonNull(team, "team");
        defaultWorkspace = Objects.requireNonNull(defaultWorkspace, "defaultWorkspace");
        ownerMember = Objects.requireNonNull(ownerMember, "ownerMember");
        builtInRoles = List.copyOf(Objects.requireNonNull(builtInRoles, "builtInRoles"));
        ownerRole = Objects.requireNonNull(ownerRole, "ownerRole");
        ownerPersonalAgent = Objects.requireNonNull(ownerPersonalAgent, "ownerPersonalAgent");
        validate(
                team,
                defaultWorkspace,
                ownerMember,
                builtInRoles,
                ownerRole,
                ownerPersonalAgent);
    }

    /** Builds the Team, its owner, default Workspace, five roles and unique owner grant. */
    public static TeamInitialization create(
            Principal creator, String teamName, UtcTimestamp occurredAt) {
        Principal requiredCreator = Objects.requireNonNull(creator, "creator");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        TeamId teamId = TeamId.generate();
        TeamMemberId ownerMemberId = TeamMemberId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        Team team = Team.create(
                teamId,
                requiredCreator.scope().organizationId(),
                teamName,
                ownerMemberId,
                workspaceId,
                requiredCreator.id(),
                requiredTime);
        TeamMember ownerMember = team.joinMember(
                ownerMemberId,
                requiredCreator,
                TeamJoinMethod.BOOTSTRAP,
                requiredTime);
        Workspace workspace = Workspace.createTeam(
                workspaceId,
                team,
                requiredCreator,
                team.name(),
                requiredTime);
        List<TeamRole> roles = Arrays.stream(BuiltInTeamRole.values())
                .map(definition -> TeamRole.createBuiltIn(
                        TeamRoleId.generate(), team.scope(), definition, requiredTime))
                .toList();
        TeamRole teamOwnerRole = roles.stream()
                .filter(role -> role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER))
                .findFirst()
                .orElseThrow();
        MemberRole ownerRole = MemberRole.grantOwner(
                MemberRoleId.generate(),
                team,
                ownerMember,
                teamOwnerRole,
                requiredCreator.id(),
                requiredTime);
        PersonalAgentInitialization personalAgent = PersonalAgentInitialization.createDefault(
                ownerMember, workspace, requiredCreator, requiredTime);
        return new TeamInitialization(
                team, workspace, ownerMember, roles, ownerRole, personalAgent);
    }

    private static void validate(
            Team team,
            Workspace workspace,
            TeamMember ownerMember,
            List<TeamRole> roles,
            MemberRole ownerRole,
            PersonalAgentInitialization ownerPersonalAgent) {
        if (!team.isActive()
                || !team.scope().equals(ownerMember.scope())
                || !team.isOwner(ownerMember.id())
                || !ownerMember.canParticipate()) {
            throw new DomainValidationException(
                    "teamInitialization.ownerMember", "must be the active owner of the Team");
        }
        if (workspace.type() != WorkspaceType.TEAM
                || workspace.status() != WorkspaceStatus.ACTIVE
                || !workspace.id().equals(team.defaultWorkspaceId())
                || workspace.scope().teamId().filter(team.id()::equals).isEmpty()
                || !workspace.scope().organizationId().equals(team.organizationId())
                || workspace.ownerPrincipalId()
                        .filter(ownerMember.userPrincipalId()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "teamInitialization.defaultWorkspace",
                    "must be the default TEAM Workspace of the Team");
        }
        Set<BuiltInTeamRole> definitions = roles.stream()
                .map(TeamInitialization::definitionOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(BuiltInTeamRole.class)));
        long distinctRoleIds = roles.stream().map(TeamRole::id).distinct().count();
        if (roles.size() != BuiltInTeamRole.values().length
                || definitions.size() != BuiltInTeamRole.values().length
                || distinctRoleIds != BuiltInTeamRole.values().length
                || roles.stream().anyMatch(role ->
                        !team.scope().equals(role.scope()) || !role.isGrantable())) {
            throw new DomainValidationException(
                    "teamInitialization.builtInRoles",
                    "must contain each built-in TeamRole exactly once in this Team");
        }
        TeamRole teamOwnerRole = roles.stream()
                .filter(role -> role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER))
                .findFirst()
                .orElseThrow();
        if (!ownerRole.teamScope().equals(team.scope())
                || !ownerRole.teamMemberId().equals(ownerMember.id())
                || !ownerRole.teamRoleId().equals(teamOwnerRole.id())
                || !ownerRole.roleScope().equals(RoleScope.team())
                || !ownerRole.grantedByPrincipalId().equals(ownerMember.userPrincipalId())
                || ownerRole.expiresAt().isPresent()
                || ownerRole.status() != MemberRoleStatus.ACTIVE) {
            throw new DomainValidationException(
                    "teamInitialization.ownerRole",
                    "must be the active TEAM_OWNER grant for the Team owner");
        }
        ownerPersonalAgent.requireDefaultFor(ownerMember, workspace);
    }

    private static BuiltInTeamRole definitionOf(TeamRole role) {
        return Arrays.stream(BuiltInTeamRole.values())
                .filter(role::isBuiltIn)
                .findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "teamInitialization.builtInRoles",
                        "must contain only product-owned TeamRoles"));
    }
}
