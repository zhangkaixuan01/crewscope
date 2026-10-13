package io.crewscope.application.organization.governance;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingStatus;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.organization.governance.AccountDeletionItem;
import io.crewscope.domain.organization.governance.AccountDeletionItemId;
import io.crewscope.domain.organization.governance.AccountDeletionJob;
import io.crewscope.domain.organization.governance.AccountDeletionJobId;
import io.crewscope.domain.organization.governance.AccountDeletionJobStatus;
import io.crewscope.domain.organization.governance.event.OrganizationAccountDeletionCancelled;
import io.crewscope.domain.organization.governance.event.OrganizationAccountDeletionCompleted;
import io.crewscope.domain.organization.governance.event.OrganizationAccountDeletionConfirmed;
import io.crewscope.domain.organization.governance.event.OrganizationAccountDeletionRequested;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-scoped deletion coordination (M12-A01, S01 freeze 3.4).
 *
 * <p>create runs the hard preconditions (zero in-flight task executions, zero active
 * responsibilities — the caller is steered through the handover job first), pins the six-step
 * checklist with live expected counts and freezes the backup retention statement onto the job.
 * confirm is the second, deliberate step: only the sha-256 of the target username unlocks
 * processing. process settles each step in its own short transaction, re-observing the live
 * count first so a drift settles as CONFLICT instead of a blind write; every settlement — DONE,
 * CONFLICT or DENIED alike — lands as a direct governance audit fact. There is no rollback of
 * DONE steps: cancel keeps them and a re-run after a restored backup converges idempotently.
 */
public final class OrganizationAccountDeletionApplicationService {

    private static final String CREATE_COMMAND_TYPE = "organization.create-account-deletion";
    private static final String AGGREGATE_TYPE = "ORGANIZATION_ACCOUNT_DELETION_JOB";

    private final OrganizationAccountDeletionJobRepository jobs;
    private final OrganizationAuthorization authorization;
    private final AccountDeletionStepExecutor steps;
    private final AccountOrganizationBindingRepository bindings;
    private final UserAccountRepository userAccounts;
    private final TaskExecutionRepository taskExecutions;
    private final ResponsibilityAssignmentRepository responsibilities;
    private final CommandReceiptStore receipts;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final GovernanceAuditRecorder governanceAudit;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public OrganizationAccountDeletionApplicationService(
            OrganizationAccountDeletionJobRepository jobs,
            OrganizationAuthorization authorization,
            AccountDeletionStepExecutor steps,
            AccountOrganizationBindingRepository bindings,
            UserAccountRepository userAccounts,
            TaskExecutionRepository taskExecutions,
            ResponsibilityAssignmentRepository responsibilities,
            CommandReceiptStore receipts,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            GovernanceAuditRecorder governanceAudit,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            tools.jackson.databind.ObjectMapper objectMapper) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.userAccounts = Objects.requireNonNull(userAccounts, "userAccounts");
        this.taskExecutions = Objects.requireNonNull(taskExecutions, "taskExecutions");
        this.responsibilities = Objects.requireNonNull(responsibilities, "responsibilities");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.governanceAudit = Objects.requireNonNull(governanceAudit, "governanceAudit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Read-side precondition report; {@code openReservations} stays null until A02 lands. */
    public DeletionPrecheckView precheck(
            OrganizationAccessContext access, OrganizationId organizationId, UserAccountId accountId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(accountId, "accountId");
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.GOVERNANCE_DELETION);
        return transactions.required(() -> {
            AccountDeletionTarget target = resolveTarget(organizationId, accountId);
            long inFlight = taskExecutions.countInFlightByPrincipals(
                    organizationId, List.of(target.principalId()));
            long active = responsibilities.countActiveByPrincipal(
                    organizationId, target.principalId());
            return new DeletionPrecheckView(accountId, inFlight, active, null);
        });
    }

