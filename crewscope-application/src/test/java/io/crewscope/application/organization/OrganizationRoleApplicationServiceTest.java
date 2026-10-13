package io.crewscope.application.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.role.LastOrganizationOwnerProtectionException;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRoleGrantConflictException;
import io.crewscope.domain.organization.role.OrganizationRoleGrantId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
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

/**
 * Role-management guardrails: the OWNER-only bailouts, the last-owner protection, the initial
 * owner provisioning for fresh installs, and receipt replay behavior.
 */
class OrganizationRoleApplicationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId actorId = PrincipalId.generate();
    private final PrincipalId targetId = PrincipalId.generate();
    private final Principal actor = Principal.create(
            actorId,
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Governor",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final Principal target = Principal.create(
            targetId,
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Member",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private OrganizationRoleGrantRepository grants;
    private PrincipalRepository principals;
    private CommandReceiptStore receipts;
    private DomainEventStore eventStore;
    private OrganizationRoleApplicationService service;

    @BeforeEach
    void setUp() {
        grants = mock(OrganizationRoleGrantRepository.class);
        principals = mock(PrincipalRepository.class);
        receipts = mock(CommandReceiptStore.class);
        eventStore = mock(DomainEventStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        service = new OrganizationRoleApplicationService(
                grants,
                new OrganizationAuthorization(grants),
                principals,
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
    void grantAppendsTheReviewedFactAndReturnsTheStoredGrant() {
        asOwner();
        targetIsAUser();
        when(grants.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var execution = service.grant(
                command(), organizationId, targetId, OrganizationRole.ORG_AUDITOR, "onboarding");

        assertFalse(execution.replayed());
        assertTrue(execution.result().orElseThrow().isActive());
        ArgumentCaptor<DomainEventEnvelope<DomainEvent>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(envelope.capture());
        assertEquals("ORGANIZATION_ROLE_GRANTED", envelope.getValue().eventType().value());
        assertEquals(0, envelope.getValue().aggregateVersion());
    }

    @Test
    void grantRejectsAnUnknownTargetPrincipal() {
        asOwner();
        when(principals.findById(organizationId, targetId)).thenReturn(Optional.empty());

        assertThrows(
                AggregateNotFoundException.class,
                () -> service.grant(
                        command(), organizationId, targetId, OrganizationRole.ORG_ADMIN, null));
        verify(grants, never()).create(any());
    }

    @Test
    void anAdminCannotAssignTheOwnerRole() {
        asAdmin();
        targetIsAUser();

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> service.grant(
                        command(), organizationId, targetId, OrganizationRole.ORG_OWNER, null));
        assertEquals("assign the ORG_OWNER role", denied.error().details().get("action"));
    }

    @Test
    void revokingTheLastActiveOwnerFailsTheLastOwnerProtection() {
        asOwner();
        OrganizationRoleGrantId grantId = OrganizationRoleGrantId.generate();
        when(grants.findByIdForUpdate(organizationId, grantId))
                .thenReturn(Optional.of(OrganizationRoleGrant.grant(
                        organizationId, targetId, OrganizationRole.ORG_OWNER, actorId, null, NOW)));
        when(grants.countActiveOwners(organizationId)).thenReturn(1L);

        assertThrows(
                LastOrganizationOwnerProtectionException.class,
                () -> service.revoke(command(), organizationId, grantId, 0L, "departure"));
        verify(grants, never()).update(any());
    }

    @Test
    void anAdminRevokesNonOwnerGrantsThroughTheDomainTransition() {
        asAdmin();
        OrganizationRoleGrantId grantId = OrganizationRoleGrantId.generate();
        when(grants.findByIdForUpdate(organizationId, grantId))
                .thenReturn(Optional.of(OrganizationRoleGrant.grant(
                        organizationId, targetId, OrganizationRole.ORG_AUDITOR, actorId, null, NOW)));
        when(grants.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var execution = service.revoke(command(), organizationId, grantId, 0L, "rotation");

        assertEquals(
                OrganizationRoleGrant.GrantStatus.REVOKED,
                execution.result().orElseThrow().status());
        ArgumentCaptor<DomainEventEnvelope<DomainEvent>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(envelope.capture());
        assertEquals("ORGANIZATION_ROLE_REVOKED", envelope.getValue().eventType().value());
    }

    @Test
    void revokeFailsAStaleIfMatch() {
        asAdmin();
        OrganizationRoleGrantId grantId = OrganizationRoleGrantId.generate();
        when(grants.findByIdForUpdate(organizationId, grantId))
                .thenReturn(Optional.of(OrganizationRoleGrant.grant(
                        organizationId, targetId, OrganizationRole.ORG_AUDITOR, actorId, null, NOW)));

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.revoke(command(), organizationId, grantId, 7L, "stale"));
        verify(grants, never()).update(any());
    }

    @Test
    void grantReplaysACompletedReceiptWithoutWriting() {
        asOwner();
        targetIsAUser();
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(receipt));

        var execution = service.grant(
                command(), organizationId, targetId, OrganizationRole.ORG_AUDITOR, null);

        assertTrue(execution.replayed());
        verify(grants, never()).create(any());
        verify(eventStore, never()).append(any());
    }

    @Test
    void ensureInitialOwnerIsANoOpWhenAnOwnerAlreadyExists() {
        when(grants.hasActiveOwner(organizationId)).thenReturn(true);

        service.ensureInitialOwner(organizationId, targetId);

        verify(grants, never()).create(any());
        verify(eventStore, never()).append(any());
    }

    @Test
    void ensureInitialOwnerSwallowsTheConcurrentWinnerConflict() {
        when(grants.hasActiveOwner(organizationId)).thenReturn(false);
        when(grants.create(any())).thenThrow(new OrganizationRoleGrantConflictException());

        service.ensureInitialOwner(organizationId, targetId);

        verify(eventStore, never()).append(any());
    }

    @Test
    void ensureInitialOwnerGrantsTheFirstAccountTheOwnerRole() {
        when(grants.hasActiveOwner(organizationId)).thenReturn(false);
        when(grants.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.ensureInitialOwner(organizationId, targetId);

        ArgumentCaptor<OrganizationRoleGrant> stored =
                ArgumentCaptor.forClass(OrganizationRoleGrant.class);
        verify(grants).create(stored.capture());
        assertEquals(OrganizationRole.ORG_OWNER, stored.getValue().role());
        assertEquals(targetId, stored.getValue().principalId());
        verify(eventStore).append(any());
    }

    private OrganizationCommandContext command() {
        return new OrganizationCommandContext(
                new OrganizationAccessContext(actor),
                IdempotencyKey.from("roles-http-1"),
                UUID.randomUUID(),
                Optional.empty());
    }

    private void asOwner() {
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_OWNER, actorId, null, NOW)));
    }

    private void asAdmin() {
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_ADMIN, actorId, null, NOW)));
    }

    private void targetIsAUser() {
        when(principals.findById(organizationId, targetId)).thenReturn(Optional.of(target));
    }
}
