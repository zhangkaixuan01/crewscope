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
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.TaskIntentOutputCandidate;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantRole;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentCandidate;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.conversation.event.TaskIntentProposed;
import io.crewscope.domain.conversation.event.TaskIntentRejected;
import io.crewscope.domain.conversation.event.TaskIntentRevised;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Coordinates TaskIntent model candidates, human review commands and confirmation preflight. */
public final class TaskIntentApplicationService {

  private static final String TASK_INTENT_AGGREGATE = "TASK_INTENT";
  private static final String REVISE_TASK_INTENT = "REVISE_TASK_INTENT";
  private static final String REJECT_TASK_INTENT = "REJECT_TASK_INTENT";
  private static final String CANDIDATE_NAMESPACE = "crewscope:task-intent-candidate:v1:";

  private final ConversationApplicationService conversationService;
  private final ConversationRepository conversationRepository;
  private final ConversationParticipantRepository participantRepository;
  private final ConversationEventRepository conversationEventRepository;
  private final TaskIntentRepository taskIntentRepository;
  private final WorkProjectRepository workProjectRepository;
  private final TeamMembershipQuery membershipQuery;
  private final PrincipalRepository principalRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public TaskIntentApplicationService(
      ConversationApplicationService conversationService,
      ConversationRepository conversationRepository,
      ConversationParticipantRepository participantRepository,
      ConversationEventRepository conversationEventRepository,
      TaskIntentRepository taskIntentRepository,
      WorkProjectRepository workProjectRepository,
      TeamMembershipQuery membershipQuery,
      PrincipalRepository principalRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
    this.conversationRepository =
        Objects.requireNonNull(conversationRepository, "conversationRepository");
    this.participantRepository =
        Objects.requireNonNull(participantRepository, "participantRepository");
    this.conversationEventRepository =
        Objects.requireNonNull(conversationEventRepository, "conversationEventRepository");
    this.taskIntentRepository = Objects.requireNonNull(taskIntentRepository, "taskIntentRepository");
    this.workProjectRepository =
        Objects.requireNonNull(workProjectRepository, "workProjectRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** Persists one Bean-validated model output exactly once before RUN_FINISHED is published. */
  public TaskIntent commitAgentProposal(
      TaskIntentOutputCandidate candidate,
      PlatformExecutionContext platformContext,
      Optional<UUID> causationDomainEventId) {
    TaskIntentOutputCandidate required = Objects.requireNonNull(candidate, "candidate");
    PlatformExecutionContext platform = Objects.requireNonNull(platformContext, "platformContext");
    Optional<UUID> causation =
        Objects.requireNonNull(causationDomainEventId, "causationDomainEventId");
    return transactionExecutor.required(
        () -> commitAgentProposalInTransaction(required, platform, causation));
  }

  /** Returns a TaskIntent only through its readable nested Conversation resource. */
  public TaskIntent get(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationIdAndTaskIntentId target) {
    return transactionExecutor.required(
        () -> {
          Conversation conversation = readableConversation(context, organizationId, teamId, target);
          return requireTaskIntent(conversation, target.taskIntentId(), false);
        });
  }

  /** Replaces the complete proposal, revalidates current facts and returns it to READY. */
  public CommandExecution<TaskIntent> revise(
      TeamCommandContext context,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      ReviseTaskIntentCommand command,
      long expectedVersion) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    ReviseTaskIntentCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash requestHash =
        proposalHash(REVISE_TASK_INTENT, trusted, teamId, target, required.proposal(), expectedVersion);
    return execute(
        trusted,
        REVISE_TASK_INTENT,
        requestHash,
        commandId ->
            reviseInTransaction(
                trusted, commandId, teamId, target, required.proposal(), expectedVersion));
  }

  /** Permanently rejects an editable proposal through its current proposed Owner. */
  public CommandExecution<TaskIntent> reject(
      TeamCommandContext context,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      RejectTaskIntentCommand command,
      long expectedVersion) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    RejectTaskIntentCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash requestHash =
        CommandRequestHash.sha256(
            REJECT_TASK_INTENT,
            trusted.access().actor().id().toString(),
            Objects.requireNonNull(teamId, "teamId").toString(),
            Objects.requireNonNull(target, "target").conversationId().toString(),
            target.taskIntentId().toString(),
            Long.toString(expectedVersion),
            trusted.causationId().map(UUID::toString).orElse(""),
            required.reason());
    return execute(
        trusted,
        REJECT_TASK_INTENT,
        requestHash,
        commandId ->
            rejectInTransaction(
                trusted, commandId, teamId, target, required.reason(), expectedVersion));
  }

