package io.crewscope.application.quota;

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
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.application.quota.OrganizationQuotaApplicationService.QuotaConfigurationView;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.organization.quota.event.OrganizationQuotaConfigurationChanged;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
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

/** Quota configuration read/write: default projection, first-row materialization, guardrails. */
class OrganizationQuotaApplicationServiceTest {

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
    private OrganizationQuotaConfigurationRepository configurations;
    private CommandReceiptStore receipts;
    private DomainEventStore eventStore;
    private OrganizationQuotaApplicationService service;

    @BeforeEach
    void setUp() {
        grants = mock(OrganizationRoleGrantRepository.class);
        configurations = mock(OrganizationQuotaConfigurationRepository.class);
        receipts = mock(CommandReceiptStore.class);
        eventStore = mock(DomainEventStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        service = new OrganizationQuotaApplicationService(
                configurations,
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
    void findQuotaProjectsTheNoRowDefaultAsUnconfiguredAtVersionZero() {
        asAdmin();
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.empty());

        QuotaConfigurationView view = service.findQuota(access(), organizationId);

        assertNull(view.monthlyTaskIntakeLimit());
        assertNull(view.monthlyTokenBudget());
        assertEquals(0, view.version());
        assertFalse(view.materialized());
    }

    @Test
    void findQuotaRequiresTheSettingsReadCapability() {
        when(grants.findActiveByPrincipal(organizationId, actorId)).thenReturn(List.of());

        assertThrows(PolicyDeniedException.class, () -> service.findQuota(access(), organizationId));
    }

    @Test
    void changeQuotaMaterializesTheFirstRowAndAppendsTheReviewedFact() {
        asAdmin();
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.empty());
        when(configurations.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var execution = service.changeQuota(command(), organizationId, 0L, 10, 500_000L);

        assertFalse(execution.replayed());
        assertEquals(Optional.of(new QuotaConfigurationView(10, 500_000L, 0, true)), execution.result());
        ArgumentCaptor<DomainEventEnvelope<DomainEvent>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(envelope.capture());
        assertEquals(
                "ORGANIZATION_QUOTA_CONFIGURATION_CHANGED",
                envelope.getValue().eventType().value());
        OrganizationQuotaConfigurationChanged payload =
                (OrganizationQuotaConfigurationChanged) envelope.getValue().payload();
        assertEquals(10, payload.monthlyTaskIntakeLimitAfter());
        assertEquals(500_000L, payload.monthlyTokenBudgetAfter());
        assertEquals(0, payload.versionAfter());
        verify(configurations).insert(any(OrganizationQuotaConfiguration.class));
        verify(configurations, never()).update(any());
    }

    @Test
    void changeQuotaRejectsLimitsBelowOne() {
        asAdmin();
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.empty());

        assertThrows(
                DomainValidationException.class,
                () -> service.changeQuota(command(), organizationId, 0L, 0, null));
        assertThrows(
                DomainValidationException.class,
                () -> service.changeQuota(command(), organizationId, 0L, null, 0L));
        verify(configurations, never()).insert(any());
    }

    @Test
    void changeQuotaUpdatesAnExistingRowThroughTheDomainTransition() {
        asAdmin();
        OrganizationQuotaConfiguration stored = OrganizationQuotaConfiguration.reconstitute(
                organizationId, 10, 500_000L, 3, NOW, actorId, NOW, actorId);
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.of(stored));
        when(configurations.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var execution = service.changeQuota(command(), organizationId, 3L, null, null);

        assertEquals(Optional.of(new QuotaConfigurationView(null, null, 4, true)), execution.result());
        verify(configurations).update(any(OrganizationQuotaConfiguration.class));
        verify(configurations, never()).insert(any());
    }

    @Test
    void changeQuotaFailsAStaleIfMatchWithAnOptimisticLockConflict() {
        asAdmin();
        OrganizationQuotaConfiguration stored = OrganizationQuotaConfiguration.reconstitute(
                organizationId, 10, 500_000L, 3, NOW, actorId, NOW, actorId);
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.of(stored));

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.changeQuota(command(), organizationId, 2L, 20, 600_000L));
        verify(configurations, never()).update(any());
    }

    @Test
    void changeQuotaReplaysACompletedReceiptWithoutWriting() {
        asAdmin();
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(receipt));

        var execution = service.changeQuota(command(), organizationId, 0L, 10, 500_000L);

        assertTrue(execution.replayed());
        assertEquals(receipt, execution.receipt());
        verify(configurations, never()).insert(any());
        verify(configurations, never()).update(any());
        verify(eventStore, never()).append(any());
    }

    private OrganizationAccessContext access() {
        return new OrganizationAccessContext(actor);
    }

    private OrganizationCommandContext command() {
        return new OrganizationCommandContext(
                access(), IdempotencyKey.from("quota-http-1"), UUID.randomUUID(), Optional.empty());
    }

    private void asAdmin() {
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_ADMIN, actorId, null, NOW)));
    }
}
