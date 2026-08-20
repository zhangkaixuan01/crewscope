package io.crewscope.application.runtime;

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
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.event.RuntimeMaintenanceCompleted;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Executes platform-admin-only Coding Runtime maintenance with durable idempotency and audit. */
public final class RuntimeMaintenanceService {

    private static final String AGGREGATE_TYPE = "RUNTIME_MAINTENANCE";

    private final CodingRuntimeOperationsPort operations;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final CommandReceiptStore receiptStore;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;

    public RuntimeMaintenanceService(
            CodingRuntimeOperationsPort operations,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<CodingRuntimeMaintenanceOutcome> reconcile(
            TeamCommandContext context,
            OrganizationId organizationId,
            RuntimeEnvironment environment) {
        return execute(
                context,
                organizationId,
                environment,
                CodingRuntimeMaintenanceOperation.RECONCILE);
    }

    public CommandExecution<CodingRuntimeMaintenanceOutcome> archive(
            TeamCommandContext context,
            OrganizationId organizationId,
            RuntimeEnvironment environment) {
        return execute(
                context,
                organizationId,
                environment,
                CodingRuntimeMaintenanceOperation.ARCHIVE);
    }

    private CommandExecution<CodingRuntimeMaintenanceOutcome> execute(
            TeamCommandContext context,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            CodingRuntimeMaintenanceOperation operation) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        OrganizationId requiredOrganization = Objects.requireNonNull(organizationId, "organizationId");
        RuntimeEnvironment requiredEnvironment = Objects.requireNonNull(environment, "environment");
        CodingRuntimeMaintenanceOperation requiredOperation = Objects.requireNonNull(operation, "operation");
        requirePlatformAdministrator(trusted, requiredOrganization);

        String commandType = "CODING_RUNTIME_" + requiredOperation.name();
        CommandRequestHash requestHash = CommandRequestHash.sha256(
                commandType,
                trusted.access().actor().id().toString(),
                requiredOrganization.toString(),
                requiredEnvironment.value(),
                trusted.causationId().map(UUID::toString).orElse(""));
        return transactionExecutor.required(() -> {
            UtcTimestamp occurredAt = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receiptStore.reserve(new CommandReservationRequest(
                    requiredOrganization,
                    trusted.idempotencyKey(),
                    commandType,
                    requestHash,
                    commandId,
                    trusted.correlationId(),
                    occurredAt));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }

            CodingRuntimeMaintenanceOutcome outcome = operations.maintain(
                    requiredOrganization, requiredEnvironment, requiredOperation);
            validateOutcome(requiredOrganization, requiredEnvironment, requiredOperation, outcome);
            UUID eventId = UUID.randomUUID();
            DomainEventEnvelope<RuntimeMaintenanceCompleted> event = new DomainEventEnvelope<>(
                    eventId,
                    EventType.from(commandType + "_COMPLETED"),
                    SchemaVersion.V1,
                    requiredOrganization,
                    Optional.empty(),
                    Optional.empty(),
                    aggregate(requiredOrganization, requiredEnvironment),
                    0,
                    EventActor.principal(EventActorType.USER, trusted.access().actor().id()),
                    trusted.correlationId(),
                    trusted.causationId(),
                    Optional.of(trusted.idempotencyKey().value()),
                    occurredAt,
                    payload(outcome));
            eventStore.append(event);
            outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
            CommandReceipt receipt = new CommandReceipt(commandId, eventId, 0, trusted.correlationId());
            receiptStore.complete(
                    requiredOrganization, trusted.idempotencyKey(), receipt, occurredAt);
            return CommandExecution.completed(outcome, receipt);
        });
    }

    private static void requirePlatformAdministrator(
            TeamCommandContext context, OrganizationId organizationId) {
        Principal actor = context.access().actor();
        if (!context.access().platformAdministrator()
                || actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("operate Coding Runtime maintenance");
        }
    }

    private static void validateOutcome(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            CodingRuntimeMaintenanceOperation operation,
            CodingRuntimeMaintenanceOutcome outcome) {
        CodingRuntimeMaintenanceOutcome required = Objects.requireNonNull(outcome, "outcome");
        if (required.operation() != operation
                || !required.snapshot().organizationId().equals(organizationId)
                || !required.snapshot().environment().equals(environment)) {
            throw new IllegalStateException("Coding Runtime maintenance returned a mismatched scope");
        }
    }

    private static AggregateReference aggregate(
            OrganizationId organizationId, RuntimeEnvironment environment) {
        UUID id = UUID.nameUUIDFromBytes(("io.crewscope/runtime-maintenance/"
                        + organizationId
                        + "/"
                        + environment.value())
                .getBytes(StandardCharsets.UTF_8));
        return new AggregateReference(AGGREGATE_TYPE, id);
    }

    private static RuntimeMaintenanceCompleted payload(CodingRuntimeMaintenanceOutcome outcome) {
        CodingCleanupSummary cleanup = outcome.snapshot().cleanup();
        return new RuntimeMaintenanceCompleted(
                outcome.operation().name(),
                outcome.snapshot().environment().value(),
                outcome.snapshot().health().name(),
                cleanup.recoveredWorkspaces(),
                cleanup.failedWorkspaces(),
                cleanup.archivedWorkspaces(),
                cleanup.archiveFailures(),
                cleanup.removedSandboxOrphans(),
                cleanup.purgedArtifacts(),
                cleanup.capacityLimited());
    }
}
