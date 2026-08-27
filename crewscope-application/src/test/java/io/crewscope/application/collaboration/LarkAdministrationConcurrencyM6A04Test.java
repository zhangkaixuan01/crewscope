package io.crewscope.application.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.notification.NotificationAdministrationRepository;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.notification.NotificationDeliveryView;
import io.crewscope.application.notification.NotificationPlanningApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.collaboration.LarkMemberVerificationProof;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Strong-version conflict types used by the M6-A04 HTTP envelope. */
class LarkAdministrationConcurrencyM6A04Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-27T06:00:00Z");

    private LarkMappingAdministration administration;
    private TimeProvider timeProvider;
    private TeamAccessContext access;
    private Principal actor;

    @BeforeEach
    void setUp() {
        administration = mock(LarkMappingAdministration.class);
        timeProvider = mock(TimeProvider.class);
        access = mock(TeamAccessContext.class);
        actor = mock(Principal.class);
        when(timeProvider.now()).thenReturn(NOW);
        when(access.actor()).thenReturn(actor);
    }

    @Test
    void verificationBindingDriftRaisesAnOptimisticConflict() {
        LarkMemberMappingApplicationService mappings = mock(LarkMemberMappingApplicationService.class);
        LarkMemberVerificationProof proof = mock(LarkMemberVerificationProof.class);
        when(proof.providerBindingVersion()).thenReturn(2L);
        when(mappings.verifyMember(any())).thenReturn(proof);
        CommandReceiptStore receipts = mock(CommandReceiptStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        TransactionExecutor transactions = immediateTransactions();
        LarkAdministrationCommandService service = new LarkAdministrationCommandService(
                administration,
                mappings,
                mock(NotificationAdministrationService.class),
                receipts,
                transactions,
                timeProvider);
        ProviderBindingId bindingId = ProviderBindingId.generate();
        TeamCommandContext context = new TeamCommandContext(
                access,
                new IdempotencyKey("m6-a04-binding-conflict"),
                UUID.randomUUID(),
                Optional.empty());

        OptimisticLockConflictException failure = assertThrows(
                OptimisticLockConflictException.class,
                () -> service.verifyMember(
                        context,
                        ORGANIZATION_ID,
                        TEAM_ID,
                        bindingId,
                        1,
                        new LarkOpenId("ou_exact_member")));

        assertEquals("1", failure.error().details().get("expectedVersion"));
        assertEquals("2", failure.error().details().get("actualVersion"));
        verify(receipts, never()).complete(any(), any(), any(), any());
    }

    @Test
    void notificationRedeliveryDistinguishesMissingAndStaleResources() {
        NotificationAdministrationRepository repository =
                mock(NotificationAdministrationRepository.class);
        NotificationPlanningApplicationService planning =
                mock(NotificationPlanningApplicationService.class);
        NotificationAdministrationService service = new NotificationAdministrationService(
                administration, repository, planning, timeProvider);
        NotificationDeliveryId deliveryId = new NotificationDeliveryId(UUID.randomUUID());

        assertThrows(
                AggregateNotFoundException.class,
                () -> service.delivery(access, ORGANIZATION_ID, TEAM_ID, deliveryId));

        when(repository.findDelivery(ORGANIZATION_ID, TEAM_ID, deliveryId))
                .thenReturn(Optional.of(delivery(deliveryId, 7)));
        OptimisticLockConflictException failure = assertThrows(
                OptimisticLockConflictException.class,
                () -> service.redeliver(
                        access,
                        ORGANIZATION_ID,
                        TEAM_ID,
                        deliveryId,
                        6,
                        new NotificationRedeliveryCommandId(UUID.randomUUID())));

        assertEquals("7", failure.error().details().get("actualVersion"));
        verify(planning, never()).redeliverScheduled(any(), any(), any(), anyLong());
    }

    @SuppressWarnings("unchecked")
    private static TransactionExecutor immediateTransactions() {
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(0)).get());
        return transactions;
    }

    private static NotificationDeliveryView delivery(
            NotificationDeliveryId deliveryId, long version) {
        return new NotificationDeliveryView(
                ORGANIZATION_ID,
                TEAM_ID,
                deliveryId,
                TeamMemberId.generate(),
                InboxItemType.EXCEPTION,
                new NotificationTemplateRef(
                        NotificationTemplateId.generate(),
                        new NotificationTemplateVersion(1)),
                ProviderBindingId.generate(),
                NotificationDeliveryStatus.FAILED_FINAL,
                1,
                Optional.of(io.crewscope.domain.notification.NotificationFailureCode.PROVIDER_REJECTED),
                Optional.of("provider_rejected"),
                Optional.empty(),
                NOW,
                NOW,
                version);
    }
}
