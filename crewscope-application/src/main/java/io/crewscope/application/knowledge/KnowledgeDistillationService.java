package io.crewscope.application.knowledge;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.distiller.DistillationCommandSupport;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryOrigin;
import io.crewscope.domain.knowledge.event.KnowledgeEntryCreated;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamScope;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Member-initiated knowledge distillation boundary (A02b): turns one completed Task
 * execution attempt into a DRAFT knowledge entry through a four-stage sequence (D1).
 *
 * <p>Stage 1 validates inside one transaction with no command-level side effects (no
 * receipt, no entry, no business events — the first call does lazily provision the built-in
 * Distiller); stage 2 performs the real model call without any transaction or receipt
 * reservation (a PENDING reservation cannot span the LLM call, and completing a receipt
 * before the entry exists would replay into nothing); stage 3 commits the usage facts in a
 * small transaction — tokens already spent on a command that reaches commit must be
 * recorded even if the entry commit fails; stage 4 re-asserts the access guards, reserves,
 * creates and commits the DRAFT entry with the standard 202 receipt contract. An LLM
 * failure leaves neither receipt nor usage facts, so an idempotency-key retry is a full
 * retry that spends new tokens and records new facts, exactly as the S01 §3.9 "real
 * retries count" rule requires.
 */
public final class KnowledgeDistillationService {

    private static final String DISTILL = "DISTILL_KNOWLEDGE_ENTRY";
    private static final String AGGREGATE_TYPE = "KNOWLEDGE_ENTRY";

    private final KnowledgeRepository repository;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final KnowledgeDistillationSourceAssembler sourceAssembler;
    private final KnowledgeDistillerReadiness distillerReadiness;
    private final KnowledgeDistillationPort port;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final Executor persistenceExecutor;
    private final DistillationCommandSupport commands;

    public KnowledgeDistillationService(
            KnowledgeRepository repository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            KnowledgeDistillationSourceAssembler sourceAssembler,
            KnowledgeDistillerReadiness distillerReadiness,
            KnowledgeDistillationPort port,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            Executor persistenceExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.sourceAssembler = Objects.requireNonNull(sourceAssembler, "sourceAssembler");
        this.distillerReadiness = Objects.requireNonNull(distillerReadiness, "distillerReadiness");
        this.port = Objects.requireNonNull(port, "port");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.persistenceExecutor =
                Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
        this.commands = new DistillationCommandSupport(
                teams, memberships, tasks, executions, events, outbox, receipts, timeProvider);
    }

    /** Distills one completed current execution attempt into a new DRAFT entry. */
    public CompletionStage<CommandExecution<KnowledgeEntry>> distill(
            TeamCommandContext context, TeamId teamId, DistillKnowledgeEntryCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        DistillKnowledgeEntryCommand required = Objects.requireNonNull(command, "command");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                commands.requestHash(
                        trusted,
                        DISTILL,
                        team.toString(),
                        required.taskExecutionId().toString(),
                        required.entryKey().value(),
                        required.category().map(KnowledgeCategory::name).orElse(""));
        Preparation preparation = transactions.required(() ->
                prepare(trusted, organizationId, team, required, requestHash));
        if (preparation.replayed().isPresent()) {
            return CompletableFuture.completedFuture(
                    CommandExecution.replayed(preparation.replayed().orElseThrow()));
        }
        UUID commandId = UUID.randomUUID();
        KnowledgeDistillationPort.KnowledgeDistillationRequest request =
                new KnowledgeDistillationPort.KnowledgeDistillationRequest(
                        organizationId,
                        team,
                        required.taskExecutionId(),
                        preparation.execution().attempt(),
                        preparation.sourceText(),
                        required.category(),
                        trusted.access().actor().id(),
                        commandId,
                        trusted.correlationId());
        return port
                .distill(request)
                .thenApplyAsync(distilled -> {
                    // Tokens are already spent: usage facts commit independently of the entry.
                    // Both stages run blocking JPA transactions, so they must leave the
                    // Provider client's completion thread for the persistence executor.
                    transactions.required(() -> commands.recordUsageFacts(
                            trusted, team, commandId, distilled.attribution()));
                    return transactions.required(() ->
                            commitEntry(trusted, preparation, required, requestHash,
                                    commandId, distilled.draft()));
                }, persistenceExecutor);
    }

    // ---------------------------------------------------------------- stage 1: validation

