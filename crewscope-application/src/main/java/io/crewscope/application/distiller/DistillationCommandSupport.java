package io.crewscope.application.distiller;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
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
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared command envelope of the Knowledge and Skill distillation services (M13-C03):
 * the Team/Principal guards, the completed-execution check, the request hash, the
 * per-attempt usage facts and the created-aggregate event commit. The per-kind stage-1
 * authorization, aggregate construction and receipt result stay with the service.
 */
public final class DistillationCommandSupport {

    private static final String USAGE_AGGREGATE_TYPE = "MODEL_USAGE_FACT";
    private static final String CALL_ID_NAMESPACE = "io.crewscope/model-usage/";
    private static final String USAGE_EVENT_ID_NAMESPACE = "io.crewscope/model-usage/event/";

    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TimeProvider timeProvider;

    public DistillationCommandSupport(
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TimeProvider timeProvider) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        Principal actor = Objects.requireNonNull(context, "context").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    public Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teams.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException("team.initializationStatus", "must be READY");
        }
        Team team = teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        if (!team.isActive()) {
            throw new DomainValidationException("team.status", "must be ACTIVE");
        }
        return team;
    }

    public TeamMember requireActiveMember(Principal actor, Team team, String denialMessage) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException(denialMessage));
    }

    /** Cross-Team or missing executions are 404; non-terminal attempts are rejected. */
    public TaskExecution requireSourceExecution(
            OrganizationId organizationId, TeamId teamId, TaskExecutionId taskExecutionId) {
        TaskExecution execution = executions
                .findById(organizationId, Objects.requireNonNull(taskExecutionId, "taskExecutionId"))
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", taskExecutionId));
        if (execution.status() != TaskExecutionStatus.COMPLETED) {
            throw new DomainValidationException(
                    "distillation.taskExecutionStatus",
                    "must be COMPLETED before distillation");
        }
        return execution;
    }

    /** Re-reads the source Task in the execution's scope. */
    public Task requireTask(OrganizationId organizationId, TaskExecution execution) {
        return tasks.findById(organizationId, execution.taskId())
                .filter(value -> value.scope().equals(execution.scope()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Task", execution.taskId()));
    }

    /** Rejects a source execution that is no longer the Task's current attempt. */
    public void requireCurrentExecution(Task task, TaskExecution execution) {
        if (task.currentExecutionId().filter(execution.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "distillation.taskExecutionId",
                    "must be the Task's current execution attempt");
        }
    }

    public CommandRequestHash requestHash(
            TeamCommandContext context, String commandType, String... semanticFields) {
        String[] fields = new String[semanticFields.length + 2];
        fields[0] = context.access().actor().id().toString();
        fields[1] = context.causationId().map(UUID::toString).orElse("");
        System.arraycopy(semanticFields, 0, fields, 2, semanticFields.length);
        return CommandRequestHash.sha256(commandType, fields);
    }

    /** Commits one usage fact per real attempt, independent of the aggregate commit. */
    public Void recordUsageFacts(
            TeamCommandContext context,
            TeamId teamId,
            UUID commandId,
            DistillationCallAttribution attribution) {
        OrganizationId organizationId = context.access().actor().scope().organizationId();
        UtcTimestamp now = timeProvider.now();
        for (DistillationAttemptUsage attemptUsage : attribution.attempts()) {
            ModelUsageFactId callId = stableCallId(commandId, attemptUsage.attempt());
            ModelUsageFactRecorded payload =
                    new ModelUsageFactRecorded(
                            callId,
                            ModelUsageRole.DISTILLATION,
                            attemptUsage.attempt(),
                            attribution.providerKey(),
                            attribution.modelId(),
                            attribution.connectionId(),
                            attribution.connectionVersion(),
                            attemptUsage.usage(),
                            now);
            DomainEventEnvelope<DomainEvent> event =
                    new DomainEventEnvelope<>(
                            stableUsageEventId(callId.value()),
                            EventType.from("MODEL_USAGE_FACT_RECORDED"),
                            SchemaVersion.V1,
                            organizationId,
                            Optional.of(teamId),
                            Optional.empty(),
                            AggregateReference.of(USAGE_AGGREGATE_TYPE, callId),
                            attemptUsage.attempt(),
                            EventActor.principal(
                                    EventActorType.USER, context.access().actor().id()),
                            context.correlationId(),
                            context.causationId(),
                            // One command legitimately appends one usage fact per provider
                            // attempt, so the store's one-event-per-key contract needs the
                            // attempt suffix — the command key alone collides on the second
                            // attempt (M10-Q02 real-stack finding; '#' cannot appear in a
                            // client command key).
                            Optional.of(context.idempotencyKey().value()
                                    + "#usage-" + attemptUsage.attempt()),
                            now,
                            payload);
            events.append(event);
            outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        }
        return null;
    }

    /** Appends the created-aggregate event, completes the receipt and returns the execution. */
    public <AGGREGATE> CommandExecution<AGGREGATE> appendCreated(
            TeamCommandContext context,
            UUID commandId,
            AGGREGATE aggregate,
            OrganizationId organizationId,
            TeamId teamId,
            AggregateId aggregateId,
            long aggregateVersion,
            String aggregateType,
            String eventType,
            DomainEvent payload,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event =
                new DomainEventEnvelope<>(
                        eventId,
                        EventType.from(eventType),
                        SchemaVersion.V1,
                        organizationId,
                        Optional.of(teamId),
                        Optional.empty(),
                        AggregateReference.of(aggregateType, aggregateId),
                        aggregateVersion,
                        EventActor.principal(EventActorType.USER, context.access().actor().id()),
                        context.correlationId(),
                        context.causationId(),
                        Optional.of(context.idempotencyKey().value()),
                        occurredAt,
                        payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, aggregateVersion,
                        context.correlationId());
        receipts.complete(
                organizationId, context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(aggregate, receipt);
    }

    /** One usage fact per real attempt, addressable deterministically from the command. */
    private static ModelUsageFactId stableCallId(UUID commandId, int attempt) {
        String source = CALL_ID_NAMESPACE + commandId + '/' + attempt;
        return new ModelUsageFactId(
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID stableUsageEventId(UUID callId) {
        return UUID.nameUUIDFromBytes(
                (USAGE_EVENT_ID_NAMESPACE + callId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
