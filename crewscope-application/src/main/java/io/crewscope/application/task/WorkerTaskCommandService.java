package io.crewscope.application.task;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.LeaseOwnership;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.task.event.WorkerTaskCommandAccepted;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cohesive trusted Worker Port over the existing Claim and Lease/Fencing authorities.
 *
 * <p>Claim remains an in-process bootstrap operation because no Task Token exists before a claim.
 * Every mutation derives its complete ownership coordinates from the authenticated Task Token,
 * reserves an idempotency key, mutates under the live Lease and appends one safe audit event in the
 * same transaction.
 */
public final class WorkerTaskCommandService {

    private static final String TASK_EXECUTION_AGGREGATE = "TASK_EXECUTION";
    private static final String EXECUTION_LEASE_AGGREGATE = "EXECUTION_LEASE";

    private final TaskClaimScheduler claimScheduler;
    private final TaskExecutionLeaseCoordinator leaseCoordinator;
    private final DomainEventStore eventStore;
    private final TaskEventRepository taskEventRepository;
    private final OutboxRepository outboxRepository;
    private final CommandReceiptStore receiptStore;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final Principal workerActor;

    public WorkerTaskCommandService(
            TaskClaimScheduler claimScheduler,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            DomainEventStore eventStore,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            Principal workerActor) {
        this.claimScheduler = Objects.requireNonNull(claimScheduler, "claimScheduler");
        this.leaseCoordinator = Objects.requireNonNull(leaseCoordinator, "leaseCoordinator");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.taskEventRepository = Objects.requireNonNull(
                taskEventRepository, "taskEventRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.workerActor = Objects.requireNonNull(workerActor, "workerActor");
        if (!this.workerActor.canAct()) {
            throw new IllegalArgumentException("workerActor must be active");
        }
    }

    /** Trusted process bootstrap; the configured scheduler closes Runtime and Worker identity. */
    public TaskClaimBatchResult claim(int requestedLimit) {
        return claimScheduler.claim(requestedLimit);
    }

    public CommandExecution<WorkerTaskCommandResult> prepare(
            WorkerCommandContext context, WorkerPrepareCommand command) {
        WorkerPrepareCommand required = Objects.requireNonNull(command, "command");
        return execute(
                context,
                WorkerCommandOperation.PREPARE,
                fields(Long.toString(required.expectedExecutionVersion())),
                Optional.empty(),
                Optional.empty(),
                () -> {
                    TaskExecution execution = leaseCoordinator.beginPreparing(
                            new LeaseExecutionCommand(
                                    leaseScope(context), required.expectedExecutionVersion()));
                    return result(
                            WorkerCommandOperation.PREPARE,
                            Optional.of(execution.version()),
                            Optional.empty());
                });
    }

    public CommandExecution<WorkerTaskCommandResult> start(
            WorkerCommandContext context, WorkerTransitionCommand command) {
        WorkerTransitionCommand required = Objects.requireNonNull(command, "command");
        return transition(
                context,
                WorkerCommandOperation.START,
                required,
                () -> leaseCoordinator.beginRun(new LeaseTransitionCommand(
                        leaseScope(context),
                        required.expectedExecutionVersion(),
                        required.expectedLeaseVersion())));
    }

    public CommandExecution<WorkerTaskCommandResult> heartbeat(
            WorkerCommandContext context, WorkerHeartbeatCommand command) {
        WorkerHeartbeatCommand required = Objects.requireNonNull(command, "command");
        return execute(
                context,
                WorkerCommandOperation.HEARTBEAT,
                fields(Long.toString(required.expectedLeaseVersion())),
                Optional.empty(),
                Optional.empty(),
                () -> {
                    ExecutionLease lease = leaseCoordinator.heartbeat(new LeaseHeartbeatCommand(
                            leaseScope(context), required.expectedLeaseVersion()));
                    return result(
                            WorkerCommandOperation.HEARTBEAT,
                            Optional.empty(),
                            Optional.of(lease.version()));
                });
    }

    public CommandExecution<WorkerTaskCommandResult> progress(
            WorkerCommandContext context, WorkerProgressCommand command) {
        WorkerProgressCommand required = Objects.requireNonNull(command, "command");
        return execute(
                context,
                WorkerCommandOperation.PROGRESS,
                fields(
                        Long.toString(required.expectedExecutionVersion()),
                        required.safeSummary(),
                        required.percent().map(String::valueOf).orElse("")),
                Optional.of(required.safeSummary()),
                required.percent(),
                () -> {
                    TaskExecution execution = leaseCoordinator.updateOwned(
                            new LeaseExecutionCommand(
                                    leaseScope(context), required.expectedExecutionVersion()),
                            TaskExecution::recordProgress);
                    return result(
                            WorkerCommandOperation.PROGRESS,
                            Optional.of(execution.version()),
                            Optional.empty());
                });
    }

    public CommandExecution<WorkerTaskCommandResult> complete(
            WorkerCommandContext context, WorkerTransitionCommand command) {
        WorkerTransitionCommand required = Objects.requireNonNull(command, "command");
        return transition(
                context,
                WorkerCommandOperation.COMPLETE,
                required,
                () -> leaseCoordinator.release(LeaseReleaseCommand.simple(
                        transitionCommand(context, required),
                        ExecutionLeaseReleaseReason.COMPLETED)));
    }

    public CommandExecution<WorkerTaskCommandResult> fail(
            WorkerCommandContext context, WorkerFailCommand command) {
        WorkerFailCommand required = Objects.requireNonNull(command, "command");
        return execute(
                context,
                WorkerCommandOperation.FAIL,
                fields(
                        Long.toString(required.expectedExecutionVersion()),
                        Long.toString(required.expectedLeaseVersion()),
                        required.failure().failureClass().name(),
                        required.failure().code()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(required.failure().failureClass().name()),
                Optional.of(required.failure().code()),
                () -> {
                    LeaseMutationResult mutation = leaseCoordinator.release(
                            new LeaseReleaseCommand(
                                    transitionCommand(context, new WorkerTransitionCommand(
                                            required.expectedExecutionVersion(),
                                            required.expectedLeaseVersion())),
                                    ExecutionLeaseReleaseReason.FAILED,
                                    Optional.empty(),
                                    Optional.of(required.failure())));
                    return result(
                            WorkerCommandOperation.FAIL,
                            Optional.of(mutation.execution().version()),
                            Optional.of(mutation.lease().version()));
                });
    }

    private CommandExecution<WorkerTaskCommandResult> transition(
            WorkerCommandContext context,
            WorkerCommandOperation operation,
            WorkerTransitionCommand command,
            Supplier<LeaseMutationResult> mutation) {
        return execute(
                context,
                operation,
                fields(
                        Long.toString(command.expectedExecutionVersion()),
                        Long.toString(command.expectedLeaseVersion())),
                Optional.empty(),
                Optional.empty(),
                () -> {
                    LeaseMutationResult committed = mutation.get();
                    return result(
                            operation,
                            Optional.of(committed.execution().version()),
                            Optional.of(committed.lease().version()));
                });
    }

    private CommandExecution<WorkerTaskCommandResult> execute(
            WorkerCommandContext context,
            WorkerCommandOperation operation,
            String[] commandFields,
            Optional<String> safeSummary,
            Optional<Integer> progressPercent,
            Supplier<WorkerTaskCommandResult> mutation) {
        return execute(
                context,
                operation,
                commandFields,
                safeSummary,
                progressPercent,
                Optional.empty(),
                Optional.empty(),
                mutation);
    }

    private CommandExecution<WorkerTaskCommandResult> execute(
            WorkerCommandContext context,
            WorkerCommandOperation operation,
            String[] commandFields,
            Optional<String> safeSummary,
            Optional<Integer> progressPercent,
            Optional<String> failureClass,
            Optional<String> failureCode,
            Supplier<WorkerTaskCommandResult> mutation) {
        WorkerCommandContext trusted = Objects.requireNonNull(context, "context");
        WorkerCommandOperation requiredOperation = Objects.requireNonNull(operation, "operation");
        Supplier<WorkerTaskCommandResult> requiredMutation = Objects.requireNonNull(
                mutation, "mutation");
        String commandType = "WORKER_TASK_" + requiredOperation.name();
        CommandRequestHash requestHash = requestHash(
                trusted, commandType, Objects.requireNonNull(commandFields, "commandFields"));
        return transactionExecutor.required(() -> {
            UtcTimestamp occurredAt = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            TaskTokenGrantScope scope = trusted.authorization().scope();
            if (!workerActor.scope().organizationId()
                    .equals(scope.workItemScope().organizationId())) {
                throw new IllegalArgumentException(
                        "Worker actor and Task Token must belong to the same Organization");
            }
            CommandReservation reservation = receiptStore.reserve(new CommandReservationRequest(
                    scope.workItemScope().organizationId(),
                    trusted.idempotencyKey(),
                    commandType,
                    requestHash,
                    commandId,
                    trusted.correlationId(),
                    occurredAt));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            WorkerTaskCommandResult committed = requiredMutation.get();
            long aggregateVersion = committed.taskExecutionVersion()
                    .orElseGet(() -> committed.leaseVersion().orElseThrow());
            UUID eventId = UUID.randomUUID();
            DomainEventEnvelope<WorkerTaskCommandAccepted> event = new DomainEventEnvelope<>(
                    eventId,
                    EventType.from(commandType + "_ACCEPTED"),
                    SchemaVersion.V1,
                    scope.workItemScope().organizationId(),
                    Optional.of(scope.workItemScope().teamId()),
                    Optional.of(scope.workItemScope().workspaceId()),
                    requiredOperation == WorkerCommandOperation.HEARTBEAT
                            ? AggregateReference.of(
                                    EXECUTION_LEASE_AGGREGATE, scope.executionLeaseId())
                            : AggregateReference.of(
                                    TASK_EXECUTION_AGGREGATE, scope.taskExecutionId()),
                    aggregateVersion,
                    EventActor.principal(
                            EventActorType.valueOf(workerActor.type().name()), workerActor.id()),
                    trusted.correlationId(),
                    trusted.causationId(),
                    Optional.of(trusted.idempotencyKey().value()),
                    occurredAt,
                    new WorkerTaskCommandAccepted(
                            scope.taskExecutionId().value(),
                            scope.attempt(),
                            scope.executionLeaseId().value(),
                            requiredOperation.name(),
                            committed.taskExecutionVersion(),
                            committed.leaseVersion(),
                            safeSummary,
                            progressPercent,
                            failureClass,
                            failureCode));
            eventStore.append(event);
            taskEventRepository.append(
                    TaskEventContext.lease(
                            scope.taskId(), scope.taskExecutionId(), scope.executionLeaseId()),
                    event);
            outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
            CommandReceipt receipt = new CommandReceipt(
                    commandId, eventId, aggregateVersion, trusted.correlationId());
            receiptStore.complete(
                    scope.workItemScope().organizationId(),
                    trusted.idempotencyKey(),
                    receipt,
                    occurredAt);
            return CommandExecution.completed(committed, receipt);
        });
    }

    private static LeaseTransitionCommand transitionCommand(
            WorkerCommandContext context, WorkerTransitionCommand command) {
        return new LeaseTransitionCommand(
                leaseScope(context),
                command.expectedExecutionVersion(),
                command.expectedLeaseVersion());
    }

    private static LeaseCommandScope leaseScope(WorkerCommandContext context) {
        TaskTokenGrantScope scope = Objects.requireNonNull(context, "context")
                .authorization().scope();
        return new LeaseCommandScope(
                scope.workItemScope().organizationId(),
                scope.environment(),
                scope.executionLeaseId(),
                new LeaseOwnership(
                        scope.taskExecutionId(),
                        scope.attempt(),
                        scope.runtimeId(),
                        scope.workerId(),
                        scope.claimTokenHash(),
                        scope.fencingToken()));
    }

    private static CommandRequestHash requestHash(
            WorkerCommandContext context, String commandType, String[] commandFields) {
        String[] fields = new String[commandFields.length + 3];
        fields[0] = ownershipFingerprint(context.authorization().scope());
        fields[1] = context.causationId().map(UUID::toString).orElse("");
        fields[2] = Integer.toString(commandFields.length);
        System.arraycopy(commandFields, 0, fields, 3, commandFields.length);
        return CommandRequestHash.sha256(commandType, fields);
    }

    /**
     * Excludes the ephemeral Grant/JTI and Tool/Provider subset so a freshly authenticated,
     * narrower rotated Token can replay the same ownership command after an uncertain response.
     */
    private static String ownershipFingerprint(TaskTokenGrantScope scope) {
        var work = scope.workItemScope();
        var principal = scope.executionPrincipal();
        var safety = scope.safetyOverlay();
        return CommandRequestHash.sha256(
                        "WORKER_TASK_OWNERSHIP_SCOPE",
                        work.organizationId().toString(),
                        work.teamId().toString(),
                        work.workspaceId().toString(),
                        work.projectId().toString(),
                        scope.taskId().toString(),
                        scope.taskExecutionId().toString(),
                        Integer.toString(scope.attempt()),
                        scope.executionLeaseId().toString(),
                        scope.environment().toString(),
                        scope.runtimeId().toString(),
                        scope.workerId().toString(),
                        scope.claimTokenHash().value(),
                        Long.toString(scope.fencingToken().value()),
                        principal.principalId().toString(),
                        principal.assignmentId().toString(),
                        Long.toString(principal.assignmentVersion()),
                        principal.responsibilitySnapshotHash().value(),
                        scope.policySnapshotId().toString(),
                        scope.policySnapshotHash().value(),
                        safety.id().toString(),
                        Long.toString(safety.version()),
                        safety.overlayHash().value())
                .value();
    }

    private static String[] fields(String... values) {
        return values;
    }

    private static WorkerTaskCommandResult result(
            WorkerCommandOperation operation,
            Optional<Long> executionVersion,
            Optional<Long> leaseVersion) {
        return new WorkerTaskCommandResult(operation, executionVersion, leaseVersion);
    }
}
