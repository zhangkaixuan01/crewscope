package io.crewscope.application.skill;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.knowledge.KnowledgeDistillationSourceAssembler;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
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
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillDisabledException;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.skill.TeamSkillOrigin;
import io.crewscope.domain.skill.event.TeamSkillCreated;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamScope;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Task-creator-initiated skill distillation boundary (M10-A03b, S01 §3.8): turns one
 * completed Task execution attempt into a DRAFT Team Skill through the same four-stage
 * sequence as knowledge distillation.
 *
 * <p>Stage 1 validates inside one transaction with no command-level side effects (no
 * receipt, no skill, no business events — the first call does lazily provision the
 * built-in Distiller); stage 2 performs the real model call without any transaction
 * or receipt reservation; stage 3 commits the usage facts in a small transaction —
 * tokens already spent on a command that reaches commit must be recorded even if the
 * skill commit fails; stage 4 re-asserts the cheap guards, reserves, assembles the
 * server-owned frontmatter (D4: the model produces only description and body, the
 * skill key stays command-owned) and commits the DRAFT skill with the standard 202
 * receipt contract. The authorization gate is the S01-frozen "own execution" choice:
 * only the Task creator — or a platform administrator — may distill, and publication
 * remains the separate SKILL_MANAGE publish command (D6).
 */
public final class SkillDistillationService {

    private static final String DISTILL = "DISTILL_TEAM_SKILL";
    private static final String AGGREGATE_TYPE = "TEAM_SKILL";
    private static final String USAGE_AGGREGATE_TYPE = "MODEL_USAGE_FACT";
    private static final String CALL_ID_NAMESPACE = "io.crewscope/model-usage/";
    private static final String USAGE_EVENT_ID_NAMESPACE = "io.crewscope/model-usage/event/";

    private final TeamSkillRepository repository;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final KnowledgeDistillationSourceAssembler sourceAssembler;
    private final SkillDistillerReadiness distillerReadiness;
    private final SkillDistillationPort port;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final Executor persistenceExecutor;
    private final boolean skillEnabled;

