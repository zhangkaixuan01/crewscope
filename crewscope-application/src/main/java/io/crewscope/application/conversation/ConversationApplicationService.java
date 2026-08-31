package io.crewscope.application.conversation;

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
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.execution.AgentMessageCandidate;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationAccessDecision;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationMessageAppend;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationParticipantStatus;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.conversation.event.ConversationCreated;
import io.crewscope.domain.conversation.event.ConversationMessagePosted;
import io.crewscope.domain.conversation.event.ConversationParticipantChanged;
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
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
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
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Coordinates Conversation commands, visibility-aware queries and durable publication facts. */
public final class ConversationApplicationService {

  private static final String CONVERSATION_AGGREGATE = "CONVERSATION";
  private static final String PARTICIPANT_AGGREGATE = "CONVERSATION_PARTICIPANT";
  private static final String CREATE_CONVERSATION = "CREATE_CONVERSATION";
  private static final String ADD_PARTICIPANT = "ADD_CONVERSATION_PARTICIPANT";
  private static final String REMOVE_PARTICIPANT = "REMOVE_CONVERSATION_PARTICIPANT";
  private static final String POST_USER_MESSAGE = "POST_CONVERSATION_USER_MESSAGE";

  private final ConversationRepository conversationRepository;
  private final ConversationParticipantRepository participantRepository;
  private final MessageRepository messageRepository;
  private final ConversationEventRepository conversationEventRepository;
  private final TeamRepository teamRepository;
  private final WorkspaceRepository workspaceRepository;
  private final TeamMembershipQuery membershipQuery;
  private final PrincipalRepository principalRepository;
  private final AgentProfileRepository agentProfileRepository;
  private final TeamRoleRepository teamRoleRepository;
  private final MemberRoleRepository memberRoleRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;
  private final ConversationVisibilityPolicy visibilityPolicy;

  public ConversationApplicationService(
      ConversationRepository conversationRepository,
      ConversationParticipantRepository participantRepository,
      MessageRepository messageRepository,
      ConversationEventRepository conversationEventRepository,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMembershipQuery membershipQuery,
      PrincipalRepository principalRepository,
      AgentProfileRepository agentProfileRepository,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider,
      ConversationVisibilityPolicy visibilityPolicy) {
    this.conversationRepository =
        Objects.requireNonNull(conversationRepository, "conversationRepository");
    this.participantRepository =
        Objects.requireNonNull(participantRepository, "participantRepository");
    this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
    this.conversationEventRepository =
        Objects.requireNonNull(conversationEventRepository, "conversationEventRepository");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.agentProfileRepository =
        Objects.requireNonNull(agentProfileRepository, "agentProfileRepository");
    this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
    this.memberRoleRepository = Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    this.visibilityPolicy = Objects.requireNonNull(visibilityPolicy, "visibilityPolicy");
  }

  /** Starts a Conversation with server-resolved owner, Workspace and default Personal Agent. */
  public CommandExecution<PersonalConversationInitialization> create(
      TeamCommandContext context, TeamId teamId, CreateConversationCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    CreateConversationCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash requestHash =
        CommandRequestHash.sha256(
            CREATE_CONVERSATION,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            trusted.causationId().map(UUID::toString).orElse(""),
            required.title(),
            required.visibility().name());
    return execute(
        trusted,
        CREATE_CONVERSATION,
        requestHash,
        commandId -> createInTransaction(trusted, commandId, requiredTeamId, required));
  }

