package io.crewscope.application.workitem;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.RoleScopeType;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamRoleKey;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemKeyConflictException;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.event.WorkItemCreated;
import io.crewscope.domain.workspace.Workspace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fakes for the work-item command tests, shared with the availability reconciliation test.
 *
 * <p>The reconciliation test must drive the real command service and the real projection over the
 * same state, so the harness cannot live inside one of the two test classes.
 */
final class WorkItemCommandTestSupport {

  private WorkItemCommandTestSupport() {}

  static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T07:00:00Z");

  static final class Fixture {
    final OrganizationId organizationId = OrganizationId.generate();
    final Principal actor =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    final TeamInitialization initialization = TeamInitialization.create(actor, "Team", NOW);
    final WorkProject project =
        WorkProject.create(
            WorkProjectId.generate(),
            new WorkProjectKey("CRW"),
            "CrewScope",
            initialization.team(),
            initialization.defaultWorkspace(),
            actor,
            NOW);
    final Store store = new Store(initialization, project, actor);
    private final TeamMembershipQuery membershipQuery = (organization, team) -> store.members;
    private final TeamRoleRepository roleRepository =
        new TeamRoleRepository() {
          @Override
          public List<TeamRole> createAll(List<TeamRole> values) {
            store.roles = List.copyOf(values);
            return store.roles;
          }

          @Override
          public List<TeamRole> findByTeam(OrganizationId organization, TeamId team) {
            return store.roles;
          }
        };
    private final WorkItemAccessPolicy sharedAccessPolicy =
        new WorkItemAccessPolicy(store, store, store, membershipQuery, roleRepository, store);
    private final WorkItemCommandService service = buildService(null);

    private WorkItemCommandService buildService(WorkItemAccessPolicy transitionAccessPolicy) {
      return new WorkItemCommandService(
          store,
          store,
          store,
          membershipQuery,
          roleRepository,
          store,
          store,
          store,
          store,
          store,
          new DirectTransactionExecutor(),
          () -> NOW,
          transitionAccessPolicy);
    }

    /** The real policy, so a test can project and execute through one shared object. */
    WorkItemAccessPolicy accessPolicy() {
      return sharedAccessPolicy;
    }

    /** The command service wired to that same policy object rather than to its own rules. */
    WorkItemCommandService serviceSharingAccessPolicy() {
      return buildService(sharedAccessPolicy);
    }

    TeamCommandContext context(String key) {
      return new TeamCommandContext(
          new TeamAccessContext(actor, false),
          IdempotencyKey.from(key),
          UUID.randomUUID(),
          Optional.empty());
    }

    CreateNativeWorkItemCommand createCommand(String key, String title) {
      return new CreateNativeWorkItemCommand(
          key,
          WorkItemType.FEATURE,
          title,
          Optional.of(" Detailed plan "),
          WorkItemPriority.HIGH,
          Set.of(new WorkItemLabel("Backend")),
          Optional.empty());
    }

    CommandExecution<WorkItem> create(
        TeamCommandContext context, CreateNativeWorkItemCommand command) {
      return service.create(context, initialization.team().id(), project.id(), command);
    }

    CommandExecution<WorkItem> transition(
        TeamCommandContext context, WorkItemId itemId, TransitionWorkItemCommand command) {
      return service.transition(
          context, initialization.team().id(), project.id(), itemId, command);
    }

    void useProjectRole(WorkProjectId projectId) {
      TeamRole role =
          TeamRole.createCustom(
              TeamRoleId.generate(),
              initialization.team().scope(),
              new TeamRoleKey("PROJECT_WORKER"),
              "Project Worker",
              Optional.empty(),
              Set.of(TeamPermission.WORK_CREATE, TeamPermission.WORK_PARTICIPATE),
              RoleScopeType.WORK_PROJECT,
              NOW);
      store.roles = List.of(role);
      store.grants =
          List.of(
              MemberRole.grant(
                  MemberRoleId.generate(),
                  initialization.ownerMember(),
                  role,
                  RoleScope.workProject(projectId),
                  actor.id(),
                  NOW,
                  NOW,
                  Optional.empty()));
    }
  }

