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
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.event.WorkItemCommentAdded;
import io.crewscope.domain.workitem.event.WorkItemResourceLinked;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Appends authorized native comments and immutable resource links with durable events. */
public final class WorkItemCollaborationService {

  private static final String ADD_COMMENT = "ADD_WORK_ITEM_COMMENT";
  private static final String LINK_RESOURCE = "LINK_WORK_ITEM_RESOURCE";

  private final WorkItemCommentRepository commentRepository;
  private final WorkItemResourceLinkRepository resourceLinkRepository;
  private final WorkItemAccessPolicy accessPolicy;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public WorkItemCollaborationService(
      WorkItemCommentRepository commentRepository,
      WorkItemResourceLinkRepository resourceLinkRepository,
      WorkItemAccessPolicy accessPolicy,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.commentRepository = Objects.requireNonNull(commentRepository, "commentRepository");
    this.resourceLinkRepository =
        Objects.requireNonNull(resourceLinkRepository, "resourceLinkRepository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  public CommandExecution<WorkItemComment> addComment(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      AddWorkItemCommentCommand command) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    AddWorkItemCommentCommand required = Objects.requireNonNull(command, "command");
    String normalizedContent = required.content().strip();
    CommandRequestHash hash =
        CommandRequestHash.sha256(
            ADD_COMMENT,
            trusted.access().actor().id().toString(),
            teamId.toString(),
            projectId.toString(),
            workItemId.toString(),
            normalizedContent,
            trusted.causationId().map(UUID::toString).orElse(""));
    return execute(
        trusted,
        ADD_COMMENT,
        hash,
        commandId ->
            addCommentInTransaction(
                trusted, commandId, teamId, projectId, workItemId, normalizedContent));
  }

  public CommandExecution<WorkItemResourceLink> linkResource(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      LinkWorkItemResourceCommand command) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    LinkWorkItemResourceCommand required = Objects.requireNonNull(command, "command");
    String normalizedReference = required.resourceReference().strip();
    String normalizedLabel =
        required.label().map(String::strip).filter(value -> !value.isEmpty()).orElse("");
    CommandRequestHash hash =
        CommandRequestHash.sha256(
            LINK_RESOURCE,
            trusted.access().actor().id().toString(),
            teamId.toString(),
            projectId.toString(),
            workItemId.toString(),
            required.resourceType().name(),
            normalizedReference,
            normalizedLabel,
            trusted.causationId().map(UUID::toString).orElse(""));
    return execute(
        trusted,
        LINK_RESOURCE,
        hash,
        commandId ->
            linkResourceInTransaction(
                trusted,
                commandId,
                teamId,
                projectId,
                workItemId,
                required,
                normalizedReference,
                normalizedLabel));
  }

  private CommandExecution<WorkItemComment> addCommentInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      String content) {
    UtcTimestamp occurredAt = timeProvider.now();
    WorkItem workItem =
        requireParticipation(context, teamId, projectId, workItemId, occurredAt, "comment on");
    WorkItemComment committed =
        commentRepository.create(
            WorkItemComment.addNative(
                WorkItemCommentId.generate(),
                workItem,
                context.access().actor(),
                content,
                occurredAt));
    return completed(
        context,
        commandId,
        committed,
        committed.id(),
        committed.scope(),
        "WORK_ITEM_COMMENT",
        "WORK_ITEM_COMMENT_ADDED",
        WorkItemCommentAdded.from(committed),
        occurredAt);
  }

  private CommandExecution<WorkItemResourceLink> linkResourceInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      LinkWorkItemResourceCommand command,
      String reference,
      String label) {
    UtcTimestamp occurredAt = timeProvider.now();
    WorkItem workItem =
        requireParticipation(context, teamId, projectId, workItemId, occurredAt, "link resources to");
    WorkItemResourceLink committed =
        resourceLinkRepository.create(
            WorkItemResourceLink.link(
                WorkItemResourceLinkId.generate(),
                workItem,
                command.resourceType(),
                reference,
                Optional.of(label).filter(value -> !value.isEmpty()),
                context.access().actor(),
                occurredAt));
    return completed(
        context,
        commandId,
        committed,
        committed.id(),
        committed.scope(),
        "WORK_ITEM_RESOURCE_LINK",
        "WORK_ITEM_RESOURCE_LINKED",
        WorkItemResourceLinked.from(committed),
        occurredAt);
  }

  private WorkItem requireParticipation(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      UtcTimestamp occurredAt,
      String verb) {
    OrganizationId organizationId = context.access().actor().scope().organizationId();
    return accessPolicy.requirePermission(
        context.access(),
        organizationId,
        teamId,
        projectId,
        workItemId,
        TeamPermission.WORK_PARTICIPATE,
        occurredAt,
        verb + " this WorkItem");
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
      AggregateId aggregateId,
      WorkItemScope scope,
      String aggregateType,
      String eventType,
      DomainEvent payload,
      UtcTimestamp occurredAt) {
    WorkItemScope requiredScope = Objects.requireNonNull(scope, "scope");
    OrganizationId organizationId = requiredScope.organizationId();
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            EventType.from(eventType),
            SchemaVersion.V1,
            organizationId,
            Optional.of(requiredScope.teamId()),
            Optional.of(requiredScope.workspaceId()),
            AggregateReference.of(aggregateType, aggregateId),
            0,
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            payload);
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    CommandReceipt receipt = new CommandReceipt(commandId, eventId, 0, context.correlationId());
    receiptStore.complete(organizationId, context.idempotencyKey(), receipt, occurredAt);
    return CommandExecution.completed(result, receipt);
  }
}
