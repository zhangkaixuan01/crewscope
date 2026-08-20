package io.crewscope.application.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M4-A07 proof for administrator authority, durable audit and replay idempotency. */
class RuntimeMaintenanceServiceM4A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-20T05:30:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final RuntimeEnvironment environment = new RuntimeEnvironment("development");
    private final CodingRuntimeOperationsPort operations = mock(CodingRuntimeOperationsPort.class);
    private final DomainEventStore events = mock(DomainEventStore.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    @BeforeEach
    void setUp() {
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(operations.maintain(any(), any(), any())).thenAnswer(invocation -> {
            CodingRuntimeMaintenanceOperation operation = invocation.getArgument(2);
            return new CodingRuntimeMaintenanceOutcome(operation, snapshot());
        });
    }

    @Test
    void completesMaintenanceWithDomainEventOutboxAndReceipt() {
        var execution = service().reconcile(context(true), organizationId, environment);

        assertFalse(execution.replayed());
        verify(operations).maintain(
                organizationId, environment, CodingRuntimeMaintenanceOperation.RECONCILE);
        verify(events).append(any());
        verify(outbox).enqueue(any());
        verify(receipts).complete(any(), any(), any(), any());
    }

    @Test
    void replaysTheReceiptWithoutRepeatingPhysicalMaintenance() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(receipt));

        var execution = service().archive(context(true), organizationId, environment);

        assertFalse(execution.result().isPresent());
        verify(operations, never()).maintain(any(), any(), any());
        verify(events, never()).append(any());
    }

    @Test
    void deniesNonAdministratorsBeforeIdempotencyReservationOrPhysicalEffects() {
        assertThrows(PolicyDeniedException.class, () -> service().reconcile(
                context(false), organizationId, environment));

        verify(receipts, never()).reserve(any());
        verify(operations, never()).maintain(any(), any(), any());
    }

    private RuntimeMaintenanceService service() {
        return new RuntimeMaintenanceService(
                operations, events, outbox, receipts, transactions, () -> NOW);
    }

    private TeamCommandContext context(boolean administrator) {
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Operator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        return new TeamCommandContext(
                new TeamAccessContext(actor, administrator),
                IdempotencyKey.from("m4-a07-command-key"),
                UUID.randomUUID(),
                Optional.empty());
    }

    private CodingRuntimeSnapshot snapshot() {
        CodingRuntimeComponentSummary healthy = new CodingRuntimeComponentSummary(
                CodingRuntimeComponentHealth.HEALTHY, 0, 0, 0);
        return new CodingRuntimeSnapshot(
                organizationId,
                environment,
                NOW,
                CodingRuntimeComponentHealth.HEALTHY,
                new RuntimeCapacitySummary(4, 0, 4),
                healthy,
                healthy,
                new CodingCleanupSummary(
                        CodingRuntimeComponentHealth.HEALTHY,
                        true,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        Optional.empty()));
    }
}
