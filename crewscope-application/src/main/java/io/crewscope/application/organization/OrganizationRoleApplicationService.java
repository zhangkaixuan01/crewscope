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
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.organization.role.LastOrganizationOwnerProtectionException;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRoleGrantConflictException;
import io.crewscope.domain.organization.role.OrganizationRoleGrantId;
import io.crewscope.domain.organization.role.event.OrganizationRoleGranted;
import io.crewscope.domain.organization.role.event.OrganizationRoleRevoked;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Organization-role management (M12-A01, S01 freeze section 3.6).
 *
 * <p>Commands carry a client {@code Idempotency-Key} with durable receipts, append reviewed domain
 * facts (audit-event projection source) and enqueue outbox rows in one transaction. The last-owner
 * protection and the OWNER-only bailouts are re-checked inside that transaction.
 */
public final class OrganizationRoleApplicationService {

    private static final String GRANT_COMMAND_TYPE = "organization.grant-role";
    private static final String REVOKE_COMMAND_TYPE = "organization.revoke-role";
    private static final String AGGREGATE_TYPE = "ORGANIZATION_ROLE_GRANT";

    private final OrganizationRoleGrantRepository grants;
    private final OrganizationAuthorization authorization;
    private final PrincipalRepository principals;
    private final CommandReceiptStore receipts;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public OrganizationRoleApplicationService(
            OrganizationRoleGrantRepository grants,
            OrganizationAuthorization authorization,
            PrincipalRepository principals,
            CommandReceiptStore receipts,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.grants = Objects.requireNonNull(grants, "grants");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Lists all grants of the organization, active first — the role-management audit face. */
    public List<OrganizationRoleGrant> listGrants(
            OrganizationAccessContext access, OrganizationId organizationId) {
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.AUDIT_READ_ORG);
        return grants.findByOrganization(organizationId);
    }

