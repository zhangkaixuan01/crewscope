package io.crewscope.application.workitem;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
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
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemKeyConflictException;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.event.WorkItemCreated;
import io.crewscope.domain.workitem.event.WorkItemStatusChanged;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Executes authorized native WorkItem commands with idempotency and optimistic concurrency. */
public final class WorkItemCommandService {

  private static final String WORK_ITEM_AGGREGATE = "WORK_ITEM";
  private static final String CREATE_WORK_ITEM = "CREATE_NATIVE_WORK_ITEM";
  private static final String TRANSITION_WORK_ITEM = "TRANSITION_WORK_ITEM";

  private final WorkItemRepository workItemRepository;
  private final WorkProjectRepository projectRepository;
  private final TeamRepository teamRepository;
  private final TeamMembershipQuery membershipQuery;
  private final TeamRoleRepository teamRoleRepository;
  private final MemberRoleRepository memberRoleRepository;
  private final ResponsibilityAssignmentRepository assignmentRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public WorkItemCommandService(
      WorkItemRepository workItemRepository,
      WorkProjectRepository projectRepository,
      TeamRepository teamRepository,
      TeamMembershipQuery membershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      ResponsibilityAssignmentRepository assignmentRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
    this.memberRoleRepository = Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** Creates a fully described CrewScope-native WorkItem in an active WorkProject. */
  public CommandExecution<WorkItem> create(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      CreateNativeWorkItemCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    WorkProjectId requiredProjectId = Objects.requireNonNull(projectId, "projectId");
    CreateNativeWorkItemCommand required = Objects.requireNonNull(command, "command");
    WorkItemKey itemKey = new WorkItemKey(required.key());
    CommandRequestHash requestHash =
        createRequestHash(trusted, requiredTeamId, requiredProjectId, itemKey, required);
    return execute(
        trusted,
        CREATE_WORK_ITEM,
        requestHash,
        commandId ->
            createInTransaction(
                trusted, commandId, requiredTeamId, requiredProjectId, itemKey, required));
  }

  /** Applies one state-machine transition using the client's expected committed version. */
  public CommandExecution<WorkItem> transition(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      TransitionWorkItemCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    WorkProjectId requiredProjectId = Objects.requireNonNull(projectId, "projectId");
    WorkItemId requiredWorkItemId = Objects.requireNonNull(workItemId, "workItemId");
    TransitionWorkItemCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash requestHash =
        CommandRequestHash.sha256(
            TRANSITION_WORK_ITEM,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            requiredProjectId.toString(),
            requiredWorkItemId.toString(),
            required.targetStatus().name(),
            Long.toString(required.expectedVersion()),
            trusted.causationId().map(UUID::toString).orElse(""));
    return execute(
        trusted,
        TRANSITION_WORK_ITEM,
        requestHash,
        commandId ->
            transitionInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredProjectId,
                requiredWorkItemId,
                required));
  }

