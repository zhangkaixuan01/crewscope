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
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingResolution;
import io.crewscope.application.provider.ProviderBindingResolutionRequest;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.conversation.ConversationWorkItemLinkOrigin;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.conversation.TaskIntentResponsibility;
import io.crewscope.domain.conversation.event.TaskIntentConfirmed;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.responsibility.event.GateReviewerAssigned;
import io.crewscope.domain.responsibility.event.ResponsibilityAssigned;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomically confirms one current TaskIntent and creates its complete native WorkItem graph. */
public final class TaskIntentConfirmationService implements TaskIntentConfirmationCommandPort {

  private static final String COMMAND_TYPE = "CONFIRM_TASK_INTENT";
  private static final String TASK_INTENT_AGGREGATE = "TASK_INTENT";

  private final TaskIntentApplicationService taskIntentService;
  private final TaskIntentRepository taskIntentRepository;
  private final ConversationRepository conversationRepository;
  private final ConversationWorkItemLinkRepository linkRepository;
  private final WorkProjectRepository projectRepository;
  private final WorkItemRepository workItemRepository;
  private final WorkItemAccessPolicy workItemAccessPolicy;
  private final TeamRepository teamRepository;
  private final TeamMembershipQuery membershipQuery;
  private final PrincipalRepository principalRepository;
  private final ResponsibilityAssignmentRepository assignmentRepository;
  private final GateReviewerPolicyProvider reviewerPolicyProvider;
  private final BuiltInProviderRegistration registration;
  private final ProviderBindingResolver bindingResolver;
  private final DomainEventStore domainEventStore;
  private final ConversationEventRepository conversationEventRepository;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public TaskIntentConfirmationService(
      TaskIntentApplicationService taskIntentService,
      TaskIntentRepository taskIntentRepository,
      ConversationRepository conversationRepository,
      ConversationWorkItemLinkRepository linkRepository,
      WorkProjectRepository projectRepository,
      WorkItemRepository workItemRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      TeamRepository teamRepository,
      TeamMembershipQuery membershipQuery,
      PrincipalRepository principalRepository,
      ResponsibilityAssignmentRepository assignmentRepository,
      GateReviewerPolicyProvider reviewerPolicyProvider,
      BuiltInProviderRegistration registration,
      ProviderBindingResolver bindingResolver,
      DomainEventStore domainEventStore,
      ConversationEventRepository conversationEventRepository,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.taskIntentService = Objects.requireNonNull(taskIntentService, "taskIntentService");
    this.taskIntentRepository =
        Objects.requireNonNull(taskIntentRepository, "taskIntentRepository");
    this.conversationRepository =
        Objects.requireNonNull(conversationRepository, "conversationRepository");
    this.linkRepository = Objects.requireNonNull(linkRepository, "linkRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
    this.workItemAccessPolicy =
        Objects.requireNonNull(workItemAccessPolicy, "workItemAccessPolicy");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    this.reviewerPolicyProvider =
        Objects.requireNonNull(reviewerPolicyProvider, "reviewerPolicyProvider");
    this.registration = Objects.requireNonNull(registration, "registration");
    this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.conversationEventRepository =
        Objects.requireNonNull(conversationEventRepository, "conversationEventRepository");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  @Override
  public CommandExecution<TaskIntentConfirmationResult> confirm(
      TeamCommandContext context,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      ConfirmTaskIntentCommand command) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    TeamId team = Objects.requireNonNull(teamId, "teamId");
    ConversationIdAndTaskIntentId requiredTarget = Objects.requireNonNull(target, "target");
    ConfirmTaskIntentCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash hash =
        CommandRequestHash.sha256(
            COMMAND_TYPE,
            trusted.access().actor().id().toString(),
            team.toString(),
            requiredTarget.conversationId().toString(),
            requiredTarget.taskIntentId().toString(),
            Long.toString(required.expectedVersion()),
            trusted.causationId().map(UUID::toString).orElse(""));
    return transactionExecutor.required(
        () -> confirmInTransaction(trusted, team, requiredTarget, required, hash));
  }

  private CommandExecution<TaskIntentConfirmationResult> confirmInTransaction(
      TeamCommandContext context,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      ConfirmTaskIntentCommand command,
      CommandRequestHash requestHash) {
    OrganizationId organizationId = context.access().actor().scope().organizationId();
    UtcTimestamp occurredAt = timeProvider.now();
    UUID commandId = UUID.randomUUID();
    CommandReservation reservation =
        receiptStore.reserve(
            new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                COMMAND_TYPE,
                requestHash,
                commandId,
                context.correlationId(),
                occurredAt));
    if (!reservation.acquired()) {
      return CommandExecution.replayed(reservation.receipt().orElseThrow());
    }

    TaskIntent locked =
        taskIntentRepository
            .lockById(organizationId, target.taskIntentId())
            .filter(value -> value.scope().teamId().equals(teamId))
            .filter(value -> value.conversationId().equals(target.conversationId()))
            .orElseThrow(
                () -> new AggregateNotFoundException("TaskIntent", target.taskIntentId()));
    TaskIntentConfirmationPreview preview =
        taskIntentService.previewConfirmation(
            context.access(), organizationId, teamId, target, command.expectedVersion());
    if (!locked.id().equals(preview.taskIntent().id())
        || locked.version() != preview.taskIntent().version()) {
      throw new DomainValidationException(
          "taskIntent", "must match the locked confirmation candidate");
    }

    Conversation conversation =
        conversationRepository
            .findById(organizationId, target.conversationId())
            .filter(value -> value.scope().equals(locked.scope()))
            .orElseThrow(
                () -> new AggregateNotFoundException("Conversation", target.conversationId()));
    TaskIntentProposal proposal = preview.validatedProposal();
    workItemAccessPolicy.requireCreatePermission(
        context.access(),
        organizationId,
        teamId,
        proposal.targetScope().projectId(),
        occurredAt);
    WorkProject project =
        projectRepository
            .lockById(organizationId, proposal.targetScope().projectId())
            .filter(WorkProject::acceptsWork)
            .filter(value -> value.scope().teamId().equals(teamId))
            .filter(value -> value.scope().workspaceId().equals(locked.scope().workspaceId()))
            .orElseThrow(
                () ->
                    new AggregateNotFoundException(
                        "WorkProject", proposal.targetScope().projectId()));

    Team team =
        teamRepository
            .findById(organizationId, teamId)
            .filter(Team::isActive)
            .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    ProviderBindingCandidate binding = requireNativeBinding(team, project);

    WorkItemKey key = workItemRepository.nextKey(organizationId, project);
    WorkItem workItem =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                key,
                WorkItemType.TASK,
                workItemTitle(proposal.objective()),
                Optional.of(workItemDescription(proposal)),
                WorkItemPriority.MEDIUM,
                Set.<WorkItemLabel>of(),
                Optional.empty(),
                context.access().actor(),
                occurredAt));

    List<TeamMember> members = List.copyOf(membershipQuery.findByTeam(organizationId, teamId));
    ResponsibilityAssignment owner =
        createAssignment(
            workItem, proposal.owner(), members, context.access().actor(), occurredAt);
    Optional<ResponsibilityAssignment> executor =
        proposal
            .executor()
            .map(
                responsibility ->
                    createAssignment(
                        workItem,
                        responsibility,
                        members,
                        context.access().actor(),
                        occurredAt));
    Optional<ConfirmedReviewer> reviewer =
        proposal
            .gateReviewer()
            .map(
                responsibility ->
                    createGateReviewer(
                        workItem,
                        responsibility,
                        members,
                        owner,
                        executor,
                        context.access().actor(),
                        occurredAt));

    TaskIntent confirmed =
        taskIntentRepository.confirm(
            locked.confirm(
                command.expectedVersion(),
                proposal,
                context.access().actor(),
                occurredAt),
            workItem.id());
    linkRepository.create(
        ConversationWorkItemLink.link(
            conversation,
            workItem,
            ConversationWorkItemLinkOrigin.TASK_INTENT_CONFIRMATION,
            context.access().actor(),
            occurredAt));

    appendWorkItemEvents(context, workItem, owner, executor, reviewer, occurredAt);
    DomainEventEnvelope<DomainEvent> confirmationEvent =
        appendEvent(
            context,
            EventType.from("TASK_INTENT_CONFIRMED"),
            AggregateReference.of(TASK_INTENT_AGGREGATE, confirmed.id()),
            confirmed.version(),
            TaskIntentConfirmed.from(
                confirmed,
                workItem,
                binding.binding().id(),
                owner,
                executor,
                reviewer.map(ConfirmedReviewer::assignment)),
            occurredAt,
            confirmed.scope().teamId(),
            confirmed.scope().workspaceId(),
            true);
    CommandReceipt receipt =
        new CommandReceipt(
            commandId,
            confirmationEvent.eventId(),
            confirmed.version(),
            context.correlationId());
    receiptStore.complete(organizationId, context.idempotencyKey(), receipt, occurredAt);
    return CommandExecution.completed(
        new TaskIntentConfirmationResult(confirmed, workItem.id()), receipt);
  }

  private ProviderBindingCandidate requireNativeBinding(Team team, WorkProject project) {
    ProviderAccessScope requested =
        new ProviderAccessScope(
            ProviderCapabilities.of("workitem.create"),
            registration.workspaceAccess(project.scope().workspaceId()).resources());
    ProviderBindingResolution resolution =
        bindingResolver.resolve(
            new ProviderBindingResolutionRequest(
                project.scope().organizationId(),
                project.scope().teamId(),
                project.scope().workspaceId(),
                Optional.of(project.id()),
                ProviderOwner.team(team),
                registration.type(),
                Optional.empty(),
                requested,
                Optional.empty(),
                Optional.empty()));
    ProviderBindingCandidate candidate =
        resolution
            .candidate()
            .orElseThrow(
                () ->
                    new DomainValidationException(
                        "taskIntent.providerBinding",
                        "must resolve exactly one current NativeWorkItem Binding; status="
                            + resolution.status()));
    if (!candidate
            .definition()
            .id()
            .equals(registration.definitionId(project.scope().organizationId()))
        || !candidate
            .implementation()
            .id()
            .equals(registration.implementationId(project.scope().organizationId()))
        || candidate.connection().isPresent()
        || candidate.connectionGrant().isPresent()) {
      throw new DomainValidationException(
          "taskIntent.providerBinding", "must resolve the built-in connectionless implementation");
    }
    return candidate;
  }

  private ResponsibilityAssignment createAssignment(
      WorkItem workItem,
      TaskIntentResponsibility responsibility,
      List<TeamMember> members,
      Principal assignedBy,
      UtcTimestamp occurredAt) {
    Principal principal = requirePrincipal(workItem.scope().organizationId(), responsibility);
    Optional<TeamMember> member =
        responsibility
            .memberId()
            .map(
                id ->
                    members.stream()
                        .filter(TeamMember::canParticipate)
                        .filter(value -> value.id().equals(id))
                        .filter(value -> value.userPrincipalId().equals(principal.id()))
                        .findFirst()
                        .orElseThrow(
                            () ->
                                new DomainValidationException(
                                    "taskIntent.responsibility.memberId",
                                    "must reference the current active Team member")));
    return assignmentRepository.create(
        ResponsibilityAssignment.assign(
            ResponsibilityAssignmentId.generate(),
            workItem,
            responsibility.role(),
            principal,
            member,
            assignedBy,
            occurredAt));
  }

  private ConfirmedReviewer createGateReviewer(
      WorkItem workItem,
      TaskIntentResponsibility responsibility,
      List<TeamMember> members,
      ResponsibilityAssignment owner,
      Optional<ResponsibilityAssignment> executor,
      Principal assignedBy,
      UtcTimestamp occurredAt) {
    Principal reviewer = requirePrincipal(workItem.scope().organizationId(), responsibility);
    TeamMember reviewerMember =
        responsibility
            .memberId()
            .flatMap(
                id ->
                    members.stream()
                        .filter(TeamMember::canParticipate)
                        .filter(value -> value.id().equals(id))
                        .filter(value -> value.userPrincipalId().equals(reviewer.id()))
                        .findFirst())
            .orElseThrow(
                () ->
                    new DomainValidationException(
                        "taskIntent.gateReviewer.memberId",
                        "must reference the current active Team member"));
    List<ResponsibilityAssignment> active = new ArrayList<>();
    active.add(owner);
    executor.ifPresent(active::add);
    ReviewerEligibilityDecision eligibility =
        reviewerPolicyProvider
            .resolve(workItem)
            .evaluateGate(workItem, reviewer, reviewerMember, members, active);
    ResponsibilityAssignment assignment =
        assignmentRepository.create(
            ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                workItem,
                ResponsibilityRole.REVIEWER,
                reviewer,
                Optional.of(reviewerMember),
                assignedBy,
                occurredAt));
    return new ConfirmedReviewer(assignment, eligibility);
  }

  private Principal requirePrincipal(
      OrganizationId organizationId, TaskIntentResponsibility responsibility) {
    return principalRepository
        .findById(organizationId, responsibility.principalId())
        .filter(Principal::canAct)
        .filter(value -> value.type() == responsibility.principalType())
        .orElseThrow(
            () ->
                new DomainValidationException(
                    "taskIntent.responsibility.principalId",
                    "must reference the current active Principal"));
  }

  private void appendWorkItemEvents(
      TeamCommandContext context,
      WorkItem workItem,
      ResponsibilityAssignment owner,
      Optional<ResponsibilityAssignment> executor,
      Optional<ConfirmedReviewer> reviewer,
      UtcTimestamp occurredAt) {
    appendEvent(
        context,
        EventType.from("WORK_ITEM_CREATED"),
        AggregateReference.of("WORK_ITEM", workItem.id()),
        workItem.version(),
        io.crewscope.domain.workitem.event.WorkItemCreated.from(workItem, owner),
        occurredAt,
        workItem.scope().teamId(),
        workItem.scope().workspaceId(),
        false);
    executor.ifPresent(
        assignment ->
            appendEvent(
                context,
                EventType.from("WORK_ITEM_EXECUTOR_ASSIGNED"),
                AggregateReference.of("RESPONSIBILITY_ASSIGNMENT", assignment.id()),
                assignment.version(),
                ResponsibilityAssigned.from(assignment, Optional.empty()),
                occurredAt,
                workItem.scope().teamId(),
                workItem.scope().workspaceId(),
                false));
    reviewer.ifPresent(
        confirmedReviewer -> {
          ResponsibilityAssignment assignment = confirmedReviewer.assignment();
          appendEvent(
              context,
              EventType.from("WORK_ITEM_GATE_REVIEWER_ASSIGNED"),
              AggregateReference.of("RESPONSIBILITY_ASSIGNMENT", assignment.id()),
              assignment.version(),
              GateReviewerAssigned.from(assignment, confirmedReviewer.eligibility()),
              occurredAt,
              workItem.scope().teamId(),
              workItem.scope().workspaceId(),
              false);
        });
  }

  private DomainEventEnvelope<DomainEvent> appendEvent(
      TeamCommandContext context,
      EventType eventType,
      AggregateReference aggregate,
      long aggregateVersion,
      DomainEvent payload,
      UtcTimestamp occurredAt,
      TeamId teamId,
      WorkspaceId workspaceId,
      boolean conversationAssociated) {
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            UUID.randomUUID(),
            eventType,
            SchemaVersion.V1,
            context.access().actor().scope().organizationId(),
            Optional.of(teamId),
            Optional.of(workspaceId),
            aggregate,
            aggregateVersion,
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            conversationAssociated
                ? Optional.of(context.idempotencyKey().value())
                : Optional.empty(),
            occurredAt,
            payload);
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    if (conversationAssociated) {
      TaskIntentConfirmed confirmed = (TaskIntentConfirmed) payload;
      conversationEventRepository.append(
          new io.crewscope.domain.conversation.ConversationId(confirmed.conversationId()), event);
    }
    return event;
  }

  private static String workItemTitle(String objective) {
    String normalized = objective.replaceAll("\\s+", " ").strip();
    if (normalized.length() <= WorkItem.MAX_TITLE_LENGTH) {
      return normalized;
    }
    int end = WorkItem.MAX_TITLE_LENGTH - 3;
    // Keep the derived title valid UTF-16 when the boundary lands inside an emoji surrogate pair.
    if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
      end--;
    }
    return normalized.substring(0, end).stripTrailing() + "...";
  }

  private static String workItemDescription(TaskIntentProposal proposal) {
    StringBuilder description = new StringBuilder(proposal.objective());
    description.append("\n\n## Acceptance criteria\n");
    proposal.acceptanceCriteria().forEach(value -> description.append("\n- ").append(value));
    return description.toString();
  }

  private record ConfirmedReviewer(
      ResponsibilityAssignment assignment, ReviewerEligibilityDecision eligibility) {

    private ConfirmedReviewer {
      assignment = Objects.requireNonNull(assignment, "assignment");
      eligibility = Objects.requireNonNull(eligibility, "eligibility");
    }
  }
}
