package io.crewscope.application.task;

import io.crewscope.application.coding.ProjectExecutionDefaultsApplicationService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.ProjectExecutionDefaults;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.task.Task;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles the delegation form's server-side facts (M9b-A05): one read joins the responsibility
 * chain, the assignable Agent candidates with their conflicts, the A04 project defaults and the
 * in-flight execution fact, so the form never re-derives truth from several pages.
 */
public final class DelegationContextService {

  private static final int PROFILE_PAGE_SIZE = 200;
  private static final int MAX_CANDIDATES = 600;

  private final WorkItemAccessPolicy accessPolicy;
  private final ResponsibilityQueryService responsibilityQuery;
  private final ResponsibilityAssignmentRepository assignmentRepository;
  private final AgentProfileRepository agentProfileRepository;
  private final PrincipalRepository principalRepository;
  private final TeamMembershipQuery membershipQuery;
  private final ProjectExecutionDefaultsApplicationService defaultsService;
  private final TaskRepository taskRepository;
  private final TaskExecutionRepository executionRepository;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public DelegationContextService(
      WorkItemAccessPolicy accessPolicy,
      ResponsibilityQueryService responsibilityQuery,
      ResponsibilityAssignmentRepository assignmentRepository,
      AgentProfileRepository agentProfileRepository,
      PrincipalRepository principalRepository,
      TeamMembershipQuery membershipQuery,
      ProjectExecutionDefaultsApplicationService defaultsService,
      TaskRepository taskRepository,
      TaskExecutionRepository executionRepository,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.responsibilityQuery = Objects.requireNonNull(responsibilityQuery, "responsibilityQuery");
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    this.agentProfileRepository =
        Objects.requireNonNull(agentProfileRepository, "agentProfileRepository");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.defaultsService = Objects.requireNonNull(defaultsService, "defaultsService");
    this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
    this.executionRepository =
        Objects.requireNonNull(executionRepository, "executionRepository");
    this.transactionExecutor =
        Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  public DelegationContext getContext(
      TeamAccessContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {
    TeamAccessContext trusted = Objects.requireNonNull(context, "context");
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    WorkProjectId requiredProjectId = Objects.requireNonNull(projectId, "projectId");
    WorkItemId requiredWorkItemId = Objects.requireNonNull(workItemId, "workItemId");
    return transactionExecutor.required(() -> contextInTransaction(
        trusted, requiredTeamId, requiredProjectId, requiredWorkItemId));
  }

  private DelegationContext contextInTransaction(
      TeamAccessContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {
    OrganizationId organizationId = context.actor().scope().organizationId();
    WorkItem item = accessPolicy.requireVisibleWorkItem(
        context, organizationId, teamId, projectId, workItemId);
    List<ResponsibilityAssignment> assignments =
        assignmentRepository.findActiveByWorkItem(organizationId, item.id());

    DelegationContext.Permissions permissions = permissions(context, item, assignments);
    boolean activeExecution = activeExecution(organizationId, item);
    DelegationContext.DefaultsSnapshot defaults = defaults(context, organizationId, teamId, projectId);
    return new DelegationContext(
        new DelegationContext.WorkItemLine(
            item.id(), projectId, item.version(), item.title(), item.status()),
        responsibilityLines(context, organizationId, teamId, projectId, item),
        candidates(context, item, assignments),
        defaults,
        activeExecution,
        permissions);
  }

  private DelegationContext.Permissions permissions(
      TeamAccessContext context, WorkItem item, List<ResponsibilityAssignment> assignments) {
    boolean canAssign = accessPolicy.hasPermission(
        context,
        item.scope().organizationId(),
        item.scope().teamId(),
        item.scope().projectId(),
        TeamPermission.RESPONSIBILITY_MANAGE,
        timeProvider.now());
    PrincipalId actor = context.actor().id();
    boolean canDelegate = assignments.stream()
        .filter(ResponsibilityAssignment::isActive)
        .filter(value -> value.role() == ResponsibilityRole.OWNER
            || value.role() == ResponsibilityRole.EXECUTOR)
        .anyMatch(value -> value.actorPrincipalId().equals(actor));
    return new DelegationContext.Permissions(canAssign, canDelegate);
  }

  private boolean activeExecution(OrganizationId organizationId, WorkItem item) {
    List<Task> tasks = taskRepository.findByWorkItem(organizationId, item.id());
    if (tasks.isEmpty()) {
      return false;
    }
    if (tasks.size() > 1) {
      return true;
    }
    return tasks.get(0).currentExecutionId()
        .map(executionId -> executionRepository
            .findById(organizationId, executionId)
            .map(execution -> !execution.status().isTerminal())
            .orElse(false))
        .orElse(false);
  }

  private DelegationContext.DefaultsSnapshot defaults(
      TeamAccessContext context, OrganizationId organizationId, TeamId teamId,
      WorkProjectId projectId) {
    ProjectExecutionDefaults value =
        defaultsService.get(context, organizationId, teamId, projectId);
    return new DelegationContext.DefaultsSnapshot(
        value.version(),
        value.repositoryBindingId(),
        value.repositoryBindingVersion(),
        value.branch(),
        value.buildProfile(),
        value.agentProfileId(),
        value.agentProfileRevision());
  }

  private List<DelegationContext.ResponsibilityLine> responsibilityLines(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItem item) {
    return responsibilityQuery
        .listActive(context, organizationId, teamId, projectId, item.id())
        .stream()
        .map(view -> new DelegationContext.ResponsibilityLine(
            view.assignment().id(),
            view.assignment().version(),
            view.assignment().role(),
            view.assignment().actorPrincipalId(),
            view.assignment().actorType().name(),
            view.actorDisplayName(),
            view.actorAgentProfileId()))
        .toList();
  }

  private List<DelegationContext.AgentCandidate> candidates(
      TeamAccessContext context, WorkItem item, List<ResponsibilityAssignment> assignments) {
    OrganizationId organizationId = item.scope().organizationId();
    boolean differentExecutorActive = assignments.stream()
        .filter(ResponsibilityAssignment::isActive)
        .anyMatch(value -> value.role() == ResponsibilityRole.EXECUTOR
            && value.scope().equals(item.scope()));
    List<AgentProfile> profiles = visibleProfiles(context, item);
    List<DelegationContext.AgentCandidate> candidates = new ArrayList<>();
    for (AgentProfile profile : profiles) {
      if (!profile.scope().teamId().filter(item.scope().teamId()::equals).isPresent()
          || !profile.workspaceId().equals(item.scope().workspaceId())) {
        continue;
      }
      Optional<Principal> principal =
          principalRepository.findById(organizationId, profile.agentPrincipalId());
      String displayName =
          principal.map(Principal::displayName).orElseGet(() -> profile.templateVersion().key().toString());
      boolean assigned = assignments.stream()
          .filter(ResponsibilityAssignment::isActive)
          .filter(value -> value.role() == ResponsibilityRole.EXECUTOR)
          .anyMatch(value -> value.actorPrincipalId().equals(profile.agentPrincipalId()));
      String state;
      Optional<String> reason = Optional.empty();
      if (assigned) {
        state = DelegationContext.STATE_ASSIGNED;
      } else if (profile.status() != AgentProfileStatus.ACTIVE) {
        state = DelegationContext.STATE_AGENT_DISABLED;
        reason = Optional.of("Agent 配置已停用或归档");
      } else if (principal.filter(Principal::canAct).isEmpty()
          || principal.filter(value -> value.type().isAgent()).isEmpty()) {
        state = DelegationContext.STATE_PRINCIPAL_INACTIVE;
        reason = Optional.of("Agent 主体不可用");
      } else if (differentExecutorActive) {
        state = DelegationContext.STATE_EXECUTOR_CONFLICT;
        reason = Optional.of("已有其他执行者责任——需先显式释放再分配");
      } else {
        state = DelegationContext.STATE_AVAILABLE;
      }
      candidates.add(new DelegationContext.AgentCandidate(
          profile.id(),
          profile.version(),
          profile.agentPrincipalId(),
          displayName,
          profile.ownership().type().name(),
          profile.runtimeRole().name(),
          state,
          reason));
      if (candidates.size() >= MAX_CANDIDATES) {
        break;
      }
    }
    candidates.sort(Comparator
        .comparing(DelegationContext.AgentCandidate::state)
        .thenComparing(DelegationContext.AgentCandidate::displayName));
    return List.copyOf(candidates);
  }

  /**
   * Candidates come from the member-visible Agent pages; a platform administrator without a
   * membership row falls back to the administrative page — both are existing authority scopes.
   */
  private List<AgentProfile> visibleProfiles(TeamAccessContext context, WorkItem item) {
    OrganizationId organizationId = item.scope().organizationId();
    TeamId teamId = item.scope().teamId();
    Optional<TeamMember> member = membershipQuery.findByTeam(organizationId, teamId).stream()
        .filter(TeamMember::canParticipate)
        .filter(value -> value.userPrincipalId().equals(context.actor().id()))
        .findFirst();
    List<AgentProfile> profiles = new ArrayList<>();
    if (member.isPresent()) {
      int offset = 0;
      while (profiles.size() < MAX_CANDIDATES) {
        List<AgentProfile> page = agentProfileRepository.findVisibleToMember(
            organizationId, teamId, member.orElseThrow().id(), offset, PROFILE_PAGE_SIZE);
        profiles.addAll(page);
        if (page.size() < PROFILE_PAGE_SIZE) {
          break;
        }
        offset += PROFILE_PAGE_SIZE;
      }
      return profiles;
    }
    if (!context.platformAdministrator()) {
      throw new PolicyDeniedException("read this Team's Agent candidates");
    }
    int offset = 0;
    while (profiles.size() < MAX_CANDIDATES) {
      List<AgentProfile> page = agentProfileRepository.findPage(
          organizationId, offset, PROFILE_PAGE_SIZE);
      profiles.addAll(page);
      if (page.size() < PROFILE_PAGE_SIZE) {
        break;
      }
      offset += PROFILE_PAGE_SIZE;
    }
    return profiles;
  }
}