    private Preparation prepare(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            DistillKnowledgeEntryCommand command,
            CommandRequestHash requestHash) {
        UtcTimestamp now = timeProvider.now();
        Principal actor = commands.requireOrganizationUser(context.access(), organizationId);
        Team team = commands.requireTeam(organizationId, teamId);
        TeamMember member =
                commands.requireActiveMember(actor, team, "access this Team's knowledge");
        requireKnowledgeManage(context.access(), team, member, now);
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(),
                        DISTILL, requestHash);
        if (completed.isPresent()) {
            return Preparation.replayed(completed.orElseThrow());
        }
        TaskExecution execution = commands.requireSourceExecution(
                organizationId, teamId, command.taskExecutionId());
        Task task = commands.requireTask(organizationId, execution);
        commands.requireCurrentExecution(task, execution);
        // Fast-fail on a taken key before the model call spends tokens.
        repository.findByKey(organizationId, teamId, command.entryKey())
                .ifPresent(existing -> {
                    throw new KnowledgeEntryKeyConflictException(
                            existing.scope(), existing.entryKey());
                });
        distillerReadiness.ensureReady(organizationId, teamId);
        String sourceText = sourceAssembler.assemble(task, execution);
        return new Preparation(actor, team, execution, sourceText, Optional.empty());
    }

    // ---------------------------------------------------------------- stage 4: entry commit

    private CommandExecution<KnowledgeEntry> commitEntry(
            TeamCommandContext context,
            Preparation preparation,
            DistillKnowledgeEntryCommand command,
            CommandRequestHash requestHash,
            UUID commandId,
            KnowledgeDistillationPort.DistilledDraft draft) {
        OrganizationId organizationId = context.access().actor().scope().organizationId();
        TeamId teamId = preparation.team().id();
        UtcTimestamp now = timeProvider.now();
        // The LLM call window spans seconds to minutes: re-assert the cheap guards at commit
        // time so a revocation or Team suspension landing mid-call cannot still commit.
        Team team = commands.requireTeam(organizationId, teamId);
        requireKnowledgeManage(
                context.access(), team,
                commands.requireActiveMember(
                        preparation.actor(), team, "access this Team's knowledge"),
                now);
        commands.requireCurrentExecution(
                commands.requireTask(organizationId, preparation.execution()),
                preparation.execution());
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
        repository.findByKey(organizationId, teamId, command.entryKey())
                .ifPresent(existing -> {
                    throw new KnowledgeEntryKeyConflictException(
                            existing.scope(), existing.entryKey());
                });
        KnowledgeEntry created = KnowledgeEntry.createDistilled(
                new TeamScope(organizationId, teamId),
                command.entryKey(),
                resolvedCategory(command, draft),
                draft.title(),
                draft.content(),
                new KnowledgeEntryOrigin(
                        preparation.execution().id().value(),
                        preparation.execution().attempt()),
                preparation.actor().id(),
                now);
        KnowledgeEntry committed = repository.create(created);
        CommandExecution<KnowledgeEntry> execution =
                commands.appendCreated(
                        context,
                        commandId,
                        committed,
                        organizationId,
                        teamId,
                        committed.id(),
                        committed.version(),
                        AGGREGATE_TYPE,
                        "KNOWLEDGE_ENTRY_CREATED",
                        KnowledgeEntryCreated.from(committed),
                        now);
        receipts.saveResult(new CommandResult(
                organizationId,
                context.idempotencyKey(),
                preparation.actor().id(),
                DISTILL,
                teamId,
                Optional.empty(),
                CommandResult.ResourceType.KNOWLEDGE_ENTRY,
                committed.id().value(),
                committed.version(),
                execution.receipt(),
                now));
        return execution;
    }

    /** Requested classification wins; an unusable model suggestion degrades to OTHER (D8). */
    private static KnowledgeCategory resolvedCategory(
            DistillKnowledgeEntryCommand command,
            KnowledgeDistillationPort.DistilledDraft draft) {
        return command.category().orElseGet(() -> {
            try {
                return KnowledgeCategory.valueOf(draft.suggestedCategory().strip());
            } catch (RuntimeException unusableSuggestion) {
                return KnowledgeCategory.OTHER;
            }
        });
    }

    // ---------------------------------------------------------------- shared guards

    private void requireKnowledgeManage(
            TeamAccessContext context, Team team, TeamMember member, UtcTimestamp now) {
        if (context.platformAdministrator()) {
            return;
        }
        Map<TeamRoleId, TeamRole> rolesById = roles
                .findByTeam(team.organizationId(), team.id()).stream()
                .collect(Collectors.toMap(TeamRole::id, Function.identity()));
        boolean allowed = grants.findByMember(team.organizationId(), member.id()).stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(now))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .map(grant -> rolesById.get(grant.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(role -> role.permissions().contains(TeamPermission.KNOWLEDGE_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage this Team's knowledge");
        }
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
