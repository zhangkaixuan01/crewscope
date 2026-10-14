package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.ExternalAccountIdentity;
import io.crewscope.domain.identity.LoginAttemptPolicy;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityConflictException;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M12-I02 federated login unit coverage: linked re-authentication through the resolver, the
 * registration-mode gate, first-login provisioning of the complete account set, and convergence
 * when a concurrent winner or an occupied username collides with a deterministic derivation.
 */
class OidcAccountAuthenticationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-01T08:00:00Z");
    private static final UtcTimestamp LOCKED_UNTIL = UtcTimestamp.parse("2026-10-01T08:15:00Z");

    private LoginIdentityRepository loginIdentities;
    private UserAccountRepository accounts;
    private PrincipalRepository principals;
    private AccountOrganizationBindingRepository bindings;
    private InitialOrganizationOwnerProvisioner initialOwner;
    private AuthenticatedAccountOrganizationResolver resolver;
    private DomainEventStore events;
    private OutboxRepository outbox;
    private LoginDefense defense;
    private OidcAccountAuthenticationService service;

    @BeforeEach
    void setUp() {
        loginIdentities = mock(LoginIdentityRepository.class);
        accounts = mock(UserAccountRepository.class);
        principals = mock(PrincipalRepository.class);
        bindings = mock(AccountOrganizationBindingRepository.class);
        initialOwner = mock(InitialOrganizationOwnerProvisioner.class);
        resolver = mock(AuthenticatedAccountOrganizationResolver.class);
        events = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        defense = mock(LoginDefense.class);
        TransactionExecutor direct =
                new TransactionExecutor() {
                    @Override
                    public <T> T required(java.util.function.Supplier<T> operation) {
                        return operation.get();
                    }
                };
        service = new OidcAccountAuthenticationService(
                loginIdentities,
                accounts,
                principals,
                bindings,
                initialOwner,
                resolver,
                events,
                outbox,
                direct,
                defense,
                () -> NOW,
                Runnable::run);
    }

    @Test
    void linkedSubjectReauthenticatesThroughTheResolverWithoutProvisioning() {
        Linked linked = linked();
        when(loginIdentities.findByIdentityKeyForUpdate(linked.claims().identityKey()))
                .thenReturn(Optional.of(linked.identity()));
        when(defense.observeAccount(linked.account().id()))
                .thenReturn(CompletableFuture.completedFuture(unlocked()));
        when(resolver.resolveExternal(any(), any()))
                .thenReturn(Optional.of(linked.resolution()));

        // A closed deployment still re-authenticates a linked identity; only first logins gate.
        OidcAuthenticationResult result =
                service.authenticate(command(linked.claims(), RegistrationMode.DISABLED))
                        .toCompletableFuture()
                        .join();

        assertFalse(result.firstLogin());
        assertSame(linked.resolution(), result.resolution());
        verify(loginIdentities).update(any(), eq(linked.identity().version()));
        verify(accounts, never()).create(any());
        verify(principals, never()).createLocalUser(any());
        verify(initialOwner, never()).ensureInitialOwner(any(), any());
    }

    @Test
    void lockedLinkedAccountRecordsOneFailureAndRefusesTheSession() {
        Linked linked = linked();
        when(loginIdentities.findByIdentityKeyForUpdate(linked.claims().identityKey()))
                .thenReturn(Optional.of(linked.identity()));
        when(defense.observeAccount(linked.account().id()))
                .thenReturn(CompletableFuture.completedFuture(locked()));
        when(defense.recordFailure(linked.account().id()))
                .thenReturn(CompletableFuture.completedFuture(locked()));

        assertFailure(
                command(linked.claims(), RegistrationMode.OPEN),
                OidcAuthenticationException.Failure.LOCKED);
        verify(defense).recordFailure(linked.account().id());
        verify(loginIdentities, never()).update(any(), anyLong());
        verify(resolver, never()).resolveExternal(any(), any());
    }

    @Test
    void unresolvableLinkedChainIsDeniedWithoutDescribingTheReason() {
        Linked linked = linked();
        when(loginIdentities.findByIdentityKeyForUpdate(linked.claims().identityKey()))
                .thenReturn(Optional.of(linked.identity()));
        when(defense.observeAccount(linked.account().id()))
                .thenReturn(CompletableFuture.completedFuture(unlocked()));
        when(resolver.resolveExternal(any(), any()))
                .thenReturn(Optional.empty());

        assertFailure(
                command(linked.claims(), RegistrationMode.OPEN),
                OidcAuthenticationException.Failure.DENIED);
        verify(loginIdentities, never()).update(any(), anyLong());
    }

    @Test
    void policyDeniedResolutionIsDenied() {
        Linked linked = linked();
        when(loginIdentities.findByIdentityKeyForUpdate(linked.claims().identityKey()))
                .thenReturn(Optional.of(linked.identity()));
        when(defense.observeAccount(linked.account().id()))
                .thenReturn(CompletableFuture.completedFuture(unlocked()));
        when(resolver.resolveExternal(any(), any()))
                .thenThrow(new PolicyDeniedException("sign in"));

        assertFailure(
                command(linked.claims(), RegistrationMode.OPEN),
                OidcAuthenticationException.Failure.DENIED);
    }

    @Test
    void firstLoginOutsideOpenModeIsBlockedBeforeAnyProvisioning() {
        ExternalAccountIdentity claims = claims("fresh-subject");
        when(loginIdentities.findByIdentityKeyForUpdate(claims.identityKey()))
                .thenReturn(Optional.empty());

        assertFailure(
                command(claims, RegistrationMode.INVITE_ONLY),
                OidcAuthenticationException.Failure.REGISTRATION_BLOCKED);
        verify(accounts, never()).create(any());
        verify(loginIdentities, never()).create(any());
        verify(principals, never()).createLocalUser(any());
    }

    @Test
    void firstLoginProvisionsTheCompleteAccountSetAndEmitsBothEvents() {
        ExternalAccountIdentity claims = claims("fresh-subject");
        OrganizationId organizationId = OrganizationId.generate();
        when(loginIdentities.findByIdentityKeyForUpdate(claims.identityKey()))
                .thenReturn(Optional.empty());
        Provision provision = stubProvision(claims, organizationId);

        OidcAuthenticationResult result =
                service.authenticate(command(claims, RegistrationMode.OPEN, organizationId))
                        .toCompletableFuture()
                        .join();

        assertTrue(result.firstLogin());
        assertSame(provision.account(), result.resolution().account());
        assertSame(provision.principal(), result.resolution().principal());
        ArgumentCaptor<UserAccount> created = ArgumentCaptor.forClass(UserAccount.class);
        verify(accounts, times(1)).create(created.capture());
        assertEquals(
                claims.username(0).displayValue(),
                created.getValue().username().displayValue());
        verify(initialOwner).ensureInitialOwner(organizationId, provision.principal().id());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DomainEventEnvelope> appended =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, times(2)).append(appended.capture());
        assertEquals(
                List.of("USER_ACCOUNT_REGISTERED", "AUTHENTICATION_SUCCEEDED"),
                appended.getAllValues().stream()
                        .map(event -> event.eventType().value())
                        .toList());
        verify(outbox, times(2)).enqueue(any());
        verify(defense, never()).observeAccount(any());
    }

    @Test
    void occupiedUsernameRetriesWithTheNextDeterministicDerivation() {
        ExternalAccountIdentity claims = claims("fresh-subject");
        OrganizationId organizationId = OrganizationId.generate();
        when(loginIdentities.findByIdentityKeyForUpdate(claims.identityKey()))
                .thenReturn(Optional.empty());
        Provision provision = stubProvision(claims, organizationId);
        when(accounts.create(any()))
                .thenThrow(new AccountIdentifierConflictException())
                .thenReturn(provision.account());

        OidcAuthenticationResult result =
                service.authenticate(command(claims, RegistrationMode.OPEN, organizationId))
                        .toCompletableFuture()
                        .join();

        assertTrue(result.firstLogin());
        ArgumentCaptor<UserAccount> retried = ArgumentCaptor.forClass(UserAccount.class);
        verify(accounts, times(2)).create(retried.capture());
        assertEquals(
                claims.username(1).displayValue(),
                retried.getAllValues().get(1).username().displayValue());
    }

    @Test
    void concurrentWinnerRowConvergesOnReauthentication() {
        ExternalAccountIdentity claims = claims("fresh-subject");
        Linked winner = linked();
        when(loginIdentities.findByIdentityKeyForUpdate(claims.identityKey()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner.identity()));
        when(accounts.create(any())).thenReturn(winner.account());
        when(loginIdentities.create(any())).thenThrow(new LoginIdentityConflictException());
        when(defense.observeAccount(winner.account().id()))
                .thenReturn(CompletableFuture.completedFuture(unlocked()));
        when(resolver.resolveExternal(any(), any()))
                .thenReturn(Optional.of(winner.resolution()));

        OidcAuthenticationResult result =
                service.authenticate(command(claims, RegistrationMode.OPEN, winner.organizationId()))
                        .toCompletableFuture()
                        .join();

        assertFalse(result.firstLogin());
        assertSame(winner.resolution(), result.resolution());
        verify(loginIdentities, times(1)).update(any(), anyLong());
        verify(principals, never()).createLocalUser(any());
    }

    @Test
    void exhaustedDerivationsFailClosedAfterEveryAttempt() {
        ExternalAccountIdentity claims = claims("fresh-subject");
        when(loginIdentities.findByIdentityKeyForUpdate(claims.identityKey()))
                .thenReturn(Optional.empty());
        when(accounts.create(any())).thenThrow(new AccountIdentifierConflictException());

        assertFailure(
                command(claims, RegistrationMode.OPEN),
                OidcAuthenticationException.Failure.UNAVAILABLE);
        verify(accounts, times(4)).create(any());
    }

    private void assertFailure(
            OidcAuthenticationCommand command, OidcAuthenticationException.Failure expected) {
        CompletionException wrapped = assertThrows(
                CompletionException.class,
                () -> service.authenticate(command).toCompletableFuture().join());
        OidcAuthenticationException failure =
                assertInstanceOf(OidcAuthenticationException.class, wrapped.getCause());
        assertEquals(expected, failure.failure());
    }

    private static AccountLoginDefenseState unlocked() {
        return new AccountLoginDefenseState(0, Optional.empty(), NOW);
    }

    private static AccountLoginDefenseState locked() {
        return new AccountLoginDefenseState(
                LoginAttemptPolicy.standard().accountFailureLimit(), Optional.of(LOCKED_UNTIL), NOW);
    }

    private static ExternalAccountIdentity claims(String subject) {
        return new ExternalAccountIdentity(
                "company", subject, Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private static OidcAuthenticationCommand command(
            ExternalAccountIdentity claims, RegistrationMode mode) {
        return command(claims, mode, OrganizationId.generate());
    }

    private static OidcAuthenticationCommand command(
            ExternalAccountIdentity claims, RegistrationMode mode, OrganizationId organizationId) {
        return new OidcAuthenticationCommand(
                claims, mode, organizationId, UUID.randomUUID(), Optional.empty());
    }

    /** Wires the provisioning path with a consistent real account set. */
    private Provision stubProvision(ExternalAccountIdentity claims, OrganizationId organizationId) {
        UserAccount account = UserAccount.register(
                UserAccountId.generate(),
                "sso-occupied",
                "sso-occupied@sso.oidc.invalid",
                claims.displayName(),
                NOW);
        LoginIdentity identity = LoginIdentity.external(
                LoginIdentityId.generate(),
                account.id(),
                claims.provider(),
                new LoginIdentitySubject(claims.subject()),
                NOW);
        Principal principal = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                claims.displayName(),
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(), account, organizationId, principal, NOW);
        when(accounts.create(any())).thenReturn(account);
        when(loginIdentities.create(any())).thenReturn(identity);
        when(principals.createLocalUser(any())).thenReturn(principal);
        when(bindings.create(any())).thenReturn(binding);
        return new Provision(account, principal);
    }

    /** A fully consistent linked chain with a resolution the resolver can hand back. */
    private Linked linked() {
        ExternalAccountIdentity claims = claims("linked-subject");
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = UserAccount.register(
                UserAccountId.generate(),
                "existing",
                "existing@example.com",
                "Existing User",
                NOW);
        LoginIdentity identity = LoginIdentity.external(
                LoginIdentityId.generate(),
                account.id(),
                claims.provider(),
                new LoginIdentitySubject(claims.subject()),
                NOW);
        Principal principal = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Existing User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(), account, organizationId, principal, NOW);
        AccountOrganizationResolution resolution =
                new AccountOrganizationResolution(account, identity, binding, principal);
        return new Linked(claims, organizationId, account, identity, resolution);
    }

    private record Provision(UserAccount account, Principal principal) {}

    private record Linked(
            ExternalAccountIdentity claims,
            OrganizationId organizationId,
            UserAccount account,
            LoginIdentity identity,
            AccountOrganizationResolution resolution) {}
}
