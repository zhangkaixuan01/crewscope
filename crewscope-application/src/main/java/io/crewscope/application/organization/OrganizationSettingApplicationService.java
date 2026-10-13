package io.crewscope.application.organization;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.organization.setting.OrganizationSetting;
import io.crewscope.domain.organization.setting.event.OrganizationSettingChanged;
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
 * Organization settings read/write (M12-A01). The default state is "no row" (retention forever);
 * the first write materializes it, later writes go through optimistic versioning. Every change
 * appends a reviewed domain fact that the audit-event projection consumes.
 */
public final class OrganizationSettingApplicationService {

    private static final String COMMAND_TYPE = "organization.change-settings";
    private static final String AGGREGATE_TYPE = "ORGANIZATION_SETTING";

    private final OrganizationSettingRepository settings;
    private final OrganizationAuthorization authorization;
    private final CommandReceiptStore receipts;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public OrganizationSettingApplicationService(
            OrganizationSettingRepository settings,
            OrganizationAuthorization authorization,
            CommandReceiptStore receipts,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Read-side projection; {@code materialized} false means the default state (no row). */
    public SettingView findSettings(OrganizationAccessContext access, OrganizationId organizationId) {
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.SETTINGS_READ);
        return settings.findByOrganization(organizationId)
                .map(setting -> new SettingView(
                        setting.auditEventRetentionDays().orElse(null),
                        setting.version(),
                        true))
                .orElseGet(() -> new SettingView(null, 0, false));
    }

    /** Creates or changes the settings row under optimistic versioning. */
    public CommandExecution<SettingView> changeSettings(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            long expectedVersion,
            Integer auditEventRetentionDays) {
        Objects.requireNonNull(context, "context");
        authorization.requireCapability(
                context.access(), organizationId, OrganizationCapability.SETTINGS_WRITE);
        CommandRequestHash hash = CommandRequestHash.sha256(
                COMMAND_TYPE,
                organizationId.toString(),
                Long.toString(expectedVersion),
                auditEventRetentionDays == null ? "" : auditEventRetentionDays.toString());
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(), COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(
                () -> executeChange(context, organizationId, expectedVersion, auditEventRetentionDays, hash));
    }

    private CommandExecution<SettingView> executeChange(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            long expectedVersion,
            Integer auditEventRetentionDays,
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
        OrganizationSetting stored;
        if (expectedVersion == 0 && settings.findByOrganization(organizationId).isEmpty()) {
            stored = settings.insert(OrganizationSetting.initial(
                    organizationId,
                    auditEventRetentionDays,
                    context.access().actor().id(),
                    now));
        } else {
            OrganizationSetting current = settings.findByOrganization(organizationId)
                    .orElseThrow(() -> new OptimisticLockConflictException(
                            AGGREGATE_TYPE,
                            organizationId.value().toString(),
                            expectedVersion,
                            0));
            stored = settings.update(current.changeRetention(
                    expectedVersion,
                    auditEventRetentionDays,
                    context.access().actor().id(),
                    now));
        }
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from("ORGANIZATION_SETTING_CHANGED"),
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
                new OrganizationSettingChanged(
                        organizationId.value(),
                        stored.auditEventRetentionDays().orElse(null),
                        stored.version()));
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        SettingView view = new SettingView(
                stored.auditEventRetentionDays().orElse(null), stored.version(), true);
        return CommandExecution.completed(
                view, new CommandReceipt(commandId, eventId, stored.version(), context.correlationId()));
    }

    /**
     * Read-side shape. {@code auditEventRetentionDays} null means retain forever;
     * {@code materialized} false is the no-row default state at version 0.
     */
    public record SettingView(Integer auditEventRetentionDays, long version, boolean materialized) {
    }
}