  static final class Store
      implements WorkItemRepository,
          WorkProjectRepository,
          TeamRepository,
          MemberRoleRepository,
          ResponsibilityAssignmentRepository,
          DomainEventStore,
          OutboxRepository,
          CommandReceiptStore {

    private final TeamInitialization initialization;
    private final Map<WorkProjectId, WorkProject> projects = new LinkedHashMap<>();
    final Map<WorkItemId, WorkItem> items = new LinkedHashMap<>();
    private final Map<ResponsibilityAssignmentId, ResponsibilityAssignment> assignments =
        new LinkedHashMap<>();
    private final Map<String, ReceiptEntry> receipts = new HashMap<>();
    final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
    final List<PendingOutboxEvent> outbox = new ArrayList<>();
    List<TeamMember> members;
    List<TeamRole> roles;
    List<MemberRole> grants;

    private Store(TeamInitialization initialization, WorkProject project, Principal actor) {
      this.initialization = initialization;
      this.projects.put(project.id(), project);
      this.members = List.of(initialization.ownerMember());
      TeamRole ownerRole =
          TeamRole.createBuiltIn(
              TeamRoleId.generate(),
              initialization.team().scope(),
              BuiltInTeamRole.TEAM_OWNER,
              NOW);
      this.roles = List.of(ownerRole);
      this.grants =
          List.of(
              MemberRole.grantOwner(
                  MemberRoleId.generate(),
                  initialization.team(),
                  initialization.ownerMember(),
                  ownerRole,
                  actor.id(),
                  NOW));
    }

    @Override
    public WorkItem create(WorkItem item) {
      items.put(item.id(), item);
      return item;
    }

    @Override
    public WorkItem update(WorkItem item) {
      items.put(item.id(), item);
      return item;
    }

    @Override
    public Optional<WorkItem> findById(OrganizationId organizationId, WorkItemId id) {
      return Optional.ofNullable(items.get(id))
          .filter(item -> item.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<WorkItem> findByKey(
        OrganizationId organizationId, WorkProjectId projectId, WorkItemKey key) {
      return items.values().stream()
          .filter(item -> item.scope().organizationId().equals(organizationId))
          .filter(item -> item.scope().projectId().equals(projectId))
          .filter(item -> item.key().equals(key))
          .findFirst();
    }

    @Override
    public WorkItemPage findPage(WorkItemQuery query) {
      return new WorkItemPage(List.of(), Optional.empty());
    }

    @Override
    public WorkProject create(WorkProject project) {
      projects.put(project.id(), project);
      return project;
    }

    @Override
    public WorkProject update(WorkProject project) {
      projects.put(project.id(), project);
      return project;
    }

    @Override
    public Optional<WorkProject> findById(
        OrganizationId organizationId, WorkProjectId id) {
      return Optional.ofNullable(projects.get(id))
          .filter(project -> project.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<WorkProject> lockById(
        OrganizationId organizationId, WorkProjectId id) {
      return findById(organizationId, id);
    }

    @Override
    public Optional<WorkProject> findByKey(
        OrganizationId organizationId, TeamId teamId, WorkProjectKey key) {
      return projects.values().stream()
          .filter(project -> project.scope().organizationId().equals(organizationId))
          .filter(project -> project.scope().teamId().equals(teamId))
          .filter(project -> project.key().equals(key))
          .findFirst();
    }

    @Override
    public List<WorkProject> findByTeam(OrganizationId organizationId, TeamId teamId) {
      return projects.values().stream()
          .filter(project -> project.scope().organizationId().equals(organizationId))
          .filter(project -> project.scope().teamId().equals(teamId))
          .toList();
    }

    @Override
    public WorkProjectPage findPage(WorkProjectQuery query) {
      return new WorkProjectPage(List.of(), Optional.empty());
    }

    @Override
    public Team create(Team team) {
      return team;
    }

    @Override
    public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
      return Optional.of(initialization.team())
          .filter(team -> team.organizationId().equals(organizationId) && team.id().equals(id));
    }

    @Override
    public Optional<UninitializedTeam> findUninitializedById(
        OrganizationId organizationId, TeamId id) {
      return Optional.empty();
    }

    @Override
    public MemberRole create(MemberRole memberRole) {
      grants = new ArrayList<>(grants);
      grants.add(memberRole);
      return memberRole;
    }

    @Override
    public List<MemberRole> findByMember(
        OrganizationId organizationId, io.crewscope.domain.team.TeamMemberId memberId) {
      return grants;
    }

    @Override
    public void lockResponsibilityChain(OrganizationId organizationId, WorkItemId workItemId) {
      if (findById(organizationId, workItemId).isEmpty()) {
        throw new IllegalArgumentException("WorkItem does not exist");
      }
    }

    @Override
    public ResponsibilityAssignment create(ResponsibilityAssignment assignment) {
      assignments.put(assignment.id(), assignment);
      return assignment;
    }

    @Override
    public ResponsibilityAssignment update(ResponsibilityAssignment assignment) {
      assignments.put(assignment.id(), assignment);
      return assignment;
    }

    @Override
    public Optional<ResponsibilityAssignment> findById(
        OrganizationId organizationId, ResponsibilityAssignmentId id) {
      return Optional.ofNullable(assignments.get(id))
          .filter(value -> value.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<ResponsibilityAssignment> findActiveOwner(
        OrganizationId organizationId, WorkItemId workItemId) {
      return assignments.values().stream()
          .filter(value -> value.scope().organizationId().equals(organizationId))
          .filter(value -> value.workItemId().equals(workItemId))
          .filter(value -> value.role() == ResponsibilityRole.OWNER)
          .filter(ResponsibilityAssignment::isActive)
          .findFirst();
    }

    @Override
    public List<ResponsibilityAssignment> findActiveByWorkItem(
        OrganizationId organizationId, WorkItemId workItemId) {
      return assignments.values().stream()
          .filter(value -> value.scope().organizationId().equals(organizationId))
          .filter(value -> value.workItemId().equals(workItemId))
          .filter(ResponsibilityAssignment::isActive)
          .toList();
    }

    @Override
    public Optional<ResponsibilityAssignment> findActive(
        OrganizationId organizationId,
        WorkItemId workItemId,
        ResponsibilityRole role,
        PrincipalId actorPrincipalId) {
      return assignments.values().stream()
          .filter(value -> value.scope().organizationId().equals(organizationId))
          .filter(value -> value.workItemId().equals(workItemId))
          .filter(value -> value.role() == role)
          .filter(value -> value.actorPrincipalId().equals(actorPrincipalId))
          .filter(ResponsibilityAssignment::isActive)
          .findFirst();
    }

    @Override
    public void append(DomainEventEnvelope<? extends DomainEvent> event) {
      events.add(event);
    }

    @Override
    public void enqueue(PendingOutboxEvent event) {
      outbox.add(event);
    }

    @Override
    public CommandReservation reserve(CommandReservationRequest request) {
      String key = request.organizationId() + ":" + request.idempotencyKey();
      ReceiptEntry existing = receipts.get(key);
      if (existing == null) {
        receipts.put(key, new ReceiptEntry(request, null));
        return CommandReservation.newlyAcquired();
      }
      if (!existing.request.commandType().equals(request.commandType())
          || !existing.request.requestHash().equals(request.requestHash())) {
        throw new IdempotencyConflictException(
            request.idempotencyKey().value(),
            existing.request.requestHash().value(),
            request.requestHash().value());
      }
      return CommandReservation.replay(existing.receipt);
    }

    @Override
    public void complete(
        OrganizationId organizationId,
        IdempotencyKey idempotencyKey,
        CommandReceipt receipt,
        UtcTimestamp completedAt) {
      String key = organizationId + ":" + idempotencyKey;
      ReceiptEntry existing = receipts.get(key);
      receipts.put(key, new ReceiptEntry(existing.request, receipt));
    }

    private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
  }

  private static final class DirectTransactionExecutor implements TransactionExecutor {

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }
}
