package io.crewscope.application.team;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.DomainEvent;
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
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.team.event.TeamCreated;
import io.crewscope.domain.team.event.TeamInitializationCompleted;
import io.crewscope.domain.team.event.TeamMemberJoined;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Coordinates Team commands, authorization, queries and durable business events. */
public final class TeamApplicationService {

  private static final String TEAM_AGGREGATE = "TEAM";
  private static final String TEAM_MEMBER_AGGREGATE = "TEAM_MEMBER";
  private static final String CREATE_TEAM = "CREATE_TEAM";
  private static final String ADD_TEAM_MEMBER = "ADD_TEAM_MEMBER";
  private static final String COMPLETE_TEAM_INITIALIZATION = "COMPLETE_TEAM_INITIALIZATION";

  private final TeamCreationService creationService;
  private final TeamRepository teamRepository;
  private final WorkspaceRepository workspaceRepository;
  private final TeamMemberRepository teamMemberRepository;
  private final TeamMembershipQuery membershipQuery;
  private final TeamRoleRepository teamRoleRepository;
  private final MemberRoleRepository memberRoleRepository;
  private final PrincipalRepository principalRepository;
  private final DefaultPersonalAgentRepository personalAgentRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public TeamApplicationService(
      TeamCreationService creationService,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMemberRepository teamMemberRepository,
      TeamMembershipQuery membershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      PrincipalRepository principalRepository,
      DefaultPersonalAgentRepository personalAgentRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.creationService = Objects.requireNonNull(creationService, "creationService");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
    this.teamMemberRepository =
        Objects.requireNonNull(teamMemberRepository, "teamMemberRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
    this.memberRoleRepository =
        Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.personalAgentRepository =
        Objects.requireNonNull(personalAgentRepository, "personalAgentRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** Creates a complete Team foundation and its publication facts atomically. */
  public CommandExecution<TeamInitialization> createTeam(
      TeamCommandContext context, CreateTeamCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    CreateTeamCommand required = Objects.requireNonNull(command, "command");
    return execute(
        trusted,
        CREATE_TEAM,
        CommandRequestHash.sha256(
            CREATE_TEAM,
            trusted.access().actor().id().toString(),
            trusted.causationId().map(UUID::toString).orElse(""),
            required.name().strip()),
        commandId -> {
          TeamInitialization result = creationService.create(trusted.access().actor(), required);
          return completed(
              trusted,
              commandId,
              result,
              TEAM_AGGREGATE,
              result.team().id(),
              result.team().version(),
              EventType.from("TEAM_CREATED"),
              TeamCreated.from(result),
              result.team().id(),
              Optional.of(result.defaultWorkspace().id()));
        });
  }

  /** Adds one USER, its MEMBER grant and default Personal Agent under MEMBER_MANAGE policy. */
  public CommandExecution<TeamMember> addMember(
      TeamCommandContext context, TeamId teamId, AddTeamMemberCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    AddTeamMemberCommand required = Objects.requireNonNull(command, "command");
    return execute(
        trusted,
        ADD_TEAM_MEMBER,
        CommandRequestHash.sha256(
            ADD_TEAM_MEMBER,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            trusted.causationId().map(UUID::toString).orElse(""),
            required.userPrincipalId().toString()),
        commandId -> addMemberInTransaction(trusted, commandId, requiredTeamId, required));
  }

  /** Completes an incomplete migrated Team once under platform-administrator authority. */
  public CommandExecution<TeamInitialization> completeInitialization(
      TeamCommandContext context, TeamId teamId, CompleteTeamInitializationCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    CompleteTeamInitializationCommand required = Objects.requireNonNull(command, "command");
    if (!trusted.access().platformAdministrator()) {
      throw new PolicyDeniedException("complete legacy Team initialization");
    }
    return execute(
        trusted,
        COMPLETE_TEAM_INITIALIZATION,
        CommandRequestHash.sha256(
            COMPLETE_TEAM_INITIALIZATION,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            trusted.causationId().map(UUID::toString).orElse(""),
            required.ownerPrincipalId().toString()),
        commandId ->
            completeInitializationInTransaction(trusted, commandId, requiredTeamId, required));
  }

  /** Lists only initialized Teams in which the current USER can participate. */
  public List<TeamView> listTeams(TeamAccessContext context, OrganizationId organizationId) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    return teamRepository.findActiveByMember(organizationId, trusted.actor().id()).stream()
        .map(TeamView::from)
        .toList();
  }

  /** Returns READY or administrator-visible INITIALIZATION_REQUIRED state. */
  public TeamView getTeam(TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    Optional<UninitializedTeam> legacy =
        teamRepository.findUninitializedById(organizationId, teamId);
    if (legacy.isPresent()) {
      if (!trusted.platformAdministrator()) {
        throw new PolicyDeniedException("read an uninitialized Team");
      }
      return TeamView.from(legacy.orElseThrow());
    }
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(trusted.actor(), team);
    return TeamView.from(team);
  }

  public List<TeamMember> listMembers(
      TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(trusted.actor(), team);
    return membershipQuery.findByTeam(organizationId, teamId);
  }

  public Workspace getDefaultWorkspace(
      TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    requireActiveMember(trusted.actor(), team);
    return workspaceRepository
        .findById(organizationId, team.defaultWorkspaceId())
        .orElseThrow(() -> new AggregateNotFoundException("Workspace", team.defaultWorkspaceId()));
  }

  private CommandExecution<TeamMember> addMemberInTransaction(
      TeamCommandContext context, UUID commandId, TeamId teamId, AddTeamMemberCommand command) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireLockedTeam(organizationId, teamId);
    UtcTimestamp occurredAt = timeProvider.now();
    TeamMember caller = requireActiveMember(actor, team);
    requirePermission(caller, TeamPermission.MEMBER_MANAGE, occurredAt);
    Principal target =
        principalRepository
            .findById(organizationId, command.userPrincipalId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Principal", command.userPrincipalId()));
    requireActiveUserInOrganization(target, organizationId);
    boolean duplicate =
        membershipQuery.findByTeam(organizationId, teamId).stream()
            .anyMatch(member -> member.userPrincipalId().equals(target.id()));
    if (duplicate) {
      throw new DomainValidationException(
          "teamMember.userPrincipalId", "already has a Membership in this Team");
    }
    TeamMember member =
        team.joinMember(TeamMemberId.generate(), target, TeamJoinMethod.OIDC, occurredAt);
    TeamMember committed = teamMemberRepository.create(member);
    TeamRole memberRole =
        teamRoleRepository.findByTeam(organizationId, teamId).stream()
            .filter(role -> role.isBuiltIn(BuiltInTeamRole.MEMBER))
            .findFirst()
            .orElseThrow(
                () -> new DomainValidationException("teamRole.MEMBER", "built-in role is missing"));
    memberRoleRepository.create(
        MemberRole.grant(
            MemberRoleId.generate(),
            committed,
            memberRole,
            RoleScope.team(),
            actor.id(),
            occurredAt,
            occurredAt,
            Optional.empty()));
    Workspace workspace =
        workspaceRepository
            .findById(organizationId, team.defaultWorkspaceId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Workspace", team.defaultWorkspaceId()));
    PersonalAgentInitialization personalAgent =
        personalAgentRepository.initializeIfAbsent(
            PersonalAgentInitialization.createDefault(committed, workspace, target, occurredAt));
    personalAgent.requireDefaultFor(committed, workspace);
    return completed(
        context,
        commandId,
        committed,
        TEAM_MEMBER_AGGREGATE,
        committed.id(),
        committed.version(),
        EventType.from("TEAM_MEMBER_JOINED"),
        TeamMemberJoined.from(committed),
        team.id(),
        Optional.of(workspace.id()));
  }

  private CommandExecution<TeamInitialization> completeInitializationInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      CompleteTeamInitializationCommand command) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    UninitializedTeam legacy =
        teamRepository
            .lockUninitializedById(organizationId, teamId)
            .orElseThrow(
                () ->
                    new DomainValidationException(
                        "team.initializationStatus", "must be INITIALIZATION_REQUIRED"));
    Principal owner =
        principalRepository
            .findById(organizationId, command.ownerPrincipalId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Principal", command.ownerPrincipalId()));
    requireActiveUserInOrganization(owner, organizationId);
    TeamInitialization result = creationService.completeLegacy(legacy, owner, actor);
    return completed(
        context,
        commandId,
        result,
        TEAM_AGGREGATE,
        result.team().id(),
        result.team().version(),
        EventType.from("TEAM_INITIALIZATION_COMPLETED"),
        TeamInitializationCompleted.from(result),
        result.team().id(),
        Optional.of(result.defaultWorkspace().id()));
  }

  private <T> CommandExecution<T> execute(
      TeamCommandContext context,
      String commandType,
      CommandRequestHash requestHash,
      Function<UUID, CommandExecution<T>> command) {
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

  private <T> CommandExecution<T> completed(
      TeamCommandContext context,
      UUID commandId,
      T result,
      String aggregateType,
      io.crewscope.domain.shared.id.AggregateId aggregateId,
      long aggregateVersion,
      EventType eventType,
      DomainEvent payload,
      TeamId teamId,
      Optional<io.crewscope.domain.shared.id.WorkspaceId> workspaceId) {
    UtcTimestamp occurredAt = timeProvider.now();
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            eventType,
            SchemaVersion.V1,
            context.access().actor().scope().organizationId(),
            Optional.of(teamId),
            workspaceId,
            AggregateReference.of(aggregateType, aggregateId),
            aggregateVersion,
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            payload);
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    CommandReceipt receipt =
        new CommandReceipt(commandId, eventId, aggregateVersion, context.correlationId());
    receiptStore.complete(
        context.access().actor().scope().organizationId(),
        context.idempotencyKey(),
        receipt,
        occurredAt);
    return CommandExecution.completed(result, receipt);
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

  private TeamMember requireActiveMember(Principal actor, Team team) {
    requireActiveUserInOrganization(actor, team.organizationId());
    return membershipQuery.findByTeam(team.organizationId(), team.id()).stream()
        .filter(member -> member.userPrincipalId().equals(actor.id()))
        .filter(TeamMember::canParticipate)
        .findFirst()
        .orElseThrow(() -> new PolicyDeniedException("access this Team"));
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
      throw new PolicyDeniedException("manage Team members");
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