  /** Rebuilds all responsibility facts and proves that the current Owner may confirm in A07. */
  public TaskIntentConfirmationPreview previewConfirmation(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      long expectedVersion) {
    return transactionExecutor.required(
        () -> {
          Conversation conversation = readableConversation(context, organizationId, teamId, target);
          TaskIntent intent = requireTaskIntent(conversation, target.taskIntentId(), false);
          TaskIntentProposal current = resolveCurrentProposal(conversation, intent.proposal());
          // The discarded transition validates version, READY state, proposal equality and Owner.
          intent.confirm(expectedVersion, current, context.actor(), timeProvider.now());
          return new TaskIntentConfirmationPreview(intent, current, context.actor().id());
        });
  }

  private TaskIntent commitAgentProposalInTransaction(
      TaskIntentOutputCandidate candidate,
      PlatformExecutionContext platform,
      Optional<UUID> causationDomainEventId) {
    requireCandidateContext(candidate, platform);
    TaskIntentId taskIntentId = stableTaskIntentId(candidate);
    Optional<TaskIntent> existing =
        taskIntentRepository.findById(platform.scope().organizationId(), taskIntentId);
    if (existing.isPresent()) {
      return requireMatchingCandidate(existing.orElseThrow(), candidate, platform);
    }
    Conversation conversation =
        conversationRepository
            .lockById(platform.scope().organizationId(), candidate.conversationId())
            .filter(value -> value.scope().equals(platform.scope()))
            .orElseThrow(
                () -> new AggregateNotFoundException("Conversation", candidate.conversationId()));
    // A concurrent transaction may have committed the same stable candidate while this one waited
    // for the Conversation lock. Re-read before insert so identical submissions remain idempotent.
    existing = taskIntentRepository.findById(platform.scope().organizationId(), taskIntentId);
    if (existing.isPresent()) {
      return requireMatchingCandidate(existing.orElseThrow(), candidate, platform);
    }
    ConversationParticipant agentParticipant =
        participantRepository
            .findById(platform.scope().organizationId(), platform.agentParticipantId())
            .filter(ConversationParticipant::isActive)
            .filter(value -> value.scope().equals(conversation.scope()))
            .filter(value -> value.conversationId().equals(conversation.id()))
            .filter(value -> value.role() == ConversationParticipantRole.AGENT)
            .filter(value -> value.principalId().equals(platform.personalAgentPrincipalId()))
            .orElseThrow(
                () ->
                    new AggregateNotFoundException(
                        "ConversationParticipant", platform.agentParticipantId()));
    Principal agent =
        requirePrincipal(platform.scope().organizationId(), platform.personalAgentPrincipalId());
    if (!agent.type().isAgent()) {
      throw new PolicyDeniedException("propose a TaskIntent as this Principal");
    }
    TaskIntentProposal proposal = resolveProposal(conversation, candidate.output());
    TaskIntent draft =
        TaskIntent.draft(
            taskIntentId,
            conversation,
            agentParticipant,
            agent,
            proposal,
            candidate.occurredAt());
    taskIntentRepository.create(draft);
    TaskIntent ready =
        taskIntentRepository.update(
            draft.markReady(draft.version(), agent, candidate.occurredAt()));
    appendEvent(
        ready,
        EventType.from("TASK_INTENT_PROPOSED"),
        EventActor.principal(eventActorType(agent), agent.id()),
        platform.correlationId(),
        causationDomainEventId,
        Optional.of("agent:" + candidate.invocationId() + ":" + candidate.segmentId()),
        candidate.occurredAt(),
        TaskIntentProposed.from(ready));
    return ready;
  }

