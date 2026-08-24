package io.crewscope.application.action;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionCancellationReason;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptId;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.action.ConfirmationId;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ManualResolutionReason;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Member Action boundary from immutable preview through confirmation, delivery and reconciliation. */
public final class ActionDeliveryApplicationService {

    private static final Duration BUNDLE_VALIDITY = Duration.ofMinutes(10);
    private static final String PLAN = "PLAN_SOURCE_DELIVERY_ACTION_BUNDLE";
    private static final String CONFIRM = "CONFIRM_SOURCE_DELIVERY_ACTION_BUNDLE";
    private static final String CANCEL = "CANCEL_SOURCE_DELIVERY_CONFIRMATION";
    private static final String RESOLVE_MANUALLY = "RESOLVE_SOURCE_DELIVERY_ACTION_MANUALLY";
    private static final String CANCELLATION_EVIDENCE = "NO_SIDE_EFFECT_CONFIRMATION_CANCELLED";

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final ResponsibilityAssignmentRepository responsibilities;
    private final ActionDeliveryPlanningResolver planningResolver;
    private final ActionAuthorityFactsResolver authorityResolver;
    private final ActionBundleRepository bundles;
    private final ConfirmationRepository confirmations;
    private final ActionDispatchRepository dispatches;
    private final ActionReceiptRepository actionReceipts;
    private final ExternalResultRepository externalResults;
    private final ActionManualResolutionService manualResolution;
    private final ActionCommandEventPublisher commandEvents;
    private final ActionWorkerEventPublisher workerEvents;
    private final CommandReceiptStore commandReceipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ActionDeliveryApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            ResponsibilityAssignmentRepository responsibilities,
            ActionDeliveryPlanningResolver planningResolver,
            ActionAuthorityFactsResolver authorityResolver,
            ActionBundleRepository bundles,
            ConfirmationRepository confirmations,
            ActionDispatchRepository dispatches,
            ActionReceiptRepository actionReceipts,
            ExternalResultRepository externalResults,
            ActionManualResolutionService manualResolution,
            ActionCommandEventPublisher commandEvents,
            ActionWorkerEventPublisher workerEvents,
            CommandReceiptStore commandReceipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.responsibilities = Objects.requireNonNull(responsibilities, "responsibilities");
        this.planningResolver = Objects.requireNonNull(planningResolver, "planningResolver");
        this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.actionReceipts = Objects.requireNonNull(actionReceipts, "actionReceipts");
        this.externalResults = Objects.requireNonNull(externalResults, "externalResults");
        this.manualResolution = Objects.requireNonNull(manualResolution, "manualResolution");
        this.commandEvents = Objects.requireNonNull(commandEvents, "commandEvents");
        this.workerEvents = Objects.requireNonNull(workerEvents, "workerEvents");
        this.commandReceipts = Objects.requireNonNull(commandReceipts, "commandReceipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Persists the exact preview graph; the managed delivery branch is never client supplied. */
    public CommandExecution<ActionBundle> plan(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            PlanSourceDeliveryActionRequest request) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        PlanSourceDeliveryActionRequest required = Objects.requireNonNull(request, "request");
        CommandRequestHash hash = CommandRequestHash.sha256(
                PLAN,
                trusted.access().actor().id().toString(),
                organizationId.toString(),
                teamId.toString(),
                taskId.toString(),
                executionId.toString(),
                required.reviewDecisionId().toString(),
                required.providerBindingId().toString(),
                required.externalRepositoryId().value(),
                required.expectedRemoteHead().map(value -> value.value()).orElse(""),
                required.title(),
                required.body());
        return transactions.required(() -> {
            Task task = requireRoute(
                    trusted.access(), organizationId, teamId, taskId, executionId);
            requireCurrentOwner(trusted.access().actor(), task.scope(), task.workItemId());
            Optional<CommandReceipt> replay = commandReceipts.findCompleted(
                    organizationId, trusted.idempotencyKey(), PLAN, hash);
            if (replay.isPresent()) {
                return CommandExecution.replayed(replay.orElseThrow());
            }
            ActionDeliveryPlanningFacts planning = planningResolver.resolve(
                    organizationId,
                    teamId,
                    taskId,
                    executionId,
                    required.reviewDecisionId(),
                    required.providerBindingId(),
                    required.externalRepositoryId());
            requireOwner(trusted.access().actor(), planning.authority().responsibility());
            UtcTimestamp now = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = reserve(
                    trusted, organizationId, PLAN, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            ActionBundle bundle = ActionBundle.planSourceCodeDelivery(
                    ActionBundleId.generate(),
                    PlannedActionId.generate(),
                    PlannedActionId.generate(),
                    planning.authority(),
                    required.externalRepositoryId(),
                    planning.providerResourceKey(),
                    planning.deliveryBranch(),
                    required.expectedRemoteHead(),
                    required.title(),
                    required.body(),
                    UtcTimestamp.from(now.value().plus(BUNDLE_VALIDITY)),
                    trusted.access().actor(),
                    now);
            bundles.insert(bundle);
            UUID eventId = commandEvents.bundlePlanned(
                    bundle, eventActor(trusted.access().actor()), trusted.correlationId());
            CommandReceipt receipt = new CommandReceipt(
                    commandId, eventId, bundle.version(), trusted.correlationId());
            commandReceipts.complete(
                    organizationId, trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(bundle, receipt);
        });
    }

    /** Confirms only the latest preview for a ReviewDecision and atomically exposes READY rows. */
    public CommandExecution<Confirmation> confirm(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ActionBundleId bundleId,
            long expectedBundleVersion,
            String expectedDigest) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        String digest = requireDigest(expectedDigest);
        CommandRequestHash hash = CommandRequestHash.sha256(
                CONFIRM,
                trusted.access().actor().id().toString(),
                organizationId.toString(),
                teamId.toString(),
                taskId.toString(),
                executionId.toString(),
                bundleId.toString(),
                Long.toString(expectedBundleVersion),
                digest);
        return transactions.required(() -> {
            requireRoute(trusted.access(), organizationId, teamId, taskId, executionId);
            ActionBundle bundle = requireBundle(
                    organizationId, teamId, taskId, executionId, bundleId);
            requireCurrentOwner(trusted.access().actor(), bundle);
            Optional<CommandReceipt> replay = commandReceipts.findCompleted(
                    organizationId, trusted.idempotencyKey(), CONFIRM, hash);
            if (replay.isPresent()) {
                return CommandExecution.replayed(replay.orElseThrow());
            }
            if (bundle.version() != expectedBundleVersion || !bundle.digest().toString().equals(digest)) {
                throw new DomainValidationException(
                        "confirmation.actionBundle", "must match the reviewed Bundle version and digest");
            }
            ActionBundle latest = bundles.findByReviewDecision(
                            organizationId, bundle.authority().reviewDecision().id())
                    .orElseThrow(() -> new AggregateNotFoundException("ActionBundle", bundleId));
            if (!latest.id().equals(bundle.id())) {
                throw new DomainValidationException(
                        "confirmation.actionBundle", "must be the latest preview for its ReviewDecision");
            }
            if (confirmations.findByBundle(organizationId, bundle.id()).isPresent()) {
                throw new DomainValidationException(
                        "confirmation.actionBundle", "has already been confirmed");
            }
            UtcTimestamp now = timeProvider.now();
            ActionDeliveryPlanningFacts currentPlanning = planningResolver.resolve(
                    organizationId,
                    teamId,
                    taskId,
                    executionId,
                    bundle.authority().reviewDecision().id(),
                    bundle.authority().providerAuthorization().bindingId(),
                    repositoryId(bundle));
            Confirmation confirmation = Confirmation.confirm(
                    ConfirmationId.generate(),
                    bundle,
                    currentPlanning.authority(),
                    trusted.access().actor(),
                    now);
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = reserve(
                    trusted, organizationId, CONFIRM, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            Confirmation committed = confirmations.insert(confirmation);
            List<ActionDispatch> scheduled = bundle.actions().stream()
                    .map(action -> ActionDispatch.schedule(
                            ActionDispatchId.generate(),
                            bundle,
                            action,
                            committed,
                            trusted.access().actor(),
                            now))
                    .toList();
            dispatches.insertAll(scheduled);
            UUID eventId = commandEvents.bundleConfirmed(
                    committed, bundle, eventActor(trusted.access().actor()), trusted.correlationId());
            CommandReceipt receipt = new CommandReceipt(
                    commandId, eventId, committed.version(), trusted.correlationId());
            commandReceipts.complete(
                    organizationId, trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(committed, receipt);
        });
    }

    /** Withdraws unused authorization and cancels every still-unclaimed READY action atomically. */
    public CommandExecution<Confirmation> cancel(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ConfirmationId confirmationId,
            long expectedConfirmationVersion,
            ActionCancellationReason reason) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        ActionCancellationReason requiredReason = Objects.requireNonNull(reason, "reason");
        CommandRequestHash hash = CommandRequestHash.sha256(
                CANCEL,
                trusted.access().actor().id().toString(),
                organizationId.toString(),
                teamId.toString(),
                taskId.toString(),
                executionId.toString(),
                confirmationId.toString(),
                Long.toString(expectedConfirmationVersion),
                requiredReason.name());
        return transactions.required(() -> {
            requireRoute(trusted.access(), organizationId, teamId, taskId, executionId);
            Confirmation confirmation = confirmations.findById(organizationId, confirmationId)
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "ActionConfirmation", confirmationId));
            ActionBundle bundle = requireBundle(
                    organizationId, teamId, taskId, executionId, confirmation.bundleId());
            requireCurrentOwner(trusted.access().actor(), bundle);
            Optional<CommandReceipt> replay = commandReceipts.findCompleted(
                    organizationId, trusted.idempotencyKey(), CANCEL, hash);
            if (replay.isPresent()) {
                return CommandExecution.replayed(replay.orElseThrow());
            }
            UtcTimestamp now = timeProvider.now();
            Confirmation cancelled = confirmation.cancel(
                    expectedConfirmationVersion, requiredReason, trusted.access().actor(), now);
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = reserve(
                    trusted, organizationId, CANCEL, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            Confirmation committed = confirmations.update(cancelled);
            List<ActionReceipt> existingReceipts = receiptsFor(bundle);
            for (ActionDispatch dispatch : dispatches.findByBundle(organizationId, bundle.id())) {
                if (dispatch.status() != ActionDispatchStatus.READY) {
                    continue;
                }
                PlannedAction action = action(bundle, dispatch.actionId());
                ActionReceipt candidate = ActionReceipt.cancelled(
                        ActionReceiptId.generate(),
                        dispatch,
                        action,
                        ActionEvidenceReference.hashed(
                                CANCELLATION_EVIDENCE,
                                committed.id() + ":" + committed.version()),
                        trusted.access().actor(),
                        now);
                ActionReceiptInsertResult inserted = actionReceipts.insertIfAbsent(candidate);
                ActionDispatch changed = dispatch.cancel(
                        dispatch.version(),
                        inserted.receipt(),
                        requiredReason,
                        existingReceipts,
                        now);
                ActionDispatch saved = dispatches.update(changed);
                if (inserted.inserted()) {
                    workerEvents.receiptRecorded(
                            inserted.receipt(), bundle, trusted.correlationId());
                    existingReceipts.add(inserted.receipt());
                }
                workerEvents.dispatchTransitioned(saved, bundle, trusted.correlationId());
            }
            UUID eventId = commandEvents.confirmationCancelled(
                    committed, bundle, eventActor(trusted.access().actor()), trusted.correlationId());
            CommandReceipt receipt = new CommandReceipt(
                    commandId, eventId, committed.version(), trusted.correlationId());
            commandReceipts.complete(
                    organizationId, trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(committed, receipt);
        });
    }

    /** Lists safe Action delivery projections after closing the complete Task attempt route. */
    public List<ActionBundleView> list(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        requireRoute(access, organizationId, teamId, taskId, executionId);
        return bundles.findByTaskExecution(organizationId, executionId).stream()
                .filter(bundle -> bundle.authority().scope().teamId().equals(teamId))
                .filter(bundle -> bundle.authority().taskId().equals(taskId))
                .sorted(Comparator.comparing(
                        (ActionBundle value) -> value.audit().createdAt()).reversed())
                .map(this::view)
                .toList();
    }

    /** Returns one safe Action delivery projection without Worker or credential coordinates. */
    public ActionBundleView get(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ActionBundleId bundleId) {
        requireRoute(access, organizationId, teamId, taskId, executionId);
        return view(requireBundle(organizationId, teamId, taskId, executionId, bundleId));
    }

    /** Exposes the I12 human reconciliation path without exposing Worker claim or Dispatch APIs. */
    public CommandExecution<ActionDispatch> resolveManually(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ActionDispatchId dispatchId,
            long expectedDispatchVersion,
            ActionReceiptResult result,
            Optional<ExternalResultIdentity> externalIdentity,
            Optional<String> targetVersion,
            ManualResolutionReason reason,
            String explanation) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        ResolveActionManuallyCommand command = new ResolveActionManuallyCommand(
                organizationId,
                dispatchId,
                expectedDispatchVersion,
                result,
                externalIdentity,
                targetVersion,
                reason,
                explanation,
                trusted.access().actor());
        CommandRequestHash hash = CommandRequestHash.sha256(
                RESOLVE_MANUALLY,
                trusted.access().actor().id().toString(),
                organizationId.toString(),
                teamId.toString(),
                taskId.toString(),
                executionId.toString(),
                dispatchId.toString(),
                Long.toString(expectedDispatchVersion),
                command.result().name(),
                command.externalIdentity().map(ExternalResultIdentity::safeHash).orElse(""),
                command.targetVersion().orElse(""),
                command.reason().name(),
                command.explanation());
        return transactions.required(() -> {
            requireRoute(
                    trusted.access(), organizationId, teamId, taskId, executionId);
            ActionDispatch dispatch = dispatches.findById(organizationId, dispatchId)
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "ActionDispatch", dispatchId));
            ActionBundle bundle = requireBundle(
                    organizationId, teamId, taskId, executionId, dispatch.bundleId());
            requireCurrentOwner(trusted.access().actor(), bundle);
            Optional<CommandReceipt> replay = commandReceipts.findCompleted(
                    organizationId,
                    trusted.idempotencyKey(),
                    RESOLVE_MANUALLY,
                    hash);
            if (replay.isPresent()) {
                return CommandExecution.replayed(replay.orElseThrow());
            }
            UtcTimestamp now = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = reserve(
                    trusted, organizationId, RESOLVE_MANUALLY, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            ActionDispatch committed = manualResolution.resolve(
                    command, trusted.correlationId());
            UUID eventId = ActionEventIds.stable(
                    "ACTION_DISPATCH_TRANSITIONED",
                    committed.id().value(),
                    committed.version() - 1);
            CommandReceipt receipt = new CommandReceipt(
                    commandId, eventId, committed.version(), trusted.correlationId());
            commandReceipts.complete(
                    organizationId, trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(committed, receipt);
        });
    }

    private ActionBundleView view(ActionBundle bundle) {
        OrganizationId organizationId = bundle.authority().scope().organizationId();
        Optional<Confirmation> confirmation = confirmations.findByBundle(
                organizationId, bundle.id());
        List<ActionDispatch> bundleDispatches = dispatches.findByBundle(
                organizationId, bundle.id());
        List<ActionReceipt> receipts = receiptsFor(bundle);
        List<ExternalResult> results = bundle.actions().stream()
                .map(action -> externalResults.findByAction(organizationId, action.id()))
                .flatMap(Optional::stream)
                .toList();
        return ActionBundleView.from(
                bundle,
                confirmation,
                bundleDispatches,
                receipts,
                results,
                staleReason(bundle));
    }

    private Optional<String> staleReason(ActionBundle bundle) {
        try {
            bundle.requireCurrent(authorityResolver.resolveCurrent(bundle.authority()), timeProvider.now());
            ActionBundle latest = bundles.findByReviewDecision(
                            bundle.authority().scope().organizationId(),
                            bundle.authority().reviewDecision().id())
                    .orElse(bundle);
            return latest.id().equals(bundle.id())
                    ? Optional.empty()
                    : Optional.of("SUPERSEDED");
        } catch (io.crewscope.domain.action.StaleActionBundleException stale) {
            return Optional.of(stale.reason().name());
        } catch (DomainException unavailable) {
            return Optional.of("AUTHORITY_UNAVAILABLE");
        }
    }

    private List<ActionReceipt> receiptsFor(ActionBundle bundle) {
        List<ActionReceipt> receipts = new ArrayList<>();
        bundle.actions().stream()
                .map(PlannedAction::id)
                .map(actionId -> actionReceipts.findReceiptByAction(
                        bundle.authority().scope().organizationId(), actionId))
                .flatMap(Optional::stream)
                .forEach(receipts::add);
        return receipts;
    }

    private Task requireRoute(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        Task task = tasks.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        accessPolicy.requireVisibleWorkItem(
                access,
                organizationId,
                teamId,
                task.scope().projectId(),
                task.workItemId());
        TaskExecution execution = executions.findById(organizationId, executionId)
                .filter(value -> value.taskId().equals(task.id()))
                .filter(value -> value.scope().equals(task.scope()))
                .orElseThrow(() -> new AggregateNotFoundException("TaskExecution", executionId));
        if (task.currentExecutionId().filter(execution.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "actionBundle.taskExecutionId", "must be the current Task attempt");
        }
        return task;
    }

    private ActionBundle requireBundle(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ActionBundleId bundleId) {
        return bundles.findById(organizationId, bundleId)
                .filter(value -> value.authority().scope().teamId().equals(teamId))
                .filter(value -> value.authority().taskId().equals(taskId))
                .filter(value -> value.authority().taskExecutionId().equals(executionId))
                .orElseThrow(() -> new AggregateNotFoundException("ActionBundle", bundleId));
    }

    private void requireCurrentOwner(Principal actor, ActionBundle bundle) {
        requireCurrentOwner(actor, bundle.authority().scope(), bundle.authority().workItemId());
    }

    private void requireCurrentOwner(
            Principal actor,
            io.crewscope.domain.workitem.WorkItemScope scope,
            io.crewscope.domain.workitem.WorkItemId workItemId) {
        var owner = responsibilities.findActiveOwner(scope.organizationId(), workItemId)
                .orElseThrow(() -> new DomainValidationException(
                        "actionBundle.responsibility", "current OWNER is unavailable"));
        requireOwner(actor, owner);
        if (!owner.scope().equals(scope)) {
            throw new DomainValidationException(
                    "actionBundle.responsibility", "current OWNER must belong to the Bundle scope");
        }
    }

    private static void requireOwner(
            Principal actor,
            io.crewscope.domain.responsibility.ResponsibilityAssignment owner) {
        Principal required = Objects.requireNonNull(actor, "actor");
        if (required.type() != PrincipalType.USER
                || !required.canAct()
                || !owner.isActive()
                || owner.role() != ResponsibilityRole.OWNER
                || !owner.actorPrincipalId().equals(required.id())
                || !required.scope().organizationId().equals(owner.scope().organizationId())
                || (required.scope().teamId().isPresent()
                        && required.scope().teamId().filter(owner.scope().teamId()::equals).isEmpty())) {
            throw new DomainValidationException(
                    "actionBundle.owner", "requires the current active human WorkItem OWNER");
        }
    }

    private static PlannedAction action(ActionBundle bundle, PlannedActionId actionId) {
        return bundle.actions().stream()
                .filter(value -> value.id().equals(actionId))
                .findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "actionDispatch.actionId", "must belong to the ActionBundle"));
    }

    private static io.crewscope.domain.action.ExternalRepositoryId repositoryId(
            ActionBundle bundle) {
        return bundle.actions().stream()
                .map(PlannedAction::parameters)
                .filter(io.crewscope.domain.action.PushBranchActionParameters.class::isInstance)
                .map(io.crewscope.domain.action.PushBranchActionParameters.class::cast)
                .map(io.crewscope.domain.action.PushBranchActionParameters::repositoryId)
                .findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "actionBundle.actions", "must contain one Push Branch action"));
    }

    private CommandReservation reserve(
            TeamCommandContext context,
            OrganizationId organizationId,
            String commandType,
            CommandRequestHash hash,
            UUID commandId,
            UtcTimestamp now) {
        return commandReceipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                commandType,
                hash,
                commandId,
                context.correlationId(),
                now));
    }

    private static EventActor eventActor(Principal actor) {
        return EventActor.principal(EventActorType.USER, actor.id());
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ActionBundle digest must be lower-case SHA-256");
        }
        return value;
    }
}