    /**
     * Queues a deletion behind the hard preconditions; the job waits in PENDING_CONFIRMATION
     * with the checklist pinned and the backup retention statement frozen onto it.
     */
    public CommandExecution<DeletionJobView> create(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            UserAccountId accountId,
            String reason) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(accountId, "accountId");
        authorize(
                context.access(), organizationId, OrganizationCapability.GOVERNANCE_DELETION,
                context.correlationId());
        CommandRequestHash hash = CommandRequestHash.sha256(
                CREATE_COMMAND_TYPE, organizationId.toString(), accountId.toString(), reason);
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(), CREATE_COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(
                () -> executeCreate(context, organizationId, accountId, reason, hash));
    }

    /**
     * The second, deliberate confirmation: the sha-256 of the presented token must match the
     * frozen hash, and the caller must hold the job version it observed (If-Match).
     */
    public DeletionJobView confirm(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            AccountDeletionJobId jobId,
            String confirmationToken,
            long expectedVersion,
            UUID correlationId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(confirmationToken, "confirmationToken");
        Objects.requireNonNull(correlationId, "correlationId");
        authorize(access, organizationId, OrganizationCapability.GOVERNANCE_DELETION, correlationId);
        return transactions.required(
                () -> {
                    AccountDeletionJob job = requireJob(organizationId, jobId);
                    if (job.version() != expectedVersion) {
                        throw new OptimisticLockConflictException(
                                AGGREGATE_TYPE, jobId.toString(), expectedVersion, job.version());
                    }
                    if (!job.matchesConfirmationToken(sha256Hex(confirmationToken))) {
                        throw new DomainValidationException(
                                "accountDeletionJob.confirmationToken", "does not match the expected token");
                    }
                    AccountDeletionJob confirmed =
                            jobs.updateJob(job.markConfirmed(access.actor().id(), timeProvider.now()));
                    appendFact(
                            access.actor().id(),
                            organizationId,
                            confirmed,
                            EventType.from("ORGANIZATION_ACCOUNT_DELETION_CONFIRMED"),
                            correlationId,
                            new OrganizationAccountDeletionConfirmed(
                                    organizationId.value(), confirmed.id().value()));
                    return view(organizationId, confirmed);
                });
    }

    /**
     * Settles every PENDING step in its own short transaction and seals the job once all of them
     * are terminal; interrupted runs resume at the first PENDING step.
     */
    public DeletionJobView process(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            AccountDeletionJobId jobId,
            UUID correlationId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(correlationId, "correlationId");
        authorize(access, organizationId, OrganizationCapability.GOVERNANCE_DELETION, correlationId);
        AccountDeletionJob job = transactions.required(
                () -> {
                    AccountDeletionJob locked = requireJob(organizationId, jobId);
                    if (locked.isTerminal()) {
                        return locked;
                    }
                    if (locked.status() == AccountDeletionJobStatus.PENDING_CONFIRMATION) {
                        throw new DomainValidationException(
                                "accountDeletionJob.status", "confirmation is required before processing");
                    }
                    return jobs.updateJob(
                            locked.markRunning(access.actor().id(), timeProvider.now()));
                });
        if (job.isTerminal()) {
            return view(organizationId, job);
        }
        for (AccountDeletionItem item : jobs.findItems(organizationId, jobId)) {
            if (!item.isPending()) {
                continue;
            }
            processOneItem(organizationId, job, item, access.actor(), correlationId);
        }
        AccountDeletionJob completed = transactions.required(
                () -> {
                    AccountDeletionJob locked = requireJob(organizationId, jobId);
                    List<AccountDeletionItem> items = jobs.findItems(organizationId, jobId);
                    if (locked.isTerminal() || items.stream().anyMatch(AccountDeletionItem::isPending)) {
                        return locked;
                    }
                    AccountDeletionJob sealed = jobs.updateJob(
                            locked.markCompleted(access.actor().id(), timeProvider.now()));
                    appendFact(
                            access.actor().id(),
                            organizationId,
                            sealed,
                            EventType.from("ORGANIZATION_ACCOUNT_DELETION_COMPLETED"),
                            correlationId,
                            new OrganizationAccountDeletionCompleted(
                                    organizationId.value(), sealed.id().value()));
                    return sealed;
                });
        return view(organizationId, completed);
    }

    /** Read face for the reconciliation surface: job plus the settled checklist. */
    public DeletionJobView get(
            OrganizationAccessContext access, OrganizationId organizationId, AccountDeletionJobId jobId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.AUDIT_READ_ORG);
        return view(organizationId, jobs.findJobById(organizationId, jobId)
                .orElseThrow(() -> new AggregateNotFoundException(AGGREGATE_TYPE, jobId)));
    }

    /** Cancels an unconfirmed or partially processed job; DONE steps are never rolled back. */
    public DeletionJobView cancel(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            AccountDeletionJobId jobId,
            UUID correlationId) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(correlationId, "correlationId");
        authorize(access, organizationId, OrganizationCapability.GOVERNANCE_DELETION, correlationId);
        return transactions.required(
                () -> {
                    AccountDeletionJob job = requireJob(organizationId, jobId);
                    if (job.isTerminal()) {
                        throw new DomainValidationException(
                                "accountDeletionJob.status", "job already reached a terminal state");
                    }
                    AccountDeletionJob cancelled =
                            jobs.updateJob(job.markCancelled(access.actor().id(), timeProvider.now()));
                    appendFact(
                            access.actor().id(),
                            organizationId,
                            cancelled,
                            EventType.from("ORGANIZATION_ACCOUNT_DELETION_CANCELLED"),
                            correlationId,
                            new OrganizationAccountDeletionCancelled(
                                    organizationId.value(), cancelled.id().value()));
                    return view(organizationId, cancelled);
                });
    }

    private CommandExecution<DeletionJobView> executeCreate(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            UserAccountId accountId,
            String reason,
            CommandRequestHash hash) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                CREATE_COMMAND_TYPE,
                hash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        AccountDeletionTarget target = resolveTarget(organizationId, accountId);
        long inFlight = taskExecutions.countInFlightByPrincipals(
                organizationId, List.of(target.principalId()));
        long activeResponsibilities =
                responsibilities.countActiveByPrincipal(organizationId, target.principalId());
        if (inFlight != 0 || activeResponsibilities != 0) {
            throw new DomainValidationException(
                    "accountDeletion.preconditions",
                    "in-flight task executions and active responsibilities must both be zero;"
                            + " finish or hand over the work first");
        }
        AccountDeletionJobId jobId = AccountDeletionJobId.generate();
        AccountDeletionJob job = AccountDeletionJob.create(
                jobId,
                organizationId,
                accountId,
                target.principalId(),
                reason,
                sha256Hex(target.username()),
                commandId.toString(),
                reservationCheck(inFlight, activeResponsibilities, now),
                DeletionStepKey.BACKUP_RETENTION_STATEMENT,
                context.access().actor().id(),
                now);
        List<AccountDeletionItem> items = buildChecklist(
                organizationId, jobId, target.principalId(), context.access().actor().id(), now);
        AccountDeletionJob stored = jobs.createJob(job, items);
        UUID eventId = appendFact(
                context.access().actor().id(),
                organizationId,
                stored,
                EventType.from("ORGANIZATION_ACCOUNT_DELETION_REQUESTED"),
                context.correlationId(),
                new OrganizationAccountDeletionRequested(
                        organizationId.value(),
                        stored.id().value(),
                        accountId.value(),
                        target.principalId().value()));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, stored.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return CommandExecution.completed(new DeletionJobView(stored, items), receipt);
    }

    /** Resolves the account's ACTIVE binding in this organization and the username echo source. */
    private AccountDeletionTarget resolveTarget(
            OrganizationId organizationId, UserAccountId accountId) {
        AccountOrganizationBinding binding = bindings.findByAccountId(accountId).stream()
                .filter(candidate -> candidate.organizationId().equals(organizationId))
                .findFirst()
                .orElseThrow(() -> new AggregateNotFoundException("USER_ACCOUNT", accountId));
        if (binding.status() != AccountOrganizationBindingStatus.ACTIVE) {
            throw new DomainValidationException(
                    "accountDeletion.accountBinding", "the account binding must be ACTIVE");
        }
        UserAccount account = userAccounts.findById(accountId)
                .orElseThrow(() -> new AggregateNotFoundException("USER_ACCOUNT", accountId));
        return new AccountDeletionTarget(binding.principalId(), account.username().displayValue());
    }

    /**
     * The thirteen-item checklist: one item per fixed step, one item per derived-row table of
     * DELETE_DERIVED_ROWS, each pinned with the live count observed at creation.
     */
    private List<AccountDeletionItem> buildChecklist(
            OrganizationId organizationId,
            AccountDeletionJobId jobId,
            PrincipalId targetPrincipalId,
            PrincipalId actor,
            UtcTimestamp now) {
        List<AccountDeletionItem> items = new ArrayList<>();
        items.add(itemOf(organizationId, jobId, DeletionStepKey.REVOKE_TASK_CREDENTIAL_GRANTS,
                "TASK_CREDENTIAL_GRANT", targetPrincipalId, actor, now));
        items.add(itemOf(organizationId, jobId, DeletionStepKey.REVOKE_SUBJECT_CREDENTIALS,
                "LOCAL_CREDENTIAL", targetPrincipalId, actor, now));
        items.add(itemOf(organizationId, jobId, DeletionStepKey.TOMBSTONE_ACCOUNT_ARTIFACTS,
                "ARTIFACT_REFERENCE", targetPrincipalId, actor, now));
        for (String table : DeletionStepKey.DERIVED_ROW_TARGETS) {
            items.add(itemOf(organizationId, jobId, DeletionStepKey.DELETE_DERIVED_ROWS,
                    table, targetPrincipalId, actor, now));
        }
        items.add(itemOf(organizationId, jobId, DeletionStepKey.TOMBSTONE_IDENTITY,
                "IDENTITY_CHAIN", targetPrincipalId, actor, now));
        items.add(itemOf(organizationId, jobId, DeletionStepKey.RECORD_BACKUP_RETENTION,
                "BACKUP_RETENTION_STATEMENT", targetPrincipalId, actor, now));
        return items;
    }

    private AccountDeletionItem itemOf(
            OrganizationId organizationId,
            AccountDeletionJobId jobId,
            DeletionStepKey stepKey,
            String targetRef,
            PrincipalId targetPrincipalId,
            PrincipalId actor,
            UtcTimestamp now) {
        long count = steps.countStep(organizationId, targetPrincipalId, stepKey, targetRef);
        return AccountDeletionItem.create(
                AccountDeletionItemId.generate(),
                jobId,
                organizationId,
                stepKey.name(),
                targetRef,
                (int) Math.min(count, Integer.MAX_VALUE),
                actor,
                now);
    }

    /**
     * One step = one short transaction: lock the item, re-observe the live count against the
     * pinned expectation, run the idempotent action only when they agree, then settle and write
     * the direct governance audit fact — including stopped outcomes.
     */
    private void processOneItem(
            OrganizationId organizationId,
            AccountDeletionJob job,
            AccountDeletionItem item,
            io.crewscope.domain.identity.Principal actor,
            UUID correlationId) {
        transactions.required(
                () -> {
                    AccountDeletionItem locked = jobs
                            .lockItemById(organizationId, item.id())
                            .orElseThrow(
                                    () -> new AggregateNotFoundException("ACCOUNT_DELETION_ITEM", item.id()));
                    if (!locked.isPending()) {
                        return (Void) null;
                    }
                    DeletionStepKey stepKey = DeletionStepKey.valueOf(locked.stepKey());
                    AccountDeletionItem settled;
                    if (stepKey == DeletionStepKey.RECORD_BACKUP_RETENTION) {
                        // The statement is frozen onto the job row; the step itself touches no rows.
                        settled = locked.markDone(0, actor.id(), timeProvider.now());
                    } else {
                        long live = steps.countStep(
                                organizationId, job.targetPrincipalId(), stepKey, locked.targetRef());
                        if (live != locked.expectedCount()) {
                            settled = locked.markConflict(
                                    live < locked.expectedCount()
                                            ? "EXPECTED_ROWS_ABSENT"
                                            : "UNEXPECTED_ROWS_ADDED",
                                    actor.id(),
                                    timeProvider.now());
                        } else {
                            settled = locked.markDone(
                                    steps.executeStep(
                                            organizationId,
                                            job.targetPrincipalId(),
                                            stepKey,
                                            locked.targetRef()),
                                    actor.id(),
                                    timeProvider.now());
                        }
                    }
                    jobs.updateItem(settled);
                    governanceAudit.record(new GovernanceAuditRecord(
                            GovernanceAuditRecord.Operation.DELETION_STEP,
                            organizationId,
                            actor,
                            correlationId,
                            outcomeOf(settled),
                            job.id() + ":" + settled.stepKey() + ":" + settled.targetRef(),
                            settled.resultCount().orElse(settled.expectedCount()),
                            settled.errorCode().orElse(null),
                            timeProvider.now()));
                    return (Void) null;
                });
    }

    private static AuditOutcome outcomeOf(AccountDeletionItem settled) {
        return switch (settled.state()) {
            case DONE -> AuditOutcome.SUCCEEDED;
            case CONFLICT -> AuditOutcome.FAILED;
            case DENIED -> AuditOutcome.DENIED;
            case PENDING -> throw new IllegalStateException("settled item is still PENDING");
        };
    }

    private String reservationCheck(long inFlight, long activeResponsibilities, UtcTimestamp checkedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("inFlightTaskExecutions", inFlight);
        snapshot.put("activeResponsibilities", activeResponsibilities);
        snapshot.put("openReservations", null);
        snapshot.put("checkedAt", checkedAt.value().toString());
        return objectMapper.writeValueAsString(snapshot);
    }

    private DeletionJobView view(OrganizationId organizationId, AccountDeletionJob job) {
        return new DeletionJobView(job, jobs.findItems(organizationId, job.id()));
    }

    private AccountDeletionJob requireJob(OrganizationId organizationId, AccountDeletionJobId jobId) {
        return jobs.lockJobById(organizationId, jobId)
                .orElseThrow(() -> new AggregateNotFoundException(AGGREGATE_TYPE, jobId));
    }

    /**
     * Write-face authorization that leaves a SECURITY fact on denial (S01 freeze 3.4): record,
     * then rethrow. Read faces stay silent — a 403 on a query is observability, not audit.
     */
    private void authorize(
            OrganizationAccessContext access,
            OrganizationId organizationId,
            OrganizationCapability capability,
            UUID correlationId) {
        try {
            authorization.requireCapability(access, organizationId, capability);
        } catch (PolicyDeniedException denied) {
            try {
                governanceAudit.record(new GovernanceAuditRecord(
                        GovernanceAuditRecord.Operation.GOVERNANCE_DENIED,
                        organizationId,
                        access.actor(),
                        correlationId,
                        AuditOutcome.DENIED,
                        capability.name(),
                        0,
                        denied.getMessage(),
                        timeProvider.now()));
            } catch (RuntimeException recordingFailure) {
                denied.addSuppressed(recordingFailure);
            }
            throw denied;
        }
    }

    private UUID appendFact(
            PrincipalId actor,
            OrganizationId organizationId,
            AccountDeletionJob job,
            EventType eventType,
            UUID correlationId,
            DomainEvent payload) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                eventType,
                SchemaVersion.V1,
                organizationId,
                Optional.empty(),
                Optional.empty(),
                AggregateReference.of(AGGREGATE_TYPE, job.id()),
                job.version(),
                EventActor.principal(EventActorType.USER, actor),
                correlationId,
                Optional.empty(),
                Optional.empty(),
                timeProvider.now(),
                payload);
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return eventId;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 digest is unavailable", unavailable);
        }
    }

    /** Job plus its checklist — the shape every read/confirm/process/cancel path returns. */
    public record DeletionJobView(AccountDeletionJob job, List<AccountDeletionItem> items) {

        public DeletionJobView {
            job = Objects.requireNonNull(job, "job");
            items = List.copyOf(items);
        }
    }

    /** The precondition report of the read-side precheck face. */
    public record DeletionPrecheckView(
            UserAccountId accountId,
            long inFlightTaskExecutions,
            long activeResponsibilities,
            Long openReservations) {
    }

    private record AccountDeletionTarget(PrincipalId principalId, String username) {
    }
}