  /** Lists only Conversations discoverable by the current active Team member. */
  public ConversationPage list(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<ConversationStatus> status,
      Optional<ConversationListCursor> cursor,
      int limit) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    return transactionExecutor.required(
        () -> {
          // Membership validation and the page read share one transaction snapshot.
          Team team = requireTeam(organizationId, teamId);
          TeamMember member = requireActiveMember(trusted.actor(), team);
          ConversationPage page =
              conversationRepository.findPage(
                  new ConversationQuery(
                      organizationId,
                      teamId,
                      trusted.actor().id(),
                      Optional.empty(),
                      Objects.requireNonNull(status, "status"),
                      Objects.requireNonNull(cursor, "cursor"),
                      limit));
          List<Conversation> visible =
              page.conversations().stream()
                  .filter(
                      conversation ->
                          access(conversation, member, trusted.actor()).discoverable())
                  .toList();
          return new ConversationPage(visible, page.nextCursor());
        });
  }

  /** Returns one visible Conversation and all of its participant lifecycle facts. */
  public ConversationDetails get(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationId conversationId) {
    return transactionExecutor.required(
        () -> {
          AccessSnapshot snapshot =
              requireReadable(context, organizationId, teamId, conversationId);
          return new ConversationDetails(
              snapshot.conversation(), participantViews(snapshot));
        });
  }

  /** Resolves participant and owner names in bounded batch reads inside the detail snapshot. */
  private List<ConversationParticipantView> participantViews(AccessSnapshot snapshot) {
    OrganizationId organizationId = snapshot.conversation().scope().organizationId();
    LinkedHashSet<PrincipalId> participantIds =
        snapshot.participants().stream()
            .map(ConversationParticipant::principalId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<PrincipalId, Principal> directory =
        principalRepository.findByIds(organizationId, participantIds).stream()
            .collect(Collectors.toMap(Principal::id, Function.identity()));
    LinkedHashSet<PrincipalId> missingOwnerIds =
        directory.values().stream()
            .map(Principal::ownerPrincipalId)
            .flatMap(Optional::stream)
            .filter(ownerId -> !directory.containsKey(ownerId))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    principalRepository.findByIds(organizationId, missingOwnerIds).stream()
        .forEach(principal -> directory.putIfAbsent(principal.id(), principal));
    return snapshot.participants().stream()
        .map(
            participant -> {
              Principal participantPrincipal =
                  Optional.ofNullable(directory.get(participant.principalId()))
                      .orElseThrow(
                          () ->
                              new AggregateNotFoundException(
                                  "Principal", participant.principalId()));
              Optional<Principal> owner =
                  participantPrincipal
                      .ownerPrincipalId()
                      .map(
                          ownerId ->
                              Optional.ofNullable(directory.get(ownerId))
                                  .orElseThrow(
                                      () ->
                                          new AggregateNotFoundException(
                                              "Principal", ownerId)));
              return new ConversationParticipantView(
                  participant, participantPrincipal, owner);
            })
        .toList();
  }

  /** Returns descending committed history within the caller's current visibility cutoff. */
  public MessagePage messages(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationId conversationId,
      Optional<ConversationMessageCursor> cursor,
      int limit) {
    return transactionExecutor.required(
        () -> {
          AccessSnapshot snapshot =
              requireReadable(context, organizationId, teamId, conversationId);
          return messageRepository.findPage(
              new MessageHistoryQuery(
                  snapshot.conversation().scope(),
                  snapshot.conversation().id(),
                  snapshot.decision().historyVisibleThrough(),
                  Objects.requireNonNull(cursor, "cursor"),
                  limit));
        });
  }

  /** Resolves one exact source Message while applying PRIVATE history cutoffs and current access. */
  public ReadableConversationMessage requireReadableMessage(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationId conversationId,
      MessageId messageId) {
    return transactionExecutor.required(
        () -> {
          AccessSnapshot snapshot =
              requireReadable(context, organizationId, teamId, conversationId);
          Message message =
              messageRepository
                  .findById(organizationId, Objects.requireNonNull(messageId, "messageId"))
                  .filter(snapshot.decision()::canRead)
                  .orElseThrow(() -> new AggregateNotFoundException("Message", messageId));
          return new ReadableConversationMessage(snapshot.conversation(), message);
        });
  }

  /** Returns the next visible durable events in canonical stream order. */
  public ConversationEventPage events(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationId conversationId,
      Optional<ConversationEventCursor> cursor,
      int limit) {
    return transactionExecutor.required(
        () -> {
          AccessSnapshot snapshot =
              requireReadable(context, organizationId, teamId, conversationId);
          return conversationEventRepository.findPage(
              new ConversationEventQuery(
                  snapshot.conversation().scope(),
                  snapshot.conversation().id(),
                  snapshot.decision().historyVisibleThrough(),
                  Objects.requireNonNull(cursor, "cursor"),
                  limit));
        });
  }

  /** Adds a current Team member or reactivates its stable Participant identity. */
  public CommandExecution<ConversationParticipant> addParticipant(
      TeamCommandContext context,
      TeamId teamId,
      ConversationId conversationId,
      AddConversationParticipantCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    ConversationId requiredConversationId =
        Objects.requireNonNull(conversationId, "conversationId");
    AddConversationParticipantCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash requestHash =
        CommandRequestHash.sha256(
            ADD_PARTICIPANT,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            requiredConversationId.toString(),
            trusted.causationId().map(UUID::toString).orElse(""),
            required.userPrincipalId().toString());
    return execute(
        trusted,
        ADD_PARTICIPANT,
        requestHash,
        commandId ->
            addParticipantInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredConversationId,
                required));
  }

  /** Removes a normal Participant when invoked by the owner or by that Participant itself. */
  public CommandExecution<ConversationParticipant> removeParticipant(
      TeamCommandContext context,
      TeamId teamId,
      ConversationId conversationId,
      ConversationParticipantId participantId) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    ConversationId requiredConversationId =
        Objects.requireNonNull(conversationId, "conversationId");
    ConversationParticipantId requiredParticipantId =
        Objects.requireNonNull(participantId, "participantId");
    CommandRequestHash requestHash =
        CommandRequestHash.sha256(
            REMOVE_PARTICIPANT,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            requiredConversationId.toString(),
            requiredParticipantId.toString(),
            trusted.causationId().map(UUID::toString).orElse(""));
    return execute(
        trusted,
        REMOVE_PARTICIPANT,
        requestHash,
        commandId ->
            removeParticipantInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredConversationId,
                requiredParticipantId));
  }

  /** Appends one USER_MESSAGE with a server-resolved Participant and monotonic sequence. */
  public CommandExecution<ConversationMessageAppend> postUserMessage(
      TeamCommandContext context,
      TeamId teamId,
      ConversationId conversationId,
      PostConversationMessageCommand command) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    ConversationId requiredConversationId =
        Objects.requireNonNull(conversationId, "conversationId");
    PostConversationMessageCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash requestHash =
        CommandRequestHash.sha256(
            POST_USER_MESSAGE,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            requiredConversationId.toString(),
            trusted.causationId().map(UUID::toString).orElse(""),
            required.content().markdown());
    return execute(
        trusted,
        POST_USER_MESSAGE,
        requestHash,
        commandId ->
            postUserMessageInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredConversationId,
                required));
  }

  /**
   * Commits one validated Personal Agent reply before its execution segment can finish publicly.
   *
   * <p>The stable Invocation/Segment client key and locked Conversation make runtime callback
   * retries idempotent without creating a user-facing CommandReceipt.
   */
  public io.crewscope.domain.conversation.Message commitAgentMessage(
      AgentMessageCandidate candidate,
      OrganizationId organizationId,
      UUID correlationId,
      Optional<UUID> causationDomainEventId) {
    AgentMessageCandidate required = Objects.requireNonNull(candidate, "candidate");
    OrganizationId requiredOrganizationId =
        Objects.requireNonNull(organizationId, "organizationId");
    UUID requiredCorrelationId = Objects.requireNonNull(correlationId, "correlationId");
    Optional<UUID> requiredCausationId =
        Objects.requireNonNull(causationDomainEventId, "causationDomainEventId");
    return transactionExecutor.required(
        () -> commitAgentMessageInTransaction(
            required, requiredOrganizationId, requiredCorrelationId, requiredCausationId));
  }

  private CommandExecution<PersonalConversationInitialization> createInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      CreateConversationCommand command) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireTeam(organizationId, teamId);
    TeamMember member = requireActiveMember(actor, team);
    Workspace workspace =
        workspaceRepository
            .findById(organizationId, team.defaultWorkspaceId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Workspace", team.defaultWorkspaceId()));
    AgentProfile profile =
        agentProfileRepository
            .findActiveDefaultPersonal(organizationId, member.id())
            .orElseThrow(
                () ->
                    new DomainValidationException(
                        "conversation.personalAgentPrincipalId",
                        "active default Personal Agent is required"));
    Principal agent =
        principalRepository
            .findById(organizationId, profile.agentPrincipalId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Principal", profile.agentPrincipalId()));
    PersonalAgentInitialization personalAgent =
        new PersonalAgentInitialization(agent, profile).requireDefaultFor(member, workspace);
    UtcTimestamp occurredAt = timeProvider.now();
    PersonalConversationInitialization candidate =
        PersonalConversationInitialization.start(
            ConversationId.generate(),
            workspace,
            member,
            actor,
            personalAgent,
            command.title(),
            command.visibility(),
            occurredAt);
    Conversation committedConversation =
        conversationRepository.create(candidate.conversation());
    ConversationParticipant committedOwner =
        participantRepository.create(candidate.ownerParticipant());
    ConversationParticipant committedAgent =
        participantRepository.create(candidate.agentParticipant());
    PersonalConversationInitialization committed =
        new PersonalConversationInitialization(
            committedConversation, committedOwner, committedAgent);
    return completed(
        context,
        commandId,
        committed,
        EventType.from("CONVERSATION_CREATED"),
        CONVERSATION_AGGREGATE,
        committedConversation.id(),
        committedConversation.version(),
        ConversationCreated.from(committed),
        occurredAt);
  }

  private CommandExecution<ConversationParticipant> addParticipantInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      ConversationId conversationId,
      AddConversationParticipantCommand command) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireTeam(organizationId, teamId);
    TeamMember caller = requireActiveMember(actor, team);
    Conversation conversation =
        requireLockedConversation(organizationId, teamId, conversationId);
    requireActiveConversation(conversation);
    requireOwnerManager(actor, caller, conversation, timeProvider.now());
    TeamMember targetMember =
        membershipQuery.findByTeam(organizationId, teamId).stream()
            .filter(TeamMember::canParticipate)
            .filter(member -> member.userPrincipalId().equals(command.userPrincipalId()))
            .findFirst()
            .orElseThrow(() -> new PolicyDeniedException("add this Conversation participant"));
    Principal targetUser =
        principalRepository
            .findById(organizationId, command.userPrincipalId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Principal", command.userPrincipalId()));
    UtcTimestamp occurredAt = timeProvider.now();
    Optional<ConversationParticipant> existing =
        participantRepository.findByConversation(organizationId, conversationId).stream()
            .filter(value -> value.principalId().equals(command.userPrincipalId()))
            .findFirst();
    ConversationParticipant committed;
    EventType eventType;
    if (existing.isPresent()) {
      ConversationParticipant current = existing.orElseThrow();
      if (current.status() != ConversationParticipantStatus.LEFT) {
        throw new DomainValidationException(
            "conversationParticipant.principalId", "is already an active participant");
      }
      committed =
          participantRepository.update(
              current.reactivateMember(
                  conversation, targetMember, targetUser, actor, occurredAt));
      eventType = EventType.from("CONVERSATION_PARTICIPANT_REACTIVATED");
    } else {
      committed =
          participantRepository.create(
              ConversationParticipant.joinMember(
                  conversation, targetMember, targetUser, actor, occurredAt));
      eventType = EventType.from("CONVERSATION_PARTICIPANT_JOINED");
    }
    return completed(
        context,
        commandId,
        committed,
        eventType,
        PARTICIPANT_AGGREGATE,
        committed.id(),
        committed.version(),
        ConversationParticipantChanged.from(committed),
        occurredAt);
  }

  private CommandExecution<ConversationParticipant> removeParticipantInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      ConversationId conversationId,
      ConversationParticipantId participantId) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireTeam(organizationId, teamId);
    TeamMember caller = requireActiveMember(actor, team);
    Conversation conversation =
        requireLockedConversation(organizationId, teamId, conversationId);
    requireActiveConversation(conversation);
    ConversationParticipant participant =
        participantRepository
            .findById(organizationId, participantId)
            .filter(value -> value.conversationId().equals(conversationId))
            .filter(value -> value.scope().equals(conversation.scope()))
            .orElseThrow(
                () -> new AggregateNotFoundException("ConversationParticipant", participantId));
    boolean self = participant.principalId().equals(actor.id());
    if (!self) {
      requireOwnerManager(actor, caller, conversation, timeProvider.now());
    }
    UtcTimestamp occurredAt = timeProvider.now();
    ConversationParticipant committed =
        participantRepository.update(participant.leave(actor, occurredAt));
    return completed(
        context,
        commandId,
        committed,
        EventType.from("CONVERSATION_PARTICIPANT_LEFT"),
        PARTICIPANT_AGGREGATE,
        committed.id(),
        committed.version(),
        ConversationParticipantChanged.from(committed),
        occurredAt);
  }

  private CommandExecution<ConversationMessageAppend> postUserMessageInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      ConversationId conversationId,
      PostConversationMessageCommand command) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireTeam(organizationId, teamId);
    TeamMember member = requireActiveMember(actor, team);
    Conversation conversation =
        requireLockedConversation(organizationId, teamId, conversationId);
    List<ConversationParticipant> participants =
        participantRepository.findByConversation(organizationId, conversationId);
    Optional<ConversationParticipant> participation =
        participants.stream()
            .filter(value -> value.principalId().equals(actor.id()))
            .findFirst();
    ConversationAccessDecision decision =
        visibilityPolicy.forMember(conversation, member, actor, participation);
    if (!decision.readable()) {
      throw new AggregateNotFoundException("Conversation", conversationId);
    }
    // Check visibility before state so a hidden PRIVATE Conversation cannot be probed.
    requireActiveConversation(conversation);
    if (!decision.writable()) {
      throw new PolicyDeniedException("post messages to this Conversation");
    }
    UtcTimestamp occurredAt = timeProvider.now();
    ConversationMessageAppend candidate =
        conversation.appendMessage(
            MessageId.generate(),
            participation.orElseThrow(),
            actor,
            command.content(),
            occurredAt);
    Conversation committedConversation =
        conversationRepository.update(candidate.conversation());
    io.crewscope.domain.conversation.Message committedMessage =
        messageRepository.create(
            candidate.message(), Optional.of(context.idempotencyKey().value()));
    ConversationMessageAppend committed =
        new ConversationMessageAppend(committedConversation, committedMessage);
    return completed(
        context,
        commandId,
        committed,
        EventType.from("CONVERSATION_MESSAGE_POSTED"),
        CONVERSATION_AGGREGATE,
        committedConversation.id(),
        committedConversation.version(),
        ConversationMessagePosted.from(committedMessage),
        occurredAt);
  }

  private io.crewscope.domain.conversation.Message commitAgentMessageInTransaction(
      AgentMessageCandidate candidate,
      OrganizationId organizationId,
      UUID correlationId,
      Optional<UUID> causationDomainEventId) {
    String clientMessageKey =
        "agent:" + candidate.invocationId() + ":" + candidate.segmentId();
    Conversation conversation =
        conversationRepository
            .lockById(organizationId, candidate.conversationId())
            .orElseThrow(
                () ->
                    new AggregateNotFoundException(
                        "Conversation", candidate.conversationId()));
    Optional<io.crewscope.domain.conversation.Message> existing =
        messageRepository.findByClientMessageKey(
            conversation.scope().organizationId(), conversation.id(), clientMessageKey);
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    requireActiveConversation(conversation);
    if (!conversation.personalAgentPrincipalId().equals(candidate.authorPrincipalId())) {
      throw new PolicyDeniedException("post this Personal Agent reply");
    }
    ConversationParticipant participant =
        participantRepository
            .findById(conversation.scope().organizationId(), candidate.participantId())
            .filter(ConversationParticipant::isActive)
            .filter(value -> value.conversationId().equals(conversation.id()))
            .filter(value -> value.scope().equals(conversation.scope()))
            .filter(value -> value.principalId().equals(candidate.authorPrincipalId()))
            .filter(
                value ->
                    value.role()
                        == io.crewscope.domain.conversation.ConversationParticipantRole.AGENT)
            .orElseThrow(
                () ->
                    new AggregateNotFoundException(
                        "ConversationParticipant", candidate.participantId()));
    Principal agent =
        principalRepository
            .findById(conversation.scope().organizationId(), candidate.authorPrincipalId())
            .filter(Principal::canAct)
            .filter(value -> value.type() == PrincipalType.PERSONAL_AGENT)
            .orElseThrow(
                () ->
                    new AggregateNotFoundException(
                        "Principal", candidate.authorPrincipalId()));
    ConversationMessageAppend append =
        conversation.appendMessage(
            MessageId.generate(), participant, agent, candidate.content(), candidate.occurredAt());
    Conversation committedConversation = conversationRepository.update(append.conversation());
    io.crewscope.domain.conversation.Message committedMessage =
        messageRepository.create(append.message(), Optional.of(clientMessageKey));
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            EventType.from("CONVERSATION_MESSAGE_POSTED"),
            SchemaVersion.V1,
            committedConversation.scope().organizationId(),
            Optional.of(committedConversation.scope().teamId()),
            Optional.of(committedConversation.scope().workspaceId()),
            AggregateReference.of(CONVERSATION_AGGREGATE, committedConversation.id()),
            committedConversation.version(),
            EventActor.principal(EventActorType.PERSONAL_AGENT, agent.id()),
            correlationId,
            causationDomainEventId,
            Optional.of(clientMessageKey),
            candidate.occurredAt(),
            ConversationMessagePosted.from(committedMessage));
    domainEventStore.append(event);
    conversationEventRepository.append(committedConversation.id(), event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    return committedMessage;
  }


  private AccessSnapshot requireReadable(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationId conversationId) {
    TeamAccessContext trusted = requireAccess(context, organizationId);
    Team team = requireTeam(organizationId, teamId);
    TeamMember member = requireActiveMember(trusted.actor(), team);
    Conversation conversation = requireConversation(organizationId, teamId, conversationId);
    List<ConversationParticipant> participants =
        participantRepository.findByConversation(organizationId, conversationId);
    ConversationAccessDecision decision =
        visibilityPolicy.forMember(
            conversation,
            member,
            trusted.actor(),
            participants.stream()
                .filter(value -> value.principalId().equals(trusted.actor().id()))
                .findFirst());
    if (!decision.readable()) {
      throw new AggregateNotFoundException("Conversation", conversationId);
    }
    return new AccessSnapshot(conversation, participants, decision);
  }

  private ConversationAccessDecision access(
      Conversation conversation, TeamMember member, Principal actor) {
    Optional<ConversationParticipant> participant =
        participantRepository
            .findByConversation(conversation.scope().organizationId(), conversation.id())
            .stream()
            .filter(value -> value.principalId().equals(actor.id()))
            .findFirst();
    return visibilityPolicy.forMember(conversation, member, actor, participant);
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
      EventType eventType,
      String aggregateType,
      AggregateId aggregateId,
      long aggregateVersion,
      DomainEvent payload,
      UtcTimestamp occurredAt) {
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            eventType,
            SchemaVersion.V1,
            context.access().actor().scope().organizationId(),
            Optional.of(resultScope(result).teamId()),
            Optional.of(resultScope(result).workspaceId()),
            AggregateReference.of(aggregateType, aggregateId),
            aggregateVersion,
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            payload);
    domainEventStore.append(event);
    conversationEventRepository.append(resultConversationId(result), event);
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

  private static io.crewscope.domain.conversation.ConversationScope resultScope(Object result) {
    if (result instanceof PersonalConversationInitialization initialization) {
      return initialization.conversation().scope();
    }
    if (result instanceof ConversationParticipant participant) {
      return participant.scope();
    }
    if (result instanceof ConversationMessageAppend append) {
      return append.conversation().scope();
    }
    throw new IllegalArgumentException("Unsupported Conversation command result");
  }

  private static ConversationId resultConversationId(Object result) {
    if (result instanceof PersonalConversationInitialization initialization) {
      return initialization.conversation().id();
    }
    if (result instanceof ConversationParticipant participant) {
      return participant.conversationId();
    }
    if (result instanceof ConversationMessageAppend append) {
      return append.conversation().id();
    }
    throw new IllegalArgumentException("Unsupported Conversation command result");
  }

  private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
    if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
      throw new DomainValidationException("team.initializationStatus", "must be READY");
    }
    return teamRepository
        .findById(organizationId, teamId)
        .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
  }

  private Conversation requireConversation(
      OrganizationId organizationId, TeamId teamId, ConversationId conversationId) {
    return conversationRepository
        .findById(organizationId, conversationId)
        .filter(value -> value.scope().teamId().equals(teamId))
        .orElseThrow(() -> new AggregateNotFoundException("Conversation", conversationId));
  }

  private Conversation requireLockedConversation(
      OrganizationId organizationId, TeamId teamId, ConversationId conversationId) {
    return conversationRepository
        .lockById(organizationId, conversationId)
        .filter(value -> value.scope().teamId().equals(teamId))
        .orElseThrow(() -> new AggregateNotFoundException("Conversation", conversationId));
  }

  private TeamMember requireActiveMember(Principal actor, Team team) {
    requireActiveUserInOrganization(actor, team.organizationId());
    return membershipQuery.findByTeam(team.organizationId(), team.id()).stream()
        .filter(member -> member.userPrincipalId().equals(actor.id()))
        .filter(TeamMember::canParticipate)
        .findFirst()
        .orElseThrow(() -> new PolicyDeniedException("access this Team's Conversations"));
  }

  private void requireOwnerManager(
      Principal actor, TeamMember member, Conversation conversation, UtcTimestamp occurredAt) {
    if (!conversation.ownerPrincipalId().equals(actor.id())) {
      throw new PolicyDeniedException("manage this Conversation's participants");
    }
    requirePermission(member, TeamPermission.COLLABORATION_REQUEST, occurredAt);
  }

  private void requirePermission(
      TeamMember member, TeamPermission permission, UtcTimestamp occurredAt) {
    Map<TeamRoleId, TeamRole> roles =
        teamRoleRepository.findByTeam(member.scope().organizationId(), member.scope().teamId())
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
      throw new PolicyDeniedException("manage this Conversation's participants");
    }
  }

  private static void requireActiveConversation(Conversation conversation) {
    if (conversation.status() != ConversationStatus.ACTIVE) {
      throw new DomainValidationException(
          "conversation.status", "must be ACTIVE for mutation");
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

  private record AccessSnapshot(
      Conversation conversation,
      List<ConversationParticipant> participants,
      ConversationAccessDecision decision) {

    private AccessSnapshot {
      conversation = Objects.requireNonNull(conversation, "conversation");
      participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
      decision = Objects.requireNonNull(decision, "decision");
    }
  }
}
