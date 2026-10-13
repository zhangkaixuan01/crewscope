package io.crewscope.application.quota;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.organization.quota.event.OrganizationQuotaConfigurationChanged;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Quota configuration read/write (M12-A02), mirroring the settings service's full ordering:
 * capability check, idempotent replay before the transaction, reserve/insert-or-update under
 * optimistic versioning, then event append + outbox + receipt completion in one transaction.
 * Null dimensions mean "not configured"; the default state is no row at all.
 */
public final class OrganizationQuotaApplicationService {

    private static final String COMMAND_TYPE = "organization.change-quota";
    private static final String AGGREGATE_TYPE = "ORGANIZATION_QUOTA_CONFIGURATION";

    private final OrganizationQuotaConfigurationRepository configurations;
    private final OrganizationAuthorization authorization;
    private final CommandReceiptStore receipts;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public OrganizationQuotaApplicationService(
            OrganizationQuotaConfigurationRepository configurations,
            OrganizationAuthorization authorization,
            CommandReceiptStore receipts,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Read-side projection; {@code materialized} false means the default state (no row). */
    public QuotaConfigurationView findQuota(OrganizationAccessContext access, OrganizationId organizationId) {
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.SETTINGS_READ);
        return configurations.findByOrganization(organizationId)
                .map(configuration -> new QuotaConfigurationView(
                        configuration.monthlyTaskIntakeLimit().orElse(null),
                        configuration.monthlyTokenBudget().orElse(null),
                        configuration.version(),
                        true))
                .orElseGet(() -> new QuotaConfigurationView(null, null, 0, false));
    }

    /** Creates or changes the quota row under optimistic versioning. */
    public CommandExecution<QuotaConfigurationView> changeQuota(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            long expectedVersion,
            Integer monthlyTaskIntakeLimit,
            Long monthlyTokenBudget) {
        Objects.requireNonNull(context, "context");
        authorization.requireCapability(
                context.access(), organizationId, OrganizationCapability.SETTINGS_WRITE);
        CommandRequestHash hash = CommandRequestHash.sha256(
                COMMAND_TYPE,
                organizationId.toString(),
                Long.toString(expectedVersion),
                monthlyTaskIntakeLimit == null ? "" : monthlyTaskIntakeLimit.toString(),
                monthlyTokenBudget == null ? "" : monthlyTokenBudget.toString());
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(), COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(
                () -> executeChange(
                        context, organizationId, expectedVersion, monthlyTaskIntakeLimit, monthlyTokenBudget, hash));
    }

    private CommandExecution<QuotaConfigurationView> executeChange(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            long expectedVersion,
            Integer monthlyTaskIntakeLimit,
            Long monthlyTokenBudget,
            CommandRequestHash hash) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                COMMAND_TYPE,
                hash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        OrganizationQuotaConfiguration stored;
        if (expectedVersion == 0 && configurations.findByOrganization(organizationId).isEmpty()) {
            stored = configurations.insert(OrganizationQuotaConfiguration.initial(
                    organizationId,
                    monthlyTaskIntakeLimit,
                    monthlyTokenBudget,
                    context.access().actor().id(),
                    now));
        } else {
            OrganizationQuotaConfiguration current = configurations.findByOrganization(organizationId)
                    .orElseThrow(() -> new OptimisticLockConflictException(
                            AGGREGATE_TYPE,
                            organizationId.value().toString(),
                            expectedVersion,
                            0));
            stored = configurations.update(current.changeLimits(
                    expectedVersion,
                    monthlyTaskIntakeLimit,
                    monthlyTokenBudget,
                    context.access().actor().id(),
                    now));
        }
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from("ORGANIZATION_QUOTA_CONFIGURATION_CHANGED"),
                SchemaVersion.V1,
                organizationId,
                Optional.empty(),
                Optional.empty(),
                AggregateReference.of(AGGREGATE_TYPE, organizationId),
                stored.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                new OrganizationQuotaConfigurationChanged(
                        organizationId.value(),
                        stored.monthlyTaskIntakeLimit().orElse(null),
                        stored.monthlyTokenBudget().orElse(null),
                        stored.version()));
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        QuotaConfigurationView view = new QuotaConfigurationView(
                stored.monthlyTaskIntakeLimit().orElse(null),
                stored.monthlyTokenBudget().orElse(null),
                stored.version(),
                true);
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, stored.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return CommandExecution.completed(view, receipt);
    }

    /**
     * Read-side shape. Either dimension null means unconfigured for that dimension;
     * {@code materialized} false is the no-row default state at version 0.
     */
    public record QuotaConfigurationView(
            Integer monthlyTaskIntakeLimit,
            Long monthlyTokenBudget,
            long version,
            boolean materialized) {
    }
}
