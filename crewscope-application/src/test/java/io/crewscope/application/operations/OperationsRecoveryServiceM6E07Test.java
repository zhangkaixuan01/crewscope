package io.crewscope.application.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.projection.ProjectionAdministration;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.projection.ProjectionDeadLetterId;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Authorization, strong confirmation, idempotency and audit-boundary tests for M6-E07. */
class OperationsRecoveryServiceM6E07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-26T05:30:00Z");

    private OrganizationId organizationId;
    private Principal actor;
    private TeamAccessContext access;
    private ProjectionAdministration administration;
    private OperationsRecoveryRepository repository;
    private OperationsRecoveryService service;

    @BeforeEach
    void setUp() {
        organizationId = OrganizationId.generate();
        actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        access = new TeamAccessContext(actor, true);
        administration = mock(ProjectionAdministration.class);
        repository = mock(OperationsRecoveryRepository.class);
        service = new OperationsRecoveryService(
                administration, repository, new DirectTransactions(), fixedTime());
    }

    @Test
    void authorizationFailurePerformsNoReceiptOrRecoveryWork() {
        OperationsRecoveryCommand command = command(
                OperationsRecoveryCommandId.generate(), target(0));
        doThrow(new IllegalStateException("denied"))
                .when(administration)
                .requireOrganizationAdministrator(organizationId, access, NOW);

        assertThrows(IllegalStateException.class, () -> service.recover(command));

        verifyNoInteractions(repository);
    }

    @Test
    void confirmationIsBoundToTheExactTargetAndExpectedVersion() {
        NotificationDeliveryRecoveryTarget first = target(1);
        NotificationDeliveryRecoveryTarget changed =
                new NotificationDeliveryRecoveryTarget(first.deliveryId(), 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> new OperationsRecoveryCommand(
                        OperationsRecoveryCommandId.generate(),
                        organizationId,
                        changed,
                        access,
                        OperationsRecoveryStrongConfirmation.confirm(first)));
    }

    @Test
    void firstExecutionPassesOnlySanitizedCoordinatesToAtomicRepository() {
        OperationsRecoveryCommand command = command(
                OperationsRecoveryCommandId.generate(), target(3));
        when(repository.findReceipt(organizationId, command.commandId()))
                .thenReturn(Optional.empty());
        when(repository.recover(any())).thenAnswer(invocation -> receipt(
                invocation.getArgument(0, OperationsRecoveryRequest.class)));

        OperationsRecoveryResult result = service.recover(command);

        ArgumentCaptor<OperationsRecoveryRequest> request =
                ArgumentCaptor.forClass(OperationsRecoveryRequest.class);
        verify(repository).recover(request.capture());
        assertEquals(OperationsRecoveryStatus.SCHEDULED, result.status());
        assertEquals(command.target().referenceHash(), result.targetReferenceHash());
        assertFalse(Arrays.stream(OperationsRecoveryRequest.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .anyMatch(name -> name.contains("phrase")
                        || name.contains("payload")
                        || name.contains("secret")
                        || name.contains("credential")
                        || name.contains("exception")));
    }

    @Test
    void exactReplayReturnsReceiptAndSemanticCommandReuseConflicts() {
        OperationsRecoveryCommandId commandId = OperationsRecoveryCommandId.generate();
        NotificationDeliveryRecoveryTarget target = target(4);
        OperationsRecoveryCommand command = command(commandId, target);
        when(repository.findReceipt(organizationId, commandId)).thenReturn(Optional.empty());
        ArgumentCaptor<OperationsRecoveryRequest> request =
                ArgumentCaptor.forClass(OperationsRecoveryRequest.class);
        when(repository.recover(request.capture())).thenAnswer(invocation -> receipt(
                invocation.getArgument(0, OperationsRecoveryRequest.class)));
        OperationsRecoveryResult first = service.recover(command);
        OperationsRecoveryReceipt committed = receipt(request.getValue());

        reset(repository);
        when(repository.findReceipt(organizationId, commandId))
                .thenReturn(Optional.of(committed));

        assertEquals(first, service.recover(command));
        assertThrows(
                IdempotencyConflictException.class,
                () -> service.recover(command(commandId,
                        new NotificationDeliveryRecoveryTarget(target.deliveryId(), 5))));
        verify(repository, never()).recover(any());
    }

    @Test
    void repositoryConcurrencyConflictFailsClosed() {
        OperationsRecoveryCommand command = command(
                OperationsRecoveryCommandId.generate(), target(1));
        when(repository.findReceipt(organizationId, command.commandId()))
                .thenReturn(Optional.empty());
        when(repository.recover(any())).thenThrow(new IllegalStateException("version conflict"));

        assertThrows(IllegalStateException.class, () -> service.recover(command));
    }

    @Test
    void recoveryTargetsAreAClosedSetWithOpaqueReferenceHashes() {
        OperationsRecoveryTarget outbox = new OutboxDeadLetterRecoveryTarget(
                UUID.randomUUID(), UUID.randomUUID(), 1);
        OperationsRecoveryTarget projection = new ProjectionDeadLetterRecoveryTarget(
                new ProjectionName("team-activity"),
                ProjectionGeneration.FIRST,
                ProjectionDeadLetterId.generate(),
                UUID.randomUUID(),
                2);
        OperationsRecoveryTarget notification = target(3);

        assertTrue(OperationsRecoveryTarget.class.isSealed());
        assertEquals(3, OperationsRecoveryTarget.class.getPermittedSubclasses().length);
        assertEquals(64, outbox.referenceHash().length());
        assertEquals(64, projection.referenceHash().length());
        assertEquals(64, notification.referenceHash().length());
    }

    private OperationsRecoveryCommand command(
            OperationsRecoveryCommandId commandId,
            NotificationDeliveryRecoveryTarget target) {
        return new OperationsRecoveryCommand(
                commandId,
                organizationId,
                target,
                access,
                OperationsRecoveryStrongConfirmation.confirm(target));
    }

    private static NotificationDeliveryRecoveryTarget target(long version) {
        return new NotificationDeliveryRecoveryTarget(
                new NotificationDeliveryId(UUID.randomUUID()), version);
    }

    private static OperationsRecoveryReceipt receipt(OperationsRecoveryRequest request) {
        return new OperationsRecoveryReceipt(
                request.commandId(),
                request.organizationId(),
                request.fingerprint(),
                new OperationsRecoveryResult(
                        request.target().action(),
                        request.target().referenceHash(),
                        OperationsRecoveryStatus.SCHEDULED,
                        request.occurredAt()));
    }

    private static TimeProvider fixedTime() {
        return () -> NOW;
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
