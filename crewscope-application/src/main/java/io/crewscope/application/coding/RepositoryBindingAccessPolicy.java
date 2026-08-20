package io.crewscope.application.coding;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Enforces project visibility and Team administrator authority for repository management. */
public final class RepositoryBindingAccessPolicy {

    private final WorkItemAccessPolicy workItemAccessPolicy;
    private final WorkProjectRepository workProjectRepository;
    private final TeamRoleRepository teamRoleRepository;
    private final MemberRoleRepository memberRoleRepository;

    public RepositoryBindingAccessPolicy(
            WorkItemAccessPolicy workItemAccessPolicy,
            WorkProjectRepository workProjectRepository,
            TeamRoleRepository teamRoleRepository,
            MemberRoleRepository memberRoleRepository) {
        this.workItemAccessPolicy =
                Objects.requireNonNull(workItemAccessPolicy, "workItemAccessPolicy");
        this.workProjectRepository =
                Objects.requireNonNull(workProjectRepository, "workProjectRepository");
        this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
        this.memberRoleRepository =
                Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
    }

    /** Returns the exact visible WorkProject for read-only RepositoryBinding operations. */
    public WorkProject requireVisibleProject(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        requireActiveOrganizationUser(trusted, organizationId);
        if (trusted.platformAdministrator()) {
            return workProjectRepository
                    .findById(organizationId, projectId)
                    .filter(project -> project.scope().teamId().equals(teamId))
                    .orElseThrow(() -> new AggregateNotFoundException("WorkProject", projectId));
        }
        return workItemAccessPolicy.requireVisibleProject(
                trusted, organizationId, teamId, projectId);
    }

    /** Restricts mutation and Preflight to platform administrators or built-in Team administrators. */
    public WorkProject requireAdministrator(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            UtcTimestamp occurredAt) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        WorkProject project = requireVisibleProject(trusted, organizationId, teamId, projectId);
        if (trusted.platformAdministrator()) {
            return project;
        }
        var member = workItemAccessPolicy.requireVisibleTeamMember(
                trusted, organizationId, teamId);
        Map<TeamRoleId, TeamRole> roles = teamRoleRepository
                .findByTeam(organizationId, teamId)
                .stream()
                .collect(Collectors.toMap(TeamRole::id, role -> role));
        boolean administrator = memberRoleRepository
                .findByMember(organizationId, member.id())
                .stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(Objects.requireNonNull(occurredAt, "occurredAt")))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .map(grant -> roles.get(grant.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(role -> role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER)
                        || role.isBuiltIn(BuiltInTeamRole.TEAM_ADMIN));
        if (!administrator) {
            throw new PolicyDeniedException("manage repositories in this Team");
        }
        return project;
    }

    private static Principal requireActiveOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        Principal actor = context.actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }
}
