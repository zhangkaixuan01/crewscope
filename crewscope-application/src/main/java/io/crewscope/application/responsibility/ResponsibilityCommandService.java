package io.crewscope.application.responsibility;

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
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.event.GateReviewerAssigned;
import io.crewscope.domain.responsibility.event.ResponsibilityAssigned;
import io.crewscope.domain.responsibility.event.ResponsibilityReleased;
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
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Executes authorized responsibility commands with trusted targets, policy and durable events. */
public final class ResponsibilityCommandService {

  private static final String REPLACE_OWNER = "REPLACE_WORK_ITEM_OWNER";
  private static final String ASSIGN_EXECUTOR = "ASSIGN_WORK_ITEM_EXECUTOR";
  private static final String ASSIGN_GATE_REVIEWER = "ASSIGN_WORK_ITEM_GATE_REVIEWER";
  private static final String ASSIGN_ADVISORY_REVIEWER = "ASSIGN_WORK_ITEM_ADVISORY_REVIEWER";
  private static final String RELEASE_RESPONSIBILITY = "RELEASE_WORK_ITEM_RESPONSIBILITY";

  private final ResponsibilityAssignmentRepository assignmentRepository;
  private final ResponsibilityAssignmentService assignmentService;
  private final GateReviewerAssignmentService reviewerService;
  private final GateReviewerPolicyProvider reviewerPolicyProvider;
  private final WorkItemAccessPolicy accessPolicy;
  private final PrincipalRepository principalRepository;
  private final TeamMembershipQuery membershipQuery;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public ResponsibilityCommandService(
      ResponsibilityAssignmentRepository assignmentRepository,
      ResponsibilityAssignmentService assignmentService,
      GateReviewerAssignmentService reviewerService,
      GateReviewerPolicyProvider reviewerPolicyProvider,
      WorkItemAccessPolicy accessPolicy,
      PrincipalRepository principalRepository,
      TeamMembershipQuery membershipQuery,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    this.assignmentService = Objects.requireNonNull(assignmentService, "assignmentService");
    this.reviewerService = Objects.requireNonNull(reviewerService, "reviewerService");
    this.reviewerPolicyProvider =
        Objects.requireNonNull(reviewerPolicyProvider, "reviewerPolicyProvider");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  public CommandExecution<OwnerAssignmentChange> replaceOwner(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      ReplaceOwnerCommand command) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    ReplaceOwnerCommand required = Objects.requireNonNull(command, "command");
    String expectedId =
        required.expectation().assignmentId().map(Object::toString).orElse("NONE");
    CommandRequestHash hash =
        hash(
            REPLACE_OWNER,
            trusted,
            teamId,
            projectId,
            workItemId,
            required.actorPrincipalId().toString(),
            expectedId,
            Long.toString(required.expectation().assignmentVersion()));
    return execute(
        trusted,
        REPLACE_OWNER,
        hash,
        commandId -> {
          UtcTimestamp occurredAt = timeProvider.now();
          WorkItem item = requireManage(trusted, teamId, projectId, workItemId, occurredAt);
          Principal target = requirePrincipal(item.scope().organizationId(), required.actorPrincipalId());
          TeamMember member = requireActiveMember(item, target);
          OwnerAssignmentChange change =
              assignmentService.replaceOwner(
                  item, target, member, trusted.access().actor(), required.expectation());
          return completed(
              trusted,
              commandId,
              change,
              change.active(),
              change.released().isPresent() ? "WORK_ITEM_OWNER_REPLACED" : "WORK_ITEM_OWNER_ASSIGNED",
              ResponsibilityAssigned.from(
                  change.active(), change.released().map(ResponsibilityAssignment::id)),
              occurredAt);
        });
  }

