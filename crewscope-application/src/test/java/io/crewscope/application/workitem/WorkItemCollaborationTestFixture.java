package io.crewscope.application.workitem;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
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
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
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
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Shared in-memory boundary fixture for WorkItem collaboration application tests. */
public final class WorkItemCollaborationTestFixture
    implements WorkItemRepository,
        TeamRepository,
        MemberRoleRepository,
        DomainEventStore,
        OutboxRepository,
        CommandReceiptStore,
        TransactionExecutor {

  public static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T09:00:00Z");

  public final OrganizationId organizationId = OrganizationId.generate();
  public final Principal actor =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          NOW);
  public final TeamInitialization initialization = TeamInitialization.create(actor, "Platform", NOW);
  public final WorkProject project =
      WorkProject.create(
          WorkProjectId.generate(),
          new WorkProjectKey("CRW"),
          "CrewScope",
          initialization.team(),
          initialization.defaultWorkspace(),
          actor,
          NOW);
  public final WorkItem item =
      WorkItem.createNative(
          WorkItemId.generate(),
          project,
          new WorkItemKey("CRW-1"),
          WorkItemType.FEATURE,
          "Collaboration API",
          Optional.empty(),
          WorkItemPriority.HIGH,
          Set.of(),
          Optional.empty(),
          actor,
          NOW);

  final Map<WorkItemId, WorkItem> items = new LinkedHashMap<>();
  final List<WorkItemComment> comments = new ArrayList<>();
  final List<WorkItemResourceLink> links = new ArrayList<>();
  public final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
  final List<PendingOutboxEvent> outbox = new ArrayList<>();
  final Map<String, ReceiptEntry> receipts = new HashMap<>();
  public List<TeamMember> members = List.of(initialization.ownerMember());
  List<TeamRole> roles;
  List<MemberRole> grants;
  WorkItemQuery lastQuery;
  final WorkProjectRepository projectRepository = new ProjectRepository();
  final TeamRoleRepository roleRepository = new RoleRepository();
  final WorkItemCommentRepository commentRepository = new CommentRepository();
  final WorkItemResourceLinkRepository linkRepository = new LinkRepository();

  public WorkItemCollaborationTestFixture() {
    items.put(item.id(), item);
    TeamRole ownerRole =
        TeamRole.createBuiltIn(
            TeamRoleId.generate(), initialization.team().scope(), BuiltInTeamRole.TEAM_OWNER, NOW);
    roles = List.of(ownerRole);
    grants =
        List.of(
            MemberRole.grantOwner(
                MemberRoleId.generate(),
                initialization.team(),
                initialization.ownerMember(),
                ownerRole,
                actor.id(),
                NOW));
  }

  public TeamMembershipQuery membershipQuery() {
    return (organization, team) -> members;
  }

  public WorkItemAccessPolicy accessPolicy() {
    return new WorkItemAccessPolicy(
        this, projectRepository, this, membershipQuery(), roleRepository, this);
  }

  public TeamAccessContext access() {
    return new TeamAccessContext(actor, false);
  }

  public TeamCommandContext commandContext(String key) {
    return new TeamCommandContext(
        access(), IdempotencyKey.from(key), UUID.randomUUID(), Optional.empty());
  }

  public void useProjectRole(WorkProjectId scope) {
    TeamRole role =
        TeamRole.createCustom(
            TeamRoleId.generate(),
            initialization.team().scope(),
            new TeamRoleKey("PROJECT_WORKER"),
            "Project Worker",
            Optional.empty(),
            Set.of(TeamPermission.WORK_PARTICIPATE),
            RoleScopeType.WORK_PROJECT,
            NOW);
    roles = List.of(role);
    grants =
        List.of(
            MemberRole.grant(
                MemberRoleId.generate(),
                initialization.ownerMember(),
                role,
                RoleScope.workProject(scope),
                actor.id(),
                NOW,
                NOW,
                Optional.empty()));
  }

  @Override
  public WorkItem create(WorkItem value) {
    items.put(value.id(), value);
    return value;
  }

  @Override
  public WorkItem update(WorkItem value) {
    items.put(value.id(), value);
    return value;
  }

  @Override
  public Optional<WorkItem> findById(OrganizationId organization, WorkItemId id) {
    return Optional.ofNullable(items.get(id))
        .filter(value -> value.scope().organizationId().equals(organization));
  }

  @Override
  public WorkItemPage findPage(WorkItemQuery query) {
    lastQuery = query;
    return new WorkItemPage(List.copyOf(items.values()), Optional.empty());
  }

  @Override
  public Team create(Team value) {
    return value;
  }

  @Override
  public Optional<Team> findById(OrganizationId organization, TeamId id) {
    return Optional.of(initialization.team())
        .filter(value -> value.organizationId().equals(organization))
        .filter(value -> value.id().equals(id));
  }

  @Override
  public Optional<UninitializedTeam> findUninitializedById(
      OrganizationId organization, TeamId id) {
    return Optional.empty();
  }

  @Override
  public MemberRole create(MemberRole value) {
    grants = new ArrayList<>(grants);
    grants.add(value);
    return value;
  }

  @Override
  public List<MemberRole> findByMember(
      OrganizationId organization, io.crewscope.domain.team.TeamMemberId memberId) {
    return grants;
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
    if (!existing.request().commandType().equals(request.commandType())
        || !existing.request().requestHash().equals(request.requestHash())) {
      throw new IdempotencyConflictException(
          request.idempotencyKey().value(),
          existing.request().requestHash().value(),
          request.requestHash().value());
    }
    return CommandReservation.replay(existing.receipt());
  }

  @Override
  public void complete(
      OrganizationId organization,
      IdempotencyKey idempotencyKey,
      CommandReceipt receipt,
      UtcTimestamp completedAt) {
    String key = organization + ":" + idempotencyKey;
    ReceiptEntry existing = receipts.get(key);
    receipts.put(key, new ReceiptEntry(existing.request(), receipt));
  }

  @Override
  public <T> T required(Supplier<T> operation) {
    return operation.get();
  }

  private final class ProjectRepository implements WorkProjectRepository {

    @Override
    public WorkProject create(WorkProject value) {
      return value;
    }

    @Override
    public WorkProject update(WorkProject value) {
      return value;
    }

    @Override
    public Optional<WorkProject> findById(OrganizationId organization, WorkProjectId id) {
      return Optional.of(project)
          .filter(value -> value.scope().organizationId().equals(organization))
          .filter(value -> value.id().equals(id));
    }

    @Override
    public Optional<WorkProject> findByKey(
        OrganizationId organization, TeamId team, WorkProjectKey key) {
      return findById(organization, project.id())
          .filter(value -> value.scope().teamId().equals(team))
          .filter(value -> value.key().equals(key));
    }

    @Override
    public List<WorkProject> findByTeam(OrganizationId organization, TeamId team) {
      return findById(organization, project.id())
          .filter(value -> value.scope().teamId().equals(team))
          .stream()
          .toList();
    }

    @Override
    public WorkProjectPage findPage(WorkProjectQuery query) {
      return new WorkProjectPage(List.of(project), Optional.empty());
    }
  }

  private final class RoleRepository implements TeamRoleRepository {

    @Override
    public List<TeamRole> createAll(List<TeamRole> values) {
      roles = List.copyOf(values);
      return roles;
    }

    @Override
    public List<TeamRole> findByTeam(OrganizationId organization, TeamId team) {
      return roles;
    }
  }

  private final class CommentRepository implements WorkItemCommentRepository {

    @Override
    public WorkItemComment create(WorkItemComment value) {
      comments.add(value);
      return value;
    }

    @Override
    public Optional<WorkItemComment> findById(OrganizationId organization, WorkItemCommentId id) {
      return comments.stream()
          .filter(value -> value.scope().organizationId().equals(organization))
          .filter(value -> value.id().equals(id))
          .findFirst();
    }

    @Override
    public List<WorkItemComment> findByWorkItem(
        OrganizationId organization, WorkItemId workItemId) {
      return comments.stream()
          .filter(value -> value.scope().organizationId().equals(organization))
          .filter(value -> value.workItemId().equals(workItemId))
          .toList();
    }
  }

  private final class LinkRepository implements WorkItemResourceLinkRepository {

    @Override
    public WorkItemResourceLink create(WorkItemResourceLink value) {
      links.add(value);
      return value;
    }

    @Override
    public Optional<WorkItemResourceLink> findById(
        OrganizationId organization, WorkItemResourceLinkId id) {
      return links.stream()
          .filter(value -> value.scope().organizationId().equals(organization))
          .filter(value -> value.id().equals(id))
          .findFirst();
    }

    @Override
    public List<WorkItemResourceLink> findByWorkItem(
        OrganizationId organization, WorkItemId workItemId) {
      return links.stream()
          .filter(value -> value.scope().organizationId().equals(organization))
          .filter(value -> value.workItemId().equals(workItemId))
          .toList();
    }
  }

  private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
}