  private CommandExecution<WorkItem> createInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemKey itemKey,
      CreateNativeWorkItemCommand command) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireTeam(organizationId, teamId);
    WorkProject project = requireLockedProject(organizationId, teamId, projectId);
    UtcTimestamp occurredAt = timeProvider.now();
    TeamMember member = requireActiveMember(actor, team);
    requirePermission(
        member,
        TeamPermission.WORK_CREATE,
        project.id(),
        occurredAt,
        "create WorkItems in this WorkProject");
    if (workItemRepository.findByKey(organizationId, project.id(), itemKey).isPresent()) {
      throw new WorkItemKeyConflictException(project.id(), itemKey);
    }
    WorkItem workItem =
        WorkItem.createNative(
            WorkItemId.generate(),
            project,
            itemKey,
            command.type(),
            command.title(),
            command.description(),
            command.priority(),
            command.labels(),
            command.dueAt(),
            actor,
            occurredAt);
    WorkItem committed = workItemRepository.create(workItem);
    ResponsibilityAssignment initialOwner =
        assignmentRepository.create(
            ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                committed,
                ResponsibilityRole.OWNER,
                actor,
                Optional.of(member),
                actor,
                occurredAt));
    return completed(
        context,
        commandId,
        committed,
        EventType.from("WORK_ITEM_CREATED"),
        WorkItemCreated.from(committed, initialOwner),
        occurredAt);
  }

  private CommandExecution<WorkItem> transitionInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      TransitionWorkItemCommand command) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireTeam(organizationId, teamId);
    WorkProject project = requireProject(organizationId, teamId, projectId);
    UtcTimestamp occurredAt = timeProvider.now();
    TeamMember member = requireActiveMember(actor, team);
    requirePermission(
        member,
        TeamPermission.WORK_PARTICIPATE,
        project.id(),
        occurredAt,
        "transition WorkItems in this WorkProject");
    WorkItem current = requireWorkItem(organizationId, project, workItemId);
    if (!current.source().isNative()) {
      throw new PolicyDeniedException("transition an externally managed WorkItem");
    }
    if (current.version() != command.expectedVersion()) {
      throw new OptimisticLockConflictException(
          "WorkItem", workItemId, command.expectedVersion(), current.version());
    }
    WorkItem changed = current.transitionTo(command.targetStatus(), actor, occurredAt);
    WorkItem committed = workItemRepository.update(changed);
    return completed(
        context,
        commandId,
        committed,
        EventType.from("WORK_ITEM_STATUS_CHANGED"),
        WorkItemStatusChanged.from(current, committed),
        occurredAt);
  }

  private CommandExecution<WorkItem> execute(
      TeamCommandContext context,
      String commandType,
      CommandRequestHash requestHash,
      Function<UUID, CommandExecution<WorkItem>> command) {
    return transactionExecutor.required(
        () -> {
          UtcTimestamp now = timeProvider.now();
          UUID commandId = UUID.randomUUID();
          CommandReservation reservation =
              receiptStore.reserve(
                  new CommandReservationRequest(
                      context.access().actor().scope().organizationId(),
                      context.idempotencyKey(),
                      commandType,
                      requestHash,
                      commandId,
                      context.correlationId(),
                      now));
          if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
          }
          return command.apply(commandId);
        });
  }

  private CommandExecution<WorkItem> completed(
      TeamCommandContext context,
      UUID commandId,
      WorkItem workItem,
      EventType eventType,
      DomainEvent payload,
      UtcTimestamp occurredAt) {
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            eventType,
            SchemaVersion.V1,
            workItem.scope().organizationId(),
            Optional.of(workItem.scope().teamId()),
            Optional.of(workItem.scope().workspaceId()),
            AggregateReference.of(WORK_ITEM_AGGREGATE, workItem.id()),
            workItem.version(),
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            payload);
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    CommandReceipt receipt =
        new CommandReceipt(commandId, eventId, workItem.version(), context.correlationId());
    receiptStore.complete(
        workItem.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
    return CommandExecution.completed(workItem, receipt);
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

  private WorkProject requireLockedProject(
      OrganizationId organizationId, TeamId teamId, WorkProjectId projectId) {
    return projectRepository
        .lockById(organizationId, projectId)
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
    requireActiveUserInOrganization(actor, team.organizationId());
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

  private static TeamCommandContext requireCommandContext(TeamCommandContext context) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    requireActiveUserInOrganization(
        trusted.access().actor(), trusted.access().actor().scope().organizationId());
    return trusted;
  }

  private static void requireActiveUserInOrganization(
      Principal principal, OrganizationId organizationId) {
    Principal actor = Objects.requireNonNull(principal, "principal");
    if (actor.type() != PrincipalType.USER
        || !actor.canAct()
        || !actor.scope().organizationId().equals(organizationId)) {
      throw new PolicyDeniedException("act in this Organization");
    }
  }

  private static CommandRequestHash createRequestHash(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemKey itemKey,
      CreateNativeWorkItemCommand command) {
    List<String> fields = new ArrayList<>();
    fields.add(context.access().actor().id().toString());
    fields.add(teamId.toString());
    fields.add(projectId.toString());
    fields.add(itemKey.value());
    fields.add(command.type().name());
    fields.add(command.title().strip());
    fields.add(command.description().map(String::strip).filter(text -> !text.isEmpty()).orElse(""));
    fields.add(command.priority().name());
    List<String> labels = command.labels().stream().map(label -> label.value()).sorted().toList();
    fields.add(Integer.toString(labels.size()));
    fields.addAll(labels);
    fields.add(command.dueAt().map(Object::toString).orElse(""));
    fields.add(context.causationId().map(UUID::toString).orElse(""));
    return CommandRequestHash.sha256(CREATE_WORK_ITEM, fields.toArray(String[]::new));
  }
}
