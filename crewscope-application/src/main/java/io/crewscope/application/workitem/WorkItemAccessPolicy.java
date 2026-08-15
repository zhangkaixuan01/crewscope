package io.crewscope.application.workitem;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Resolves trusted membership, complete WorkItem scope and project-scoped permissions. */
public final class WorkItemAccessPolicy {

  private final WorkItemRepository workItemRepository;
  private final WorkProjectRepository projectRepository;
  private final TeamRepository teamRepository;
  private final TeamMembershipQuery membershipQuery;
  private final TeamRoleRepository teamRoleRepository;
  private final MemberRoleRepository memberRoleRepository;

  public WorkItemAccessPolicy(
      WorkItemRepository workItemRepository,
      WorkProjectRepository projectRepository,
      TeamRepository teamRepository,
      TeamMembershipQuery membershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository) {
    this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
    this.memberRoleRepository = Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
  }

  /** Returns a visible project only after validating active USER membership in its Team. */
  public WorkProject requireVisibleProject(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId) {
    Principal actor = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(actor, team);
    return requireProject(organizationId, teamId, projectId);
  }

  /** Requires an active USER membership before exposing Team-scoped read models. */
  public Team requireVisibleTeam(
      TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    Principal actor = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(actor, team);
    return team;
  }

  /** Returns the exact active TeamMember identity used by member-specific visibility policies. */
  public TeamMember requireVisibleTeamMember(
      TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    Principal actor = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    return requireActiveMember(actor, team);
  }

  /** Requires platform authority or an effective Team-wide grant from an active member. */
  public void requireTeamPermission(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      TeamPermission permission,
      UtcTimestamp occurredAt,
      String action) {
    TeamAccessContext trusted = Objects.requireNonNull(context, "context");
    Principal actor = requireAccess(trusted, organizationId);
    Team team = requireTeam(organizationId, teamId);
    if (trusted.platformAdministrator()) {
      return;
    }
    TeamMember member = requireActiveMember(actor, team);
    requireTeamPermission(member, permission, occurredAt, action);
  }

  /** Requires the same effective permission used by the native WorkItem creation command. */
  public WorkProject requireCreatePermission(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      UtcTimestamp occurredAt) {
    Principal actor = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    TeamMember member = requireActiveMember(actor, team);
    WorkProject project = requireProject(organizationId, teamId, projectId);
    requirePermission(
        member,
        TeamPermission.WORK_CREATE,
        projectId,
        Objects.requireNonNull(occurredAt, "occurredAt"),
        "create WorkItems in this WorkProject");
    return project;
  }

  /** Returns one visible WorkItem only when every URL and persisted scope component agrees. */
  public WorkItem requireVisibleWorkItem(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {
    WorkProject project =
        requireVisibleProject(context, organizationId, teamId, projectId);
    return requireWorkItem(organizationId, project, workItemId);
  }

  /** Requires an effective Team or target-WorkProject grant before returning the WorkItem. */
  public WorkItem requirePermission(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      TeamPermission permission,
      UtcTimestamp occurredAt,
      String action) {
    Principal actor = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    TeamMember member = requireActiveMember(actor, team);
    WorkProject project = requireProject(organizationId, teamId, projectId);
    requirePermission(member, permission, projectId, occurredAt, action);
    return requireWorkItem(organizationId, project, workItemId);
  }

  private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
    if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
      throw new DomainValidationException("team.initializationStatus", "must be READY");
    }
    return teamRepository
        .findById(organizationId, teamId)
        .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
  }

  private WorkProject requireProject(
      OrganizationId organizationId, TeamId teamId, WorkProjectId projectId) {
    return projectRepository
        .findById(organizationId, projectId)
        .filter(project -> project.scope().teamId().equals(teamId))
        .orElseThrow(() -> new AggregateNotFoundException("WorkProject", projectId));
  }

  private WorkItem requireWorkItem(
      OrganizationId organizationId, WorkProject project, WorkItemId workItemId) {
    return workItemRepository
        .findById(organizationId, workItemId)
        .filter(item -> item.scope().teamId().equals(project.scope().teamId()))
        .filter(item -> item.scope().workspaceId().equals(project.scope().workspaceId()))
        .filter(item -> item.scope().projectId().equals(project.id()))
        .orElseThrow(() -> new AggregateNotFoundException("WorkItem", workItemId));
  }

  private TeamMember requireActiveMember(Principal actor, Team team) {
    return membershipQuery.findByTeam(team.organizationId(), team.id()).stream()
        .filter(member -> member.userPrincipalId().equals(actor.id()))
        .filter(TeamMember::canParticipate)
        .findFirst()
        .orElseThrow(() -> new PolicyDeniedException("access this Team's WorkItems"));
  }

  private void requirePermission(
      TeamMember member,
      TeamPermission permission,
      WorkProjectId projectId,
      UtcTimestamp occurredAt,
      String action) {
    Map<TeamRoleId, TeamRole> roles =
        teamRoleRepository
            .findByTeam(member.scope().organizationId(), member.scope().teamId())
            .stream()
            .collect(Collectors.toMap(TeamRole::id, role -> role));
    RoleScope projectScope = RoleScope.workProject(projectId);
    boolean allowed =
        memberRoleRepository.findByMember(member.scope().organizationId(), member.id()).stream()
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .filter(grant -> grant.isEffectiveAt(occurredAt))
            .filter(
                grant ->
                    grant.roleScope().equals(RoleScope.team())
                        || grant.roleScope().equals(projectScope))
            .map(grant -> roles.get(grant.teamRoleId()))
            .filter(Objects::nonNull)
            .filter(TeamRole::isGrantable)
            .anyMatch(role -> role.permissions().contains(permission));
    if (!allowed) {
      throw new PolicyDeniedException(action);
    }
  }

  private void requireTeamPermission(
      TeamMember member,
      TeamPermission permission,
      UtcTimestamp occurredAt,
      String action) {
    Map<TeamRoleId, TeamRole> roles =
        teamRoleRepository
            .findByTeam(member.scope().organizationId(), member.scope().teamId())
            .stream()
            .collect(Collectors.toMap(TeamRole::id, role -> role));
    boolean allowed =
        memberRoleRepository.findByMember(member.scope().organizationId(), member.id()).stream()
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .filter(grant -> grant.isEffectiveAt(Objects.requireNonNull(occurredAt, "occurredAt")))
            .filter(grant -> grant.roleScope().equals(RoleScope.team()))
            .map(grant -> roles.get(grant.teamRoleId()))
            .filter(Objects::nonNull)
            .filter(TeamRole::isGrantable)
            .anyMatch(role -> role.permissions().contains(
                Objects.requireNonNull(permission, "permission")));
    if (!allowed) {
      throw new PolicyDeniedException(Objects.requireNonNull(action, "action"));
    }
  }

  private static Principal requireAccess(
      TeamAccessContext context, OrganizationId organizationId) {
    TeamAccessContext trusted = Objects.requireNonNull(context, "context");
    Principal actor = trusted.actor();
    if (actor.type() != PrincipalType.USER
        || !actor.canAct()
        || !actor.scope().organizationId().equals(organizationId)) {
      throw new PolicyDeniedException("act in this Organization");
    }
    return actor;
  }
}
