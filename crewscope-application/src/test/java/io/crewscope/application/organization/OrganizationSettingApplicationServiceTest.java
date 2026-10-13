package io.crewscope.application.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.organization.OrganizationSettingApplicationService.SettingView;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.setting.OrganizationSetting;
import io.crewscope.domain.organization.setting.event.OrganizationSettingChanged;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Settings read/write behavior: default projection, first-row materialization, guardrails. */
class OrganizationSettingApplicationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId actorId = PrincipalId.generate();
    private final Principal actor = Principal.create(
            actorId,
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Admin",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private OrganizationRoleGrantRepository grants;
    private OrganizationSettingRepository settings;
    private CommandReceiptStore receipts;
    private DomainEventStore eventStore;
    private OrganizationSettingApplicationService service;

    @BeforeEach
    void setUp() {
        grants = mock(OrganizationRoleGrantRepository.class);
        settings = mock(OrganizationSettingRepository.class);
        receipts = mock(CommandReceiptStore.class);
        eventStore = mock(DomainEventStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        service = new OrganizationSettingApplicationService(
                settings,
                new OrganizationAuthorization(grants),
                receipts,
                eventStore,
                mock(OutboxRepository.class),
                new TransactionExecutor() {
                    @Override
                    public <T> T required(java.util.function.Supplier<T> operation) {
                        return operation.get();
                    }
                },
                () -> NOW);
    }

    @Test
    void findSettingsProjectsTheNoRowDefaultAsForeverAtVersionZero() {
        asAdmin();
        when(settings.findByOrganization(organizationId)).thenReturn(Optional.empty());

        SettingView view = service.findSettings(access(), organizationId);

        assertNull(view.auditEventRetentionDays());
        assertEquals(0, view.version());
        assertFalse(view.materialized());
    }

    @Test
    void findSettingsRequiresTheSettingsReadCapability() {
        when(grants.findActiveByPrincipal(organizationId, actorId)).thenReturn(List.of());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.findSettings(access(), organizationId));
    }

    @Test
    void changeSettingsMaterializesTheFirstRowAndAppendsTheReviewedFact() {
        asAdmin();
        when(settings.findByOrganization(organizationId)).thenReturn(Optional.empty());
        when(settings.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var execution = service.changeSettings(command(), organizationId, 0L, 90);

        assertFalse(execution.replayed());
        assertEquals(Optional.of(new SettingView(90, 0, true)), execution.result());
        ArgumentCaptor<DomainEventEnvelope<DomainEvent>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(envelope.capture());
        assertEquals(
                "ORGANIZATION_SETTING_CHANGED",
                envelope.getValue().eventType().value());
        OrganizationSettingChanged payload =
                (OrganizationSettingChanged) envelope.getValue().payload();
        assertEquals(90, payload.auditEventRetentionDaysAfter());
        verify(settings).insert(any(OrganizationSetting.class));
        verify(settings, never()).update(any());
    }

    @Test
    void changeSettingsRejectsRetentionBelowTheThirtyDayFloor() {
        asAdmin();
        when(settings.findByOrganization(organizationId)).thenReturn(Optional.empty());

        assertThrows(
                DomainValidationException.class,
                () -> service.changeSettings(command(), organizationId, 0L, 29));
        verify(settings, never()).insert(any());
    }

    @Test
    void changeSettingsUpdatesAnExistingRowThroughTheDomainTransition() {
        asAdmin();
        OrganizationSetting stored = OrganizationSetting.reconstitute(
                organizationId, 90, 3, NOW, actorId, NOW, actorId);
        when(settings.findByOrganization(organizationId)).thenReturn(Optional.of(stored));
        when(settings.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var execution = service.changeSettings(command(), organizationId, 3L, null);

        assertEquals(Optional.of(new SettingView(null, 4, true)), execution.result());
        verify(settings).update(any(OrganizationSetting.class));
        verify(settings, never()).insert(any());
    }

    @Test
    void changeSettingsFailsAStaleIfMatchWithAnOptimisticLockConflict() {
        asAdmin();
        OrganizationSetting stored = OrganizationSetting.reconstitute(
                organizationId, 90, 3, NOW, actorId, NOW, actorId);
        when(settings.findByOrganization(organizationId)).thenReturn(Optional.of(stored));

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.changeSettings(command(), organizationId, 2L, 365));
        verify(settings, never()).update(any());
    }

    @Test
    void changeSettingsReplaysACompletedReceiptWithoutWriting() {
        asAdmin();
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(receipt));

        var execution = service.changeSettings(command(), organizationId, 0L, 90);

        assertTrue(execution.replayed());
        assertEquals(receipt, execution.receipt());
        verify(settings, never()).insert(any());
        verify(settings, never()).update(any());
        verify(eventStore, never()).append(any());
    }

    private OrganizationAccessContext access() {
        return new OrganizationAccessContext(actor);
    }

    private OrganizationCommandContext command() {
        return new OrganizationCommandContext(
                access(), IdempotencyKey.from("settings-http-1"), UUID.randomUUID(), Optional.empty());
    }

    private void asAdmin() {
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_ADMIN, actorId, null, NOW)));
    }
}