  private CommandExecution<TaskIntent> reviseInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      TaskIntentV1 output,
      long expectedVersion) {
    Conversation conversation =
        readableConversation(
            context.access(),
            context.access().actor().scope().organizationId(),
            teamId,
            target);
    TaskIntent before = requireTaskIntent(conversation, target.taskIntentId(), true);
    requireProposedOwner(before, context.access().actor());
    TaskIntentProposal replacement = resolveProposal(conversation, output);
    UtcTimestamp occurredAt = timeProvider.now();
    TaskIntent draft = before.revise(replacement, expectedVersion, context.access().actor(), occurredAt);
    taskIntentRepository.update(draft);
    TaskIntent committed =
        taskIntentRepository.update(
            draft.markReady(draft.version(), context.access().actor(), occurredAt));
    return completeCommand(
        context,
        commandId,
        committed,
        EventType.from("TASK_INTENT_REVISED"),
        TaskIntentRevised.from(before, committed),
        occurredAt);
  }

  private CommandExecution<TaskIntent> rejectInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      String reason,
      long expectedVersion) {
    Conversation conversation =
        readableConversation(
            context.access(),
            context.access().actor().scope().organizationId(),
            teamId,
            target);
    TaskIntent current = requireTaskIntent(conversation, target.taskIntentId(), true);
    requireProposedOwner(current, context.access().actor());
    UtcTimestamp occurredAt = timeProvider.now();
    TaskIntent committed =
        taskIntentRepository.update(
            current.reject(expectedVersion, context.access().actor(), reason, occurredAt));
    return completeCommand(
        context,
        commandId,
        committed,
        EventType.from("TASK_INTENT_REJECTED"),
        TaskIntentRejected.from(committed),
        occurredAt);
  }

  private Conversation readableConversation(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationIdAndTaskIntentId target) {
    return conversationService
        .get(
            Objects.requireNonNull(context, "context"),
            Objects.requireNonNull(organizationId, "organizationId"),
            Objects.requireNonNull(teamId, "teamId"),
            Objects.requireNonNull(target, "target").conversationId())
        .conversation();
  }

  private TaskIntent requireTaskIntent(
      Conversation conversation, TaskIntentId taskIntentId, boolean lock) {
    Optional<TaskIntent> found =
        lock
            ? taskIntentRepository.lockById(conversation.scope().organizationId(), taskIntentId)
            : taskIntentRepository.findById(conversation.scope().organizationId(), taskIntentId);
    return found
        .filter(value -> value.scope().equals(conversation.scope()))
        .filter(value -> value.conversationId().equals(conversation.id()))
        .orElseThrow(() -> new AggregateNotFoundException("TaskIntent", taskIntentId));
  }

  private TaskIntentProposal resolveProposal(Conversation conversation, TaskIntentV1 output) {
    TaskIntentV1 required = Objects.requireNonNull(output, "output");
    if (!TaskIntentV1.SCHEMA_VERSION.equals(required.schemaVersion())) {
      throw new io.crewscope.domain.shared.error.DomainValidationException(
          "taskIntent.schemaVersion", "must be 1");
    }
    WorkProject project =
        workProjectRepository
            .findById(
                conversation.scope().organizationId(), WorkProjectId.from(required.workProjectId()))
            .orElseThrow(
                () ->
                    new io.crewscope.domain.shared.error.DomainValidationException(
                        "taskIntent.workProjectId", "must reference a current WorkProject"));
    List<TeamMember> members =
        membershipQuery.findByTeam(
            conversation.scope().organizationId(), conversation.scope().teamId());
    TeamMember ownerMember =
        requireMember(members, TeamMemberId.from(required.ownerMemberId()), "ownerMemberId");
    Principal owner =
        requirePrincipal(conversation.scope().organizationId(), ownerMember.userPrincipalId());
    Optional<TaskIntentCandidate> executor =
        Optional.ofNullable(required.executorPrincipalId())
            .map(PrincipalId::from)
            .map(id -> requirePrincipal(conversation.scope().organizationId(), id))
            .map(principal -> candidate(principal, members));
    Optional<TaskIntentCandidate> reviewer =
        Optional.ofNullable(required.gateReviewerMemberId())
            .map(TeamMemberId::from)
            .map(id -> requireMember(members, id, "gateReviewerMemberId"))
            .map(
                member ->
                    TaskIntentCandidate.user(
                        requirePrincipal(
                            conversation.scope().organizationId(), member.userPrincipalId()),
                        member));
    return TaskIntentProposal.create(
        conversation,
        project,
        required.objective(),
        required.acceptanceCriteria(),
        TaskIntentCandidate.user(owner, ownerMember),
        executor,
        reviewer);
  }

  private TaskIntentProposal resolveCurrentProposal(
      Conversation conversation, TaskIntentProposal proposal) {
    TaskIntentProposal source = Objects.requireNonNull(proposal, "proposal");
    TaskIntentV1 output =
        new TaskIntentV1(
            TaskIntentV1.SCHEMA_VERSION,
            source.objective(),
            source.acceptanceCriteria(),
            source.targetScope().projectId().toString(),
            source.owner().memberId().orElseThrow().toString(),
            source.executor().map(value -> value.principalId().toString()).orElse(null),
            source.gateReviewer().flatMap(value -> value.memberId()).map(Object::toString).orElse(null));
    return resolveProposal(conversation, output);
  }

  private Principal requirePrincipal(OrganizationId organizationId, PrincipalId principalId) {
    return principalRepository
        .findById(organizationId, principalId)
        .orElseThrow(
            () ->
                new io.crewscope.domain.shared.error.DomainValidationException(
                    "taskIntent.responsibility.principalId",
                    "must reference a current Principal"));
  }

  private static TeamMember requireMember(
      List<TeamMember> members, TeamMemberId memberId, String field) {
    return members.stream()
        .filter(value -> value.id().equals(memberId))
        .findFirst()
        .orElseThrow(
            () ->
                new io.crewscope.domain.shared.error.DomainValidationException(
                    "taskIntent." + field, "must reference a current Team member"));
  }

  private static TaskIntentCandidate candidate(Principal principal, List<TeamMember> members) {
    if (principal.type() == PrincipalType.USER) {
      TeamMember member =
          members.stream()
              .filter(value -> value.userPrincipalId().equals(principal.id()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new io.crewscope.domain.shared.error.DomainValidationException(
                          "taskIntent.executorPrincipalId",
                          "must reference a current Team member or Team Agent"));
      return TaskIntentCandidate.user(principal, member);
    }
    return TaskIntentCandidate.agent(principal);
  }

  private static void requireProposedOwner(TaskIntent intent, Principal actor) {
    if (!intent.proposal().owner().principalId().equals(actor.id())) {
      throw new PolicyDeniedException("review this TaskIntent");
    }
  }

  private static void requireCandidateContext(
      TaskIntentOutputCandidate candidate, PlatformExecutionContext platform) {
    if (!candidate.invocationId().equals(platform.invocationId())
        || !candidate.conversationId().equals(platform.conversationId())) {
      throw new IllegalArgumentException("TaskIntent candidate must match its execution context");
    }
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

  private CommandExecution<TaskIntent> completeCommand(
      TeamCommandContext context,
      UUID commandId,
      TaskIntent committed,
      EventType eventType,
      DomainEvent payload,
      UtcTimestamp occurredAt) {
    DomainEventEnvelope<DomainEvent> event =
        appendEvent(
            committed,
            eventType,
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            payload);
    CommandReceipt receipt =
        new CommandReceipt(
            commandId, event.eventId(), committed.version(), context.correlationId());
    receiptStore.complete(
        committed.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
    return CommandExecution.completed(committed, receipt);
  }

  private DomainEventEnvelope<DomainEvent> appendEvent(
      TaskIntent intent,
      EventType eventType,
      EventActor actor,
      UUID correlationId,
      Optional<UUID> causationId,
      Optional<String> idempotencyKey,
      UtcTimestamp occurredAt,
      DomainEvent payload) {
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            UUID.randomUUID(),
            eventType,
            SchemaVersion.V1,
            intent.scope().organizationId(),
            Optional.of(intent.scope().teamId()),
            Optional.of(intent.scope().workspaceId()),
            AggregateReference.of(TASK_INTENT_AGGREGATE, intent.id()),
            intent.version(),
            actor,
            correlationId,
            causationId,
            idempotencyKey,
            occurredAt,
            payload);
    domainEventStore.append(event);
    conversationEventRepository.append(intent.conversationId(), event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    return event;
  }

  private static CommandRequestHash proposalHash(
      String commandType,
      TeamCommandContext context,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      TaskIntentV1 proposal,
      long expectedVersion) {
    List<String> fields = new ArrayList<>();
    fields.add(context.access().actor().id().toString());
    fields.add(Objects.requireNonNull(teamId, "teamId").toString());
    fields.add(Objects.requireNonNull(target, "target").conversationId().toString());
    fields.add(target.taskIntentId().toString());
    fields.add(Long.toString(expectedVersion));
    fields.add(context.causationId().map(UUID::toString).orElse(""));
    fields.add(proposal.schemaVersion().strip());
    fields.add(proposal.objective().strip());
    proposal.acceptanceCriteria().stream().map(String::strip).forEach(fields::add);
    fields.add(proposal.workProjectId().strip());
    fields.add(proposal.ownerMemberId().strip());
    fields.add(Optional.ofNullable(proposal.executorPrincipalId()).orElse(""));
    fields.add(Optional.ofNullable(proposal.gateReviewerMemberId()).orElse(""));
    return CommandRequestHash.sha256(commandType, fields.toArray(String[]::new));
  }

  private static TaskIntentId stableTaskIntentId(TaskIntentOutputCandidate candidate) {
    String source = CANDIDATE_NAMESPACE + candidate.invocationId() + ':' + candidate.segmentId();
    return new TaskIntentId(UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
  }

  private static EventActorType eventActorType(Principal principal) {
    return switch (principal.type()) {
      case PERSONAL_AGENT -> EventActorType.PERSONAL_AGENT;
      case TEAM_AGENT -> EventActorType.TEAM_AGENT;
      case SPECIALIST_AGENT -> EventActorType.SPECIALIST_AGENT;
      default -> throw new IllegalArgumentException("TaskIntent proposer must be an Agent");
    };
  }

  private static boolean matchesCandidate(TaskIntent intent, TaskIntentV1 output) {
    TaskIntentProposal proposal = intent.proposal();
    return TaskIntentV1.SCHEMA_VERSION.equals(output.schemaVersion())
        && proposal.objective().equals(output.objective().strip())
        && proposal.acceptanceCriteria().equals(output.acceptanceCriteria().stream().map(String::strip).toList())
        && proposal.targetScope().projectId().toString().equals(output.workProjectId())
        && proposal.owner().memberId().orElseThrow().toString().equals(output.ownerMemberId())
        && proposal
            .executor()
            .map(value -> value.principalId().toString())
            .equals(Optional.ofNullable(output.executorPrincipalId()))
        && proposal
            .gateReviewer()
            .flatMap(value -> value.memberId())
            .map(Object::toString)
            .equals(Optional.ofNullable(output.gateReviewerMemberId()));
  }

  private static TaskIntent requireMatchingCandidate(
      TaskIntent committed,
      TaskIntentOutputCandidate candidate,
      PlatformExecutionContext platform) {
    if (!committed.scope().equals(platform.scope())
        || !committed.conversationId().equals(candidate.conversationId())
        || !committed.proposedByPrincipalId().equals(platform.personalAgentPrincipalId())
        || !matchesCandidate(committed, candidate.output())) {
      throw new PolicyDeniedException("reuse this TaskIntent candidate");
    }
    return committed;
  }
}