    public SkillDistillationService(
            TeamSkillRepository repository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            KnowledgeDistillationSourceAssembler sourceAssembler,
            SkillDistillerReadiness distillerReadiness,
            SkillDistillationPort port,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            Executor persistenceExecutor,
            boolean skillEnabled) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.sourceAssembler = Objects.requireNonNull(sourceAssembler, "sourceAssembler");
        this.distillerReadiness =
                Objects.requireNonNull(distillerReadiness, "distillerReadiness");
        this.port = Objects.requireNonNull(port, "port");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.persistenceExecutor =
                Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
        this.skillEnabled = skillEnabled;
    }

    /** Distills one completed current execution attempt into a new DRAFT Team Skill. */
    public CompletionStage<CommandExecution<TeamSkill>> distill(
            TeamCommandContext context, TeamId teamId, DistillTeamSkillCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        DistillTeamSkillCommand required = Objects.requireNonNull(command, "command");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                requestHash(
                        trusted,
                        DISTILL,
                        team.toString(),
                        required.taskExecutionId().toString(),
                        required.skillKey().value());
        Preparation preparation = transactions.required(() ->
                prepare(trusted, organizationId, team, required, requestHash));
        if (preparation.replayed().isPresent()) {
            return CompletableFuture.completedFuture(
                    CommandExecution.replayed(preparation.replayed().orElseThrow()));
        }
        UUID commandId = UUID.randomUUID();
        SkillDistillationPort.SkillDistillationRequest request =
                new SkillDistillationPort.SkillDistillationRequest(
                        organizationId,
                        team,
                        required.taskExecutionId(),
                        preparation.execution().attempt(),
                        required.skillKey(),
                        preparation.sourceText(),
                        trusted.access().actor().id(),
                        commandId,
                        trusted.correlationId());
        return port
                .distill(request)
                .thenApplyAsync(distilled -> {
                    // Tokens are already spent: usage facts commit independently of the skill.
                    // Both stages run blocking JPA transactions, so they must leave the
                    // Provider client's completion thread for the persistence executor.
                    transactions.required(() ->
                            recordUsageFacts(trusted, team, commandId, distilled.attribution()));
                    return transactions.required(() ->
                            commitSkill(trusted, preparation, required, requestHash,
                                    commandId, distilled.draft()));
                }, persistenceExecutor);
    }

    // ---------------------------------------------------------------- stage 1: validation

    private Preparation prepare(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            DistillTeamSkillCommand command,
            CommandRequestHash requestHash) {
        UtcTimestamp now = timeProvider.now();
        Principal actor = requireOrganizationUser(context.access(), organizationId);
        Team team = requireTeam(organizationId, teamId);
        TeamMember member = requireActiveMember(actor, team);
        TaskExecution execution = requireSourceExecution(organizationId, teamId, command);
        Task task = tasks.findById(organizationId, execution.taskId())
                .filter(value -> value.scope().equals(execution.scope()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Task", execution.taskId()));
        requireOwnExecution(context.access(), task, member);
        if (task.currentExecutionId().filter(execution.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "distillation.taskExecutionId",
                    "must be the Task's current execution attempt");
        }
        requireSkillEnabled();
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(),
                        DISTILL, requestHash);
        if (completed.isPresent()) {
            return Preparation.replayed(completed.orElseThrow());
        }
        // Fast-fail on a taken key before the model call spends tokens.
        repository.findByKey(organizationId, teamId, command.skillKey())
                .ifPresent(existing -> {
                    throw new TeamSkillKeyConflictException(
                            existing.scope(), existing.skillKey());
                });
        distillerReadiness.ensureReady(organizationId, teamId);
        String sourceText = sourceAssembler.assemble(task, execution);
        return new Preparation(actor, team, execution, sourceText, Optional.empty());
    }

    /**
     * The S01-frozen selection right: only the member who created the Task — the one
     * identity already carrying the execution's authorization — may spend the Team's
     * distillation budget on it. Not a SKILL_MANAGE grant (publication stays separate).
     */
    private static void requireOwnExecution(
            TeamAccessContext context, Task task, TeamMember member) {
        if (context.platformAdministrator()) {
            return;
        }
        if (task.audit().createdBy().filter(member.userPrincipalId()::equals).isEmpty()) {
            throw new PolicyDeniedException("distill from a Task they did not create");
        }
    }

    /** Cross-Team or missing executions are 404; non-terminal attempts are rejected. */
    private TaskExecution requireSourceExecution(
            OrganizationId organizationId, TeamId teamId, DistillTeamSkillCommand command) {
        TaskExecution execution = executions
                .findById(organizationId, command.taskExecutionId())
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", command.taskExecutionId()));
        if (execution.status() != TaskExecutionStatus.COMPLETED) {
            throw new DomainValidationException(
                    "distillation.taskExecutionStatus",
                    "must be COMPLETED before distillation");
        }
        return execution;
    }

    // ---------------------------------------------------------------- stage 3: usage facts

    private Void recordUsageFacts(
            TeamCommandContext context,
            TeamId teamId,
            UUID commandId,
            SkillDistillationPort.CallAttribution attribution) {
        OrganizationId organizationId = context.access().actor().scope().organizationId();
        UtcTimestamp now = timeProvider.now();
        for (SkillDistillationPort.AttemptUsage attemptUsage : attribution.attempts()) {
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
                            Optional.of(context.idempotencyKey().value()),
                            now,
                            payload);
            events.append(event);
            outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        }
        return null;
    }

    // ---------------------------------------------------------------- stage 4: skill commit

    private CommandExecution<TeamSkill> commitSkill(
            TeamCommandContext context,
            Preparation preparation,
            DistillTeamSkillCommand command,
            CommandRequestHash requestHash,
            UUID commandId,
            SkillDistillationPort.DistilledSkillDraft draft) {
        OrganizationId organizationId = context.access().actor().scope().organizationId();
        TeamId teamId = preparation.team().id();
        UtcTimestamp now = timeProvider.now();
        // The LLM call window spans seconds to minutes: re-assert the cheap guards at
        // commit time so a revocation or Team suspension landing mid-call cannot commit.
        Team team = requireTeam(organizationId, teamId);
        TeamMember member = requireActiveMember(preparation.actor(), team);
        Task task = tasks.findById(organizationId, preparation.execution().taskId())
                .filter(value -> value.scope().equals(preparation.execution().scope()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Task", preparation.execution().taskId()));
        requireOwnExecution(context.access(), task, member);
        if (task.currentExecutionId().filter(preparation.execution().id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "distillation.taskExecutionId",
                    "must be the Task's current execution attempt");
        }
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                DISTILL,
                requestHash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        repository.findByKey(organizationId, teamId, command.skillKey())
                .ifPresent(existing -> {
                    throw new TeamSkillKeyConflictException(
                            existing.scope(), existing.skillKey());
                });
        TeamSkill created = TeamSkill.create(
                new TeamScope(organizationId, teamId),
                command.skillKey(),
                skillDocument(command.skillKey().value(), draft),
                Optional.of(new TeamSkillOrigin(
                        preparation.execution().id().value(),
                        preparation.execution().attempt())),
                preparation.actor().id(),
                now);
        TeamSkill committed = repository.create(created);
        CommandExecution<TeamSkill> execution =
                completed(context, commandId, committed, now);
        receipts.saveResult(new CommandResult(
                organizationId,
                context.idempotencyKey(),
                preparation.actor().id(),
                DISTILL,
                teamId,
                Optional.empty(),
                CommandResult.ResourceType.TEAM_SKILL,
                committed.id().value(),
                committed.version(),
                execution.receipt(),
                now));
        return execution;
    }

    /**
     * Assembles the stored SKILL.md exactly as the runtime repository will serve it
     * (D4): the frontmatter names the command-owned key, the model's description is
     * folded onto one frontmatter line, and the domain's draft parsing re-validates
     * the whole document as the final defense.
     */
    private static String skillDocument(
            String skillKey, SkillDistillationPort.DistilledSkillDraft draft) {
        String description = draft.description().replaceAll("\\s+", " ").strip();
        if (description.isEmpty()) {
            throw new DomainValidationException(
                    "skillDistillation.description", "must not be blank");
        }
        return "---\nname: " + skillKey
                + "\ndescription: " + description
                + "\n---\n\n" + draft.body().stripTrailing() + "\n";
    }

    private CommandExecution<TeamSkill> completed(
            TeamCommandContext context,
            UUID commandId,
            TeamSkill skill,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event =
                new DomainEventEnvelope<>(
                        eventId,
                        EventType.from("TEAM_SKILL_CREATED"),
                        SchemaVersion.V1,
                        skill.scope().organizationId(),
                        Optional.of(skill.scope().teamId()),
                        Optional.empty(),
                        AggregateReference.of(AGGREGATE_TYPE, skill.id()),
                        // The head's optimistic-lock version is the aggregate version.
                        skill.version(),
                        EventActor.principal(EventActorType.USER, context.access().actor().id()),
                        context.correlationId(),
                        context.causationId(),
                        Optional.of(context.idempotencyKey().value()),
                        occurredAt,
                        TeamSkillCreated.from(skill));
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, skill.version(),
                        context.correlationId());
        receipts.complete(
                skill.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(skill, receipt);
    }

    // ---------------------------------------------------------------- shared guards

    /** Write-side switch; the distillation entry point is part of the write surface. */
    private void requireSkillEnabled() {
        if (!skillEnabled) {
            throw new TeamSkillDisabledException();
        }
    }

    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
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

    private TeamMember requireActiveMember(Principal actor, Team team) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's skills"));
    }

    private static Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        Principal actor = Objects.requireNonNull(context, "context").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    private static CommandRequestHash requestHash(
            TeamCommandContext context, String commandType, String... semanticFields) {
        String[] fields = new String[semanticFields.length + 2];
        fields[0] = context.access().actor().id().toString();
        fields[1] = context.causationId().map(UUID::toString).orElse("");
        System.arraycopy(semanticFields, 0, fields, 2, semanticFields.length);
        return CommandRequestHash.sha256(commandType, fields);
    }

    /** One usage fact per real attempt, addressable deterministically from the command (D5). */
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

    private record Preparation(
            Principal actor,
            Team team,
            TaskExecution execution,
            String sourceText,
            Optional<CommandReceipt> replayed) {

        static Preparation replayed(CommandReceipt receipt) {
            return new Preparation(null, null, null, null, Optional.of(receipt));
        }
    }
}