  public CommandExecution<ResponsibilityAssignment> assignExecutor(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      AssignResponsibilityCommand command) {
    return assign(
        context,
        teamId,
        projectId,
        workItemId,
        command,
        ASSIGN_EXECUTOR,
        (item, target) ->
            assignmentService.assignExecutor(
                item,
                target,
                target.type() == PrincipalType.USER
                    ? Optional.of(requireActiveMember(item, target))
                    : Optional.empty(),
                context.access().actor()),
        "WORK_ITEM_EXECUTOR_ASSIGNED");
  }

  public CommandExecution<ResponsibilityAssignment> assignAdvisoryReviewer(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      AssignResponsibilityCommand command) {
    return assign(
        context,
        teamId,
        projectId,
        workItemId,
        command,
        ASSIGN_ADVISORY_REVIEWER,
        (item, target) ->
            assignmentService.assignAdvisoryReviewer(item, target, context.access().actor()),
        "WORK_ITEM_ADVISORY_REVIEWER_ASSIGNED");
  }

  public CommandExecution<GateReviewerAssignment> assignGateReviewer(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      AssignResponsibilityCommand command) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    AssignResponsibilityCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash hash =
        hash(
            ASSIGN_GATE_REVIEWER,
            trusted,
            teamId,
            projectId,
            workItemId,
            required.actorPrincipalId().toString());
    return execute(
        trusted,
        ASSIGN_GATE_REVIEWER,
        hash,
        commandId -> {
          UtcTimestamp occurredAt = timeProvider.now();
          WorkItem item = requireManage(trusted, teamId, projectId, workItemId, occurredAt);
          Principal target = requirePrincipal(item.scope().organizationId(), required.actorPrincipalId());
          TeamMember member = requireActiveMember(item, target);
          GateReviewerAssignment assigned =
              reviewerService.assignGateReviewer(
                  item,
                  target,
                  member,
                  trusted.access().actor(),
                  reviewerPolicyProvider.resolve(item));
          return completed(
              trusted,
              commandId,
              assigned,
              assigned.assignment(),
              "WORK_ITEM_GATE_REVIEWER_ASSIGNED",
              GateReviewerAssigned.from(assigned.assignment(), assigned.eligibility()),
              occurredAt);
        });
  }

  public CommandExecution<ResponsibilityAssignment> release(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      ResponsibilityAssignmentId assignmentId,
      ReleaseResponsibilityCommand command) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    ResponsibilityAssignmentId requiredId = Objects.requireNonNull(assignmentId, "assignmentId");
    ReleaseResponsibilityCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash hash =
        hash(
            RELEASE_RESPONSIBILITY,
            trusted,
            teamId,
            projectId,
            workItemId,
            requiredId.toString(),
            Long.toString(required.expectedVersion()));
    return execute(
        trusted,
        RELEASE_RESPONSIBILITY,
        hash,
        commandId -> {
          UtcTimestamp occurredAt = timeProvider.now();
          WorkItem item = requireManage(trusted, teamId, projectId, workItemId, occurredAt);
          ResponsibilityAssignment current =
              assignmentRepository
                  .findById(item.scope().organizationId(), requiredId)
                  .filter(value -> value.workItemId().equals(item.id()))
                  .filter(value -> value.scope().equals(item.scope()))
                  .orElseThrow(
                      () -> new AggregateNotFoundException("ResponsibilityAssignment", requiredId));
          ResponsibilityAssignment released =
              assignmentService.release(
                  item.scope().organizationId(),
                  current.id(),
                  required.expectedVersion(),
                  trusted.access().actor());
          return completed(
              trusted,
              commandId,
              released,
              released,
              "WORK_ITEM_RESPONSIBILITY_RELEASED",
              ResponsibilityReleased.from(released),
              occurredAt);
        });
  }

  private CommandExecution<ResponsibilityAssignment> assign(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      AssignResponsibilityCommand command,
      String commandType,
      AssignmentAction action,
      String eventType) {
    TeamCommandContext trusted = Objects.requireNonNull(context, "context");
    AssignResponsibilityCommand required = Objects.requireNonNull(command, "command");
    CommandRequestHash hash =
        hash(
            commandType,
            trusted,
            teamId,
            projectId,
            workItemId,
            required.actorPrincipalId().toString());
    return execute(
        trusted,
        commandType,
        hash,
        commandId -> {
          UtcTimestamp occurredAt = timeProvider.now();
          WorkItem item = requireManage(trusted, teamId, projectId, workItemId, occurredAt);
          Principal target = requirePrincipal(item.scope().organizationId(), required.actorPrincipalId());
          ResponsibilityAssignment assigned = action.assign(item, target);
          return completed(
              trusted,
              commandId,
              assigned,
              assigned,
              eventType,
              ResponsibilityAssigned.from(assigned, Optional.empty()),
              occurredAt);
        });
  }

  private WorkItem requireManage(
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      UtcTimestamp occurredAt) {
    OrganizationId organizationId = context.access().actor().scope().organizationId();
    return accessPolicy.requirePermission(
        context.access(),
        organizationId,
        Objects.requireNonNull(teamId, "teamId"),
        Objects.requireNonNull(projectId, "projectId"),
        Objects.requireNonNull(workItemId, "workItemId"),
        TeamPermission.RESPONSIBILITY_MANAGE,
        occurredAt,
        "manage this WorkItem's responsibilities");
  }

  private Principal requirePrincipal(OrganizationId organizationId, PrincipalId principalId) {
    return principalRepository
        .findById(organizationId, Objects.requireNonNull(principalId, "principalId"))
        .orElseThrow(() -> new AggregateNotFoundException("Principal", principalId));
  }

  private TeamMember requireActiveMember(WorkItem item, Principal principal) {
    return membershipQuery
        .findByTeam(item.scope().organizationId(), item.scope().teamId())
        .stream()
        .filter(TeamMember::canParticipate)
        .filter(member -> member.userPrincipalId().equals(principal.id()))
        .findFirst()
        .orElseThrow(
            () ->
                new DomainValidationException(
                    "responsibilityAssignment.actorPrincipalId",
                    "must reference an active USER member of the WorkItem Team"));
  }

  private <T> CommandExecution<T> execute(
      TeamCommandContext context,
      String commandType,
      CommandRequestHash requestHash,
      Function<UUID, CommandExecution<T>> action) {
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
          return action.apply(commandId);
        });
  }

  private <T> CommandExecution<T> completed(
      TeamCommandContext context,
      UUID commandId,
      T result,
      ResponsibilityAssignment assignment,
      String eventType,
      DomainEvent payload,
      UtcTimestamp occurredAt) {
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            EventType.from(eventType),
            SchemaVersion.V1,
            assignment.scope().organizationId(),
            Optional.of(assignment.scope().teamId()),
            Optional.of(assignment.scope().workspaceId()),
            AggregateReference.of("RESPONSIBILITY_ASSIGNMENT", assignment.id()),
            assignment.version(),
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            payload);
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    CommandReceipt receipt =
        new CommandReceipt(commandId, eventId, assignment.version(), context.correlationId());
    receiptStore.complete(
        assignment.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
    return CommandExecution.completed(result, receipt);
  }

  private static CommandRequestHash hash(
      String commandType,
      TeamCommandContext context,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      String... fields) {
    String[] values = new String[5 + fields.length];
    values[0] = context.access().actor().id().toString();
    values[1] = Objects.requireNonNull(teamId, "teamId").toString();
    values[2] = Objects.requireNonNull(projectId, "projectId").toString();
    values[3] = Objects.requireNonNull(workItemId, "workItemId").toString();
    values[4] = context.causationId().map(UUID::toString).orElse("");
    System.arraycopy(fields, 0, values, 5, fields.length);
    return CommandRequestHash.sha256(commandType, values);
  }

  @FunctionalInterface
  private interface AssignmentAction {
    ResponsibilityAssignment assign(WorkItem item, Principal target);
  }
}