    /** Grants an ORG_* role to a principal of the same organization. */
    public CommandExecution<OrganizationRoleGrant> grant(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            PrincipalId principalId,
            OrganizationRole role,
            String reason) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(role, "role");
        authorization.requireCapability(
                context.access(),
                organizationId,
                role == OrganizationRole.ORG_OWNER
                        ? OrganizationCapability.ROLE_ASSIGN_OWNER
                        : OrganizationCapability.ROLE_ASSIGN);
        CommandRequestHash hash = CommandRequestHash.sha256(
                GRANT_COMMAND_TYPE,
                organizationId.toString(),
                principalId.toString(),
                role.name(),
                reason == null ? "" : reason.strip());
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(), GRANT_COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(
                () -> executeGrant(context, organizationId, principalId, role, reason, hash));
    }

    /** Revokes a grant; revoking the last active ORG_OWNER fails the last-owner protection. */
    public CommandExecution<OrganizationRoleGrant> revoke(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            OrganizationRoleGrantId grantId,
            long expectedVersion,
            String reason) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(grantId, "grantId");
        authorization.requireCapability(
                context.access(), organizationId, OrganizationCapability.ROLE_ASSIGN);
        CommandRequestHash hash = CommandRequestHash.sha256(
                REVOKE_COMMAND_TYPE,
                organizationId.toString(),
                grantId.toString(),
                Long.toString(expectedVersion),
                reason == null ? "" : reason.strip());
        Optional<CommandReceipt> completed =
                receipts.findCompleted(organizationId, context.idempotencyKey(), REVOKE_COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(
                () -> executeRevoke(context, organizationId, grantId, expectedVersion, reason, hash));
    }

    /**
     * Idempotent initial-owner provisioning for the account-creation chain (fresh installs, where
     * the V68 migration backfill found no accounts yet). Concurrent winners of the partial unique
     * index turn the loser's insert into a no-op.
     */
    public void ensureInitialOwner(OrganizationId organizationId, PrincipalId principalId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principalId, "principalId");
        if (grants.hasActiveOwner(organizationId)) {
            return;
        }
        UtcTimestamp now = timeProvider.now();
        OrganizationRoleGrant grant = OrganizationRoleGrant.grant(
                organizationId, principalId, OrganizationRole.ORG_OWNER, principalId, null, now);
        try {
            grants.create(grant);
        } catch (OrganizationRoleGrantConflictException raceLost) {
            return;
        }
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from("ORGANIZATION_ROLE_GRANTED"),
                SchemaVersion.V1,
                organizationId,
                Optional.empty(),
                Optional.empty(),
                AggregateReference.of(AGGREGATE_TYPE, grant.id()),
                grant.version(),
                EventActor.principal(EventActorType.USER, principalId),
                UUID.randomUUID(),
                Optional.empty(),
                Optional.empty(),
                now,
                new OrganizationRoleGranted(
                        organizationId.value(), principalId.value(), grant.role().name(), grant.version()));
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    }

    private CommandExecution<OrganizationRoleGrant> executeGrant(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            PrincipalId principalId,
            OrganizationRole role,
            String reason,
            CommandRequestHash hash) {
        // Replays still revalidate the target on the current facts before revealing a receipt.
        Principal target = principals
                .findById(organizationId, principalId)
                .orElseThrow(() -> new AggregateNotFoundException("PRINCIPAL", principalId));
        if (target.type() != PrincipalType.USER || !target.canAct()
                || !target.scope().organizationId().equals(organizationId)) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "organizationRoleGrant.principalId",
                    "must reference an active USER principal of this organization");
        }
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                GRANT_COMMAND_TYPE,
                hash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        OrganizationRoleGrant grant = OrganizationRoleGrant.grant(
                organizationId,
                principalId,
                role,
                context.access().actor().id(),
                reason,
                now);
        OrganizationRoleGrant stored = grants.create(grant);
        UUID eventId = appendFact(
                context,
                organizationId,
                stored,
                EventType.from("ORGANIZATION_ROLE_GRANTED"),
                new OrganizationRoleGranted(
                        organizationId.value(),
                        principalId.value(),
                        role.name(),
                        stored.version()));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, stored.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return CommandExecution.completed(stored, receipt);
    }

    private CommandExecution<OrganizationRoleGrant> executeRevoke(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            OrganizationRoleGrantId grantId,
            long expectedVersion,
            String reason,
            CommandRequestHash hash) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                REVOKE_COMMAND_TYPE,
                hash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        // Serialize same-organization revocations BEFORE any row lock: two concurrent
        // revocations of different owner grants hold unrelated row locks, so without this
        // guard both would count two ACTIVE owners and both pass, leaving the organization
        // ownerless (the count itself is an unguarded aggregate read).
        grants.lockAuthorizationChanges(organizationId);
        OrganizationRoleGrant grant = grants
                .findByIdForUpdate(organizationId, grantId)
                .orElseThrow(() -> new AggregateNotFoundException(AGGREGATE_TYPE, grantId));
        if (grant.role() == OrganizationRole.ORG_OWNER) {
            authorization.requireCapability(
                    context.access(), organizationId, OrganizationCapability.ROLE_REVOKE_OWNER);
            if (grants.countActiveOwners(organizationId) <= 1) {
                throw new LastOrganizationOwnerProtectionException(
                        organizationId.toString(), grant.principalId());
            }
        }
        OrganizationRoleGrant revoked = grant.revoke(
                expectedVersion, context.access().actor().id(), reason, now);
        OrganizationRoleGrant stored = grants.update(revoked);
        UUID eventId = appendFact(
                context,
                organizationId,
                stored,
                EventType.from("ORGANIZATION_ROLE_REVOKED"),
                new OrganizationRoleRevoked(
                        organizationId.value(),
                        stored.principalId().value(),
                        stored.role().name(),
                        reason,
                        stored.version()));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, stored.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return CommandExecution.completed(stored, receipt);
    }

    private UUID appendFact(
            OrganizationCommandContext context,
            OrganizationId organizationId,
            OrganizationRoleGrant grant,
            EventType eventType,
            DomainEvent payload) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                eventType,
                SchemaVersion.V1,
                organizationId,
                Optional.empty(),
                Optional.empty(),
                AggregateReference.of(AGGREGATE_TYPE, grant.id()),
                grant.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                timeProvider.now(),
                payload);
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return eventId;
    }
}
