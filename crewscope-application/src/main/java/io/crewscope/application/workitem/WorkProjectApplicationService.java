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
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
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
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkProjectKeyConflictException;
import io.crewscope.domain.workitem.event.WorkProjectCreated;
import io.crewscope.domain.workspace.Workspace;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Coordinates WorkProject commands, membership policy, queries and durable events. */
public final class WorkProjectApplicationService {

  private static final String WORK_PROJECT_AGGREGATE = "WORK_PROJECT";
  private static final String CREATE_WORK_PROJECT = "CREATE_WORK_PROJECT";

  private final WorkProjectRepository projectRepository;
  private final TeamRepository teamRepository;
  private final WorkspaceRepository workspaceRepository;
  private final TeamMembershipQuery membershipQuery;
  private final TeamRoleRepository teamRoleRepository;
  private final MemberRoleRepository memberRoleRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public WorkProjectApplicationService(
      WorkProjectRepository projectRepository,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMembershipQuery membershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
    this.memberRoleRepository = Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** Creates a WorkProject in the Team default Workspace under Team-scope management policy. */
  public CommandExecution<WorkProject> create(
      TeamCommandContext context, TeamId teamId, CreateWorkProjectCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    CreateWorkProjectCommand required = Objects.requireNonNull(command, "command");
    WorkProjectKey key = new WorkProjectKey(required.key());
    String normalizedName = required.name().strip();
    CommandRequestHash requestHash =
        CommandRequestHash.sha256(
            CREATE_WORK_PROJECT,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            trusted.causationId().map(UUID::toString).orElse(""),
            key.value(),
            normalizedName);
    return execute(
        trusted,
        requestHash,
        commandId ->
            createInTransaction(trusted, commandId, requiredTeamId, key, normalizedName));
  }

  /** Lists only projects from a Team visible to the current active member. */
  public WorkProjectPage list(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectCursor> cursor,
      int limit) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(trusted.actor(), team);
    return projectRepository.findPage(
        new WorkProjectQuery(organizationId, teamId, cursor, limit));
  }

  /** Returns one visible project and treats a Team-scope mismatch as not found. */
  public WorkProject get(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(trusted.actor(), team);
    return requireProject(organizationId, teamId, projectId);
  }

  /** Checks a syntactically valid key without weakening the database uniqueness guarantee. */
  public WorkProjectKeyAvailability keyAvailability(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectKey key) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(trusted.actor(), team);
    WorkProjectKey requiredKey = Objects.requireNonNull(key, "key");
    return new WorkProjectKeyAvailability(
        requiredKey, projectRepository.findByKey(organizationId, teamId, requiredKey).isEmpty());
  }

  private CommandExecution<WorkProject> createInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      WorkProjectKey key,
      String name) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireLockedTeam(organizationId, teamId);
    UtcTimestamp occurredAt = timeProvider.now();
    TeamMember member = requireActiveMember(actor, team);
    requirePermission(member, TeamPermission.WORK_PROJECT_MANAGE, occurredAt);
    if (projectRepository.findByKey(organizationId, teamId, key).isPresent()) {
      throw new WorkProjectKeyConflictException(teamId, key);
    }
    Workspace workspace =
        workspaceRepository
            .findById(organizationId, team.defaultWorkspaceId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Workspace", team.defaultWorkspaceId()));
    WorkProject project =
        WorkProject.create(
            WorkProjectId.generate(), key, name, team, workspace, actor, occurredAt);
    WorkProject committed = projectRepository.create(project);
    return completed(context, commandId, committed, occurredAt);
  }

  private CommandExecution<WorkProject> execute(
      TeamCommandContext context,
      CommandRequestHash requestHash,
      Function<UUID, CommandExecution<WorkProject>> command) {
    return transactionExecutor.required(
        () -> {
          UtcTimestamp now = timeProvider.now();
          UUID commandId = UUID.randomUUID();
          CommandReservation reservation =
              receiptStore.reserve(
                  new CommandReservationRequest(
                      context.access().actor().scope().organizationId(),
                      context.idempotencyKey(),
                      CREATE_WORK_PROJECT,
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

  private CommandExecution<WorkProject> completed(
      TeamCommandContext context, UUID commandId, WorkProject project, UtcTimestamp occurredAt) {
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<WorkProjectCreated> event =
        new DomainEventEnvelope<>(
            eventId,
            EventType.from("WORK_PROJECT_CREATED"),
            SchemaVersion.V1,
            project.scope().organizationId(),
            Optional.of(project.scope().teamId()),
            Optional.of(project.scope().workspaceId()),
            AggregateReference.of(WORK_PROJECT_AGGREGATE, project.id()),
            project.version(),
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            WorkProjectCreated.from(project));
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    CommandReceipt receipt =
        new CommandReceipt(commandId, eventId, project.version(), context.correlationId());
    receiptStore.complete(
        project.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
    return CommandExecution.completed(project, receipt);
  }

  private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
    if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
      throw new DomainValidationException("team.initializationStatus", "must be READY");
    }
    return teamRepository
        .findById(organizationId, teamId)
        .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
  }

  private Team requireLockedTeam(OrganizationId organizationId, TeamId teamId) {
    if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
      throw new DomainValidationException("team.initializationStatus", "must be READY");
    }
    return teamRepository
        .lockById(organizationId, teamId)
        .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
  }

  private WorkProject requireProject(
      OrganizationId organizationId, TeamId teamId, WorkProjectId projectId) {
    return projectRepository
        .findById(organizationId, projectId)
        .filter(project -> project.scope().teamId().equals(teamId))
        .orElseThrow(() -> new AggregateNotFoundException("WorkProject", projectId));
  }

  private TeamMember requireActiveMember(Principal actor, Team team) {
    requireActiveUserInOrganization(actor, team.organizationId());
    return membershipQuery.findByTeam(team.organizationId(), team.id()).stream()
        .filter(member -> member.userPrincipalId().equals(actor.id()))
        .filter(TeamMember::canParticipate)
        .findFirst()
        .orElseThrow(() -> new PolicyDeniedException("access this Team's WorkProjects"));
  }

  private void requirePermission(
      TeamMember member, TeamPermission permission, UtcTimestamp occurredAt) {
    Map<TeamRoleId, TeamRole> roles =
        teamRoleRepository
            .findByTeam(member.scope().organizationId(), member.scope().teamId())
            .stream()
            .collect(Collectors.toMap(TeamRole::id, role -> role));
    boolean allowed =
        memberRoleRepository.findByMember(member.scope().organizationId(), member.id()).stream()
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .filter(grant -> grant.isEffectiveAt(occurredAt))
            .filter(grant -> grant.roleScope().equals(RoleScope.team()))
            .map(grant -> roles.get(grant.teamRoleId()))
            .filter(Objects::nonNull)
            .filter(TeamRole::isGrantable)
            .anyMatch(role -> role.permissions().contains(permission));
    if (!allowed) {
      throw new PolicyDeniedException("create WorkProjects in this Team");
    }
  }

  private static TeamCommandContext requireCommandContext(TeamCommandContext context) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    requireActiveUserInOrganization(
        trusted.access().actor(), trusted.access().actor().scope().organizationId());
    return trusted;
  }

  private static TeamAccessContext requireAccess(
      TeamAccessContext context, OrganizationId organizationId) {
    TeamAccessContext trusted = Objects.requireNonNull(context, "context");
    requireActiveUserInOrganization(trusted.actor(), organizationId);
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
}
